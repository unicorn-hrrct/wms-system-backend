package com.example.demo.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 10.3 数据大屏（ECharts 实时数据）：聚合 KPI、销售趋势、品类占比、仓库柱状、热门商品、实时订单、预警。
 */
@Service
public class DashboardScreenService {

    private final JdbcTemplate jdbcTemplate;

    public DashboardScreenService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> aggregate() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kpiCards", kpiCards());
        data.put("salesTrend", salesTrend());
        data.put("categoryPie", categoryPie());
        data.put("warehouseBar", warehouseBar());
        data.put("hotProducts", hotProducts());
        data.put("realtimeOrders", realtimeOrders());
        data.put("alertList", alertList());
        return data;
    }

    private List<Map<String, Object>> kpiCards() {
        BigDecimal todaySales = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(pay_amount),0) FROM ord_order WHERE status IN(1,2,3,5) AND create_time::date=CURRENT_DATE",
            BigDecimal.class);
        Long orderCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ord_order WHERE create_time::date=CURRENT_DATE", Long.class);
        Long inboundCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sto_stock_log WHERE type=1 AND operate_time::date=CURRENT_DATE", Long.class);
        BigDecimal stockValue = jdbcTemplate.queryForObject("""
            SELECT COALESCE(SUM(s.quantity*p.purchase_price),0)
            FROM sto_stock s JOIN pro_sku k ON k.id=s.sku_id JOIN pro_product p ON p.id=k.product_id
            WHERE s.deleted=0
            """, BigDecimal.class);

        List<Map<String, Object>> cards = new ArrayList<>();
        cards.add(card("今日销售额", todaySales == null ? BigDecimal.ZERO : todaySales, "元"));
        cards.add(card("今日订单量", BigDecimal.valueOf(orderCount == null ? 0 : orderCount), "单"));
        cards.add(card("今日入库量", BigDecimal.valueOf(inboundCount == null ? 0 : inboundCount), "件"));
        cards.add(card("当前库存总值", stockValue == null ? BigDecimal.ZERO : stockValue, "元"));
        return cards;
    }

    private Map<String, Object> salesTrend() {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(6);
        List<Map<String, Object>> raw = jdbcTemplate.query("""
            SELECT d::date trend_date,
                   COALESCE(SUM(pay_amount) FILTER(WHERE o.status IN(1,2,3,5)),0) amount,
                   COUNT(*) FILTER(WHERE o.status IN(1,2,3,5)) count
            FROM generate_series(?::date, ?::date, '1 day') d
            LEFT JOIN ord_order o ON o.create_time::date=d::date
            GROUP BY d ORDER BY d
            """, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", rs.getDate("trend_date").toLocalDate().toString().substring(5));
                row.put("amount", rs.getBigDecimal("amount"));
                row.put("count", rs.getLong("count"));
                return row;
            }, Date.valueOf(start), Date.valueOf(today));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dates", raw.stream().map(r -> r.get("date")).toList());
        result.put("amounts", raw.stream().map(r -> r.get("amount")).toList());
        result.put("counts", raw.stream().map(r -> r.get("count")).toList());
        return result;
    }

    private List<Map<String, Object>> categoryPie() {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
            SELECT c.category_name, COALESCE(SUM(oi.subtotal),0) amount
            FROM ord_order_item oi JOIN ord_order o ON o.id=oi.order_id
            JOIN pro_sku k ON k.id=oi.sku_id JOIN pro_product p ON p.id=k.product_id
            JOIN pro_category c ON c.id=p.category_id
            WHERE o.status IN(1,2,3,5) AND o.create_time >= CURRENT_DATE - INTERVAL '30 days'
            GROUP BY c.category_name
            ORDER BY amount DESC
            """, (rs, rowNum) -> Map.of("name", rs.getString(1), "amount", rs.getBigDecimal(2)));
        BigDecimal total = rows.stream()
            .map(r -> ((BigDecimal) r.get("amount")))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            BigDecimal amount = (BigDecimal) row.get("amount");
            BigDecimal percentage = total.signum() == 0
                ? BigDecimal.ZERO
                : amount.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.get("name"));
            item.put("value", percentage);
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> warehouseBar() {
        return jdbcTemplate.query("""
            SELECT w.warehouse_name,
                   COALESCE(SUM(l.quantity_change) FILTER(WHERE l.type=1),0)::int inbound,
                   COALESCE(SUM(ABS(l.quantity_change)) FILTER(WHERE l.type=2),0)::int outbound,
                   COALESCE((SELECT SUM(quantity) FROM sto_stock s WHERE s.warehouse_id=w.id AND s.deleted=0),0)::int stock
            FROM sto_warehouse w
            LEFT JOIN sto_stock_log l ON l.warehouse_id=w.id
            WHERE w.status=0
            GROUP BY w.id,w.warehouse_name ORDER BY w.id
            """, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", rs.getString("warehouse_name"));
                row.put("inbound", rs.getInt("inbound"));
                row.put("outbound", rs.getInt("outbound"));
                row.put("stock", rs.getInt("stock"));
                return row;
            });
    }

    private List<Map<String, Object>> hotProducts() {
        return jdbcTemplate.query("""
            SELECT oi.product_name,
                   SUM(oi.quantity)::int sales,
                   SUM(oi.subtotal) amount
            FROM ord_order_item oi JOIN ord_order o ON o.id=oi.order_id
            WHERE o.status IN(1,2,3,5) AND o.create_time >= CURRENT_DATE - INTERVAL '7 days'
            GROUP BY oi.product_name ORDER BY sales DESC LIMIT 10
            """, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", rs.getString("product_name"));
                row.put("sales", rs.getInt("sales"));
                row.put("amount", rs.getBigDecimal("amount"));
                return row;
            });
    }

    private List<Map<String, Object>> realtimeOrders() {
        return jdbcTemplate.query("""
            SELECT order_no, pay_amount, create_time, address_snapshot
            FROM ord_order WHERE status>=1 ORDER BY create_time DESC LIMIT 10
            """, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("orderNo", rs.getString("order_no"));
                row.put("amount", rs.getBigDecimal("pay_amount"));
                row.put("time", rs.getTimestamp("create_time").toLocalDateTime().toLocalTime().toString().substring(0, 8));
                String snapshot = rs.getString("address_snapshot");
                String name = "用户";
                if (snapshot != null && snapshot.contains("receiverName")) {
                    int start = snapshot.indexOf("receiverName") + 14;
                    int end = snapshot.indexOf("\"", start);
                    if (end > start) {
                        name = snapshot.substring(start, end);
                        if (name.length() > 1) {
                            name = name.charAt(0) + "*";
                        }
                    }
                }
                row.put("customer", name);
                return row;
            });
    }

    private List<Map<String, Object>> alertList() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> lowStock = jdbcTemplate.query("""
            SELECT k.sku_code, p.product_name, k.spec_values,
                   COALESCE(SUM(s.quantity-s.locked_quantity),0)::int available,
                   COALESCE(MIN(s.min_stock), MAX(k.safety_stock), 20) min_stock
            FROM sto_stock s
            JOIN pro_sku k ON k.id=s.sku_id
            JOIN pro_product p ON p.id=k.product_id
            WHERE s.deleted=0
            GROUP BY k.id,k.sku_code,p.product_name,k.spec_values
            HAVING COALESCE(SUM(s.quantity-s.locked_quantity),0) < COALESCE(MIN(s.min_stock), MAX(k.safety_stock), 20)
            ORDER BY available LIMIT 5
            """, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("level", "high");
                row.put("message", rs.getString("product_name") + " (SKU:" + rs.getString("sku_code") + ") 库存仅剩"
                    + rs.getInt("available") + "件！");
                return row;
            });
        result.addAll(lowStock);
        List<Map<String, Object>> capacity = jdbcTemplate.query("""
            SELECT warehouse_name, used_capacity, capacity FROM sto_warehouse
            WHERE status=0 AND capacity>0 AND used_capacity*1.0/capacity >= 0.8
            """, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("level", "medium");
                int pct = rs.getInt("capacity") == 0 ? 0
                    : (int) Math.round(rs.getInt("used_capacity") * 100.0 / rs.getInt("capacity"));
                row.put("message", rs.getString("warehouse_name") + " 容量使用率达" + pct + "%");
                return row;
            });
        result.addAll(capacity);
        return result;
    }

    private Map<String, Object> card(String title, BigDecimal value, String unit) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", title);
        card.put("value", value);
        card.put("unit", unit);
        card.put("change", "+0.0%");
        card.put("trend", "up");
        return card;
    }
}