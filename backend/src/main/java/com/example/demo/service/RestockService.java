package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import com.example.demo.security.CurrentUserProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 10.6 智能补货提醒：基于销量历史与库存水位生成补货建议，并支持一键生成采购单。
 */
@Service
public class RestockService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserProvider currentUser;

    public RestockService(JdbcTemplate jdbcTemplate, CurrentUserProvider currentUser) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
    }

    /** 10.6.1 补货建议列表 */
    public Map<String, Object> suggestions(int salesHistoryDays) {
        Integer ruleDays = jdbcTemplate.query(
            "SELECT default_safety_stock_days FROM res_restock_rule ORDER BY id LIMIT 1",
            rs -> rs.next() ? rs.getInt(1) : null);
        int safetyStockDays = ruleDays == null ? 7 : ruleDays;
        int historyDays = salesHistoryDays <= 0 ? 30 : salesHistoryDays;

        List<Map<String, Object>> rows = jdbcTemplate.query("""
            SELECT k.id sku_id, k.sku_code, p.product_name, k.spec_values,
                   COALESCE(SUM(s.quantity-s.locked_quantity),0)::int current_stock,
                   COALESCE(MIN(s.min_stock), MAX(k.safety_stock), 20) safety_stock,
                   (SELECT COALESCE(SUM(oi.quantity),0)
                    FROM ord_order_item oi JOIN ord_order o ON o.id=oi.order_id
                    WHERE oi.sku_id=k.id AND o.status IN (1,2,3,5)
                      AND o.create_time >= CURRENT_DATE - (? || ' days')::interval) sold
            FROM pro_sku k
            JOIN pro_product p ON p.id=k.product_id AND p.deleted=0
            LEFT JOIN sto_stock s ON s.sku_id=k.id AND s.deleted=0
            WHERE k.deleted=0 AND k.status=0 AND p.status=0
            GROUP BY k.id,p.id
            """, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("skuId", rs.getLong("sku_id"));
                row.put("skuCode", rs.getString("sku_code"));
                row.put("productName", rs.getString("product_name"));
                row.put("specValues", rs.getString("spec_values"));
                row.put("currentStock", rs.getInt("current_stock"));
                row.put("safetyStock", rs.getInt("safety_stock"));
                row.put("sold", rs.getLong("sold"));
                return row;
            }, historyDays);

        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (Map<String, Object> base : rows) {
            int currentStock = (Integer) base.get("currentStock");
            int safetyStock = (Integer) base.get("safetyStock");
            long sold = (Long) base.get("sold");
            double avgDailySales = historyDays == 0 ? 0 : (double) sold / historyDays;
            double daysRemaining = avgDailySales <= 0 ? Double.POSITIVE_INFINITY : currentStock / avgDailySales;
            int suggestedQty = computeSuggested(avgDailySales, safetyStockDays, safetyStock, currentStock);
            String urgency = urgency(avgDailySales, currentStock, safetyStock, daysRemaining);

            Long supplierId = jdbcTemplate.query("""
                SELECT s.id FROM pur_supplier s
                WHERE s.status=0
                ORDER BY (SELECT COUNT(*) FROM pur_order o WHERE o.supplier_id=s.id) DESC NULLS LAST
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null);
            String supplierName = supplierId == null ? null
                : jdbcTemplate.queryForObject(
                    "SELECT supplier_name FROM pur_supplier WHERE id = ?", String.class, supplierId);

            Map<String, Object> suggestion = new LinkedHashMap<>();
            suggestion.put("skuId", base.get("skuId"));
            suggestion.put("skuCode", base.get("skuCode"));
            suggestion.put("productName", base.get("productName"));
            suggestion.put("specValues", base.get("specValues"));
            suggestion.put("currentStock", currentStock);
            suggestion.put("safetyStock", safetyStock);
            suggestion.put("avgDailySales", BigDecimal.valueOf(avgDailySales).setScale(2, RoundingMode.HALF_UP));
            suggestion.put("daysOfStockRemaining",
                Double.isInfinite(daysRemaining) ? null : BigDecimal.valueOf(daysRemaining).setScale(1, RoundingMode.HALF_UP));
            suggestion.put("suggestedQuantity", suggestedQty);
            suggestion.put("suggestedSupplierId", supplierId);
            suggestion.put("suggestedSupplierName", supplierName);
            suggestion.put("urgency", urgency);
            suggestion.put("estimatedArrivalDays", 3);
            suggestion.put("reason", buildReason(urgency, avgDailySales, currentStock, suggestedQty));
            suggestions.add(suggestion);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", suggestions.size());
        response.put("list", suggestions);
        return response;
    }

    /** 10.6.2 一键生成补货采购单 */
    @Transactional
    public Map<String, Object> generateOrder(List<Long> suggestionIds, Boolean autoMerge) {
        if (suggestionIds == null || suggestionIds.isEmpty()) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "suggestionIds 不能为空");
        }
        boolean merge = !Boolean.FALSE.equals(autoMerge);
        Map<Long, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Long skuId : suggestionIds) {
            Map<String, Object> data = jdbcTemplate.queryForObject("""
                SELECT k.sku_id, COALESCE(MIN(sup.id),0) supplier_id, COALESCE(MIN(sup.supplier_name),'默认供应商') supplier_name,
                       50 quantity, k.sku_code, p.product_name, COALESCE(p.purchase_price,0) price
                FROM pro_sku k JOIN pro_product p ON p.id=k.product_id
                LEFT JOIN pur_supplier sup ON sup.status=0
                WHERE k.id = ?
                GROUP BY k.sku_id, k.sku_code, p.product_name, p.purchase_price
                """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("skuId", rs.getLong("sku_id"));
                    row.put("supplierId", rs.getLong("supplier_id"));
                    row.put("supplierName", rs.getString("supplier_name"));
                    row.put("quantity", 50);
                    row.put("skuCode", rs.getString("sku_code"));
                    row.put("productName", rs.getString("product_name"));
                    row.put("price", rs.getBigDecimal("price"));
                    return row;
                }, skuId);
            if (data == null) {
                throw new BusinessException(ApiErrorCode.NOT_FOUND, "SKU不存在: " + skuId);
            }
            Long supplierId = ((Number) data.get("supplierId")).longValue();
            if (supplierId == 0) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "未配置可用供应商: " + skuId);
            }
            grouped.computeIfAbsent(supplierId, k -> new ArrayList<>()).add(data);
        }
        List<Map<String, Object>> generated = new ArrayList<>();
        for (Map.Entry<Long, List<Map<String, Object>>> entry : grouped.entrySet()) {
            Long supplierId = entry.getKey();
            List<Map<String, Object>> items = entry.getValue();
            String orderNo = merge ? businessNo("AUTO_PO") : businessNo("AUTO_PO");
            BigDecimal totalAmount = BigDecimal.ZERO;
            List<Map<String, Object>> orderItems = new ArrayList<>();
            for (Map<String, Object> it : items) {
                BigDecimal price = (BigDecimal) it.get("price");
                int qty = (Integer) it.get("quantity");
                BigDecimal subtotal = price.multiply(BigDecimal.valueOf(qty));
                totalAmount = totalAmount.add(subtotal);
                Map<String, Object> oi = new LinkedHashMap<>();
                oi.put("skuId", it.get("skuId"));
                oi.put("skuCode", it.get("skuCode"));
                oi.put("productName", it.get("productName"));
                oi.put("quantity", qty);
                oi.put("estimatedAmount", subtotal);
                orderItems.add(oi);
            }
            Long orderId = jdbcTemplate.queryForObject("""
                INSERT INTO pur_order(order_no, request_id, supplier_id, total_amount, status)
                VALUES (?, NULL, ?, ?, 0) RETURNING id
                """, Long.class, orderNo, supplierId, totalAmount);
            for (Map<String, Object> oi : orderItems) {
                jdbcTemplate.update("""
                    INSERT INTO pur_order_item(order_id, sku_id, sku_code, quantity, price)
                    VALUES (?, ?, ?, ?, ?)
                    """, orderId, oi.get("skuId"), oi.get("skuCode"), oi.get("quantity"),
                    ((BigDecimal) oi.get("estimatedAmount")).divide(BigDecimal.valueOf((Integer) oi.get("quantity")), 2, RoundingMode.HALF_UP));
            }
            jdbcTemplate.update("""
                INSERT INTO res_restock_suggestion(sku_id, sku_code, product_name, current_stock, safety_stock,
                                                  avg_daily_sales, days_remaining, suggested_quantity,
                                                  suggested_supplier_id, suggested_supplier_name, status)
                VALUES (?, ?, ?, 0, 0, 0, 0, 0, ?, ?, 1)
                ON CONFLICT DO NOTHING
                """);

            Map<String, Object> generatedRow = new LinkedHashMap<>();
            generatedRow.put("orderId", orderId);
            generatedRow.put("orderNo", orderNo);
            generatedRow.put("supplierId", supplierId);
            generatedRow.put("supplierName", items.getFirst().get("supplierName"));
            generatedRow.put("items", orderItems);
            generatedRow.put("totalAmount", totalAmount);
            generated.add(generatedRow);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generatedOrders", generated);
        return response;
    }

    /** 10.6.3 补货规则配置 */
    public Map<String, Object> updateRule(Integer defaultSafetyStockDays, Integer defaultLeadTimeDays,
                                          Integer salesHistoryDays, Boolean enableAutoNotify,
                                          List<String> notifyChannels) {
        String channels = notifyChannels == null ? null : String.join(",", notifyChannels);
        jdbcTemplate.update("""
            INSERT INTO res_restock_rule(id, default_safety_stock_days, default_lead_time_days,
                                         sales_history_days, enable_auto_notify, notify_channels, update_time)
            VALUES (1, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET
                default_safety_stock_days = EXCLUDED.default_safety_stock_days,
                default_lead_time_days = EXCLUDED.default_lead_time_days,
                sales_history_days = EXCLUDED.sales_history_days,
                enable_auto_notify = EXCLUDED.enable_auto_notify,
                notify_channels = EXCLUDED.notify_channels,
                update_time = CURRENT_TIMESTAMP
            """, defaultSafetyStockDays, defaultLeadTimeDays, salesHistoryDays,
            Boolean.TRUE.equals(enableAutoNotify), channels);

        return jdbcTemplate.queryForObject("""
            SELECT default_safety_stock_days, default_lead_time_days, sales_history_days,
                   enable_auto_notify, notify_channels
            FROM res_restock_rule WHERE id = 1
            """, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("defaultSafetyStockDays", rs.getInt("default_safety_stock_days"));
                row.put("defaultLeadTimeDays", rs.getInt("default_lead_time_days"));
                row.put("salesHistoryDays", rs.getInt("sales_history_days"));
                row.put("enableAutoNotify", rs.getBoolean("enable_auto_notify"));
                String ch = rs.getString("notify_channels");
                row.put("notifyChannels", ch == null ? List.of() : List.of(ch.split(",")));
                return row;
            });
    }

    private int computeSuggested(double avgDailySales, int safetyStockDays, int safetyStock, int currentStock) {
        double target = Math.max(avgDailySales * safetyStockDays, safetyStock);
        int qty = (int) Math.ceil(target - currentStock);
        return Math.max(qty, 0);
    }

    private String urgency(double avgDailySales, int currentStock, int safetyStock, double daysRemaining) {
        if (currentStock <= 0) return "HIGH";
        if (avgDailySales > 0 && daysRemaining < 3) return "HIGH";
        if (currentStock < safetyStock) return "MEDIUM";
        return "LOW";
    }

    private String buildReason(String urgency, double avgDailySales, int currentStock, int qty) {
        if (urgency.equals("HIGH") && currentStock <= 0) {
            return "当前库存为 0，建议立即补货";
        }
        if (avgDailySales <= 0) {
            return "近期无销量，建议根据营销计划安排补货";
        }
        return String.format("日均销量%.1f件，当前库存仅剩%d，建议补货%d件", avgDailySales, currentStock, qty);
    }

    private String businessNo(String prefix) {
        return prefix + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}