package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnalyticsService {

    private final JdbcTemplate jdbcTemplate;

    public AnalyticsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> stockDashboard() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalSkuCount", value(jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT sku_id) FROM sto_stock WHERE deleted=0", Long.class)));
        result.put("totalStockValue", money(jdbcTemplate.queryForObject("""
            SELECT COALESCE(SUM(s.quantity*p.purchase_price),0)
            FROM sto_stock s JOIN pro_sku k ON k.id=s.sku_id JOIN pro_product p ON p.id=k.product_id
            WHERE s.deleted=0
            """, BigDecimal.class)));
        Map<String, Object> alerts = jdbcTemplate.queryForMap("""
            SELECT COUNT(*) FILTER(WHERE available<min_alert) low_count,
                   COUNT(*) FILTER(WHERE available=0) out_count
            FROM (SELECT s.sku_id,SUM(s.quantity-s.locked_quantity) available,r.min_alert
                  FROM sto_stock s JOIN sto_stock_alert_rule r ON r.sku_id=s.sku_id AND r.enabled=TRUE
                  WHERE s.deleted=0 GROUP BY s.sku_id,r.min_alert) x
            """);
        result.put("lowStockCount", value((Number) alerts.get("low_count")));
        result.put("outOfStockCount", value((Number) alerts.get("out_count")));
        result.put("todayInboundCount", value(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sto_stock_log WHERE type=1 AND operate_time::date=CURRENT_DATE", Long.class)));
        result.put("todayOutboundCount", value(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sto_stock_log WHERE type=2 AND operate_time::date=CURRENT_DATE", Long.class)));
        result.put("topStockProducts", jdbcTemplate.query("""
            SELECT p.product_name,SUM(s.quantity)::int quantity
            FROM sto_stock s JOIN pro_sku k ON k.id=s.sku_id JOIN pro_product p ON p.id=k.product_id
            WHERE s.deleted=0 GROUP BY p.id,p.product_name ORDER BY quantity DESC LIMIT 5
            """, (rs, rowNum) -> Map.of("productName", rs.getString("product_name"), "quantity", rs.getInt("quantity"))));
        result.put("recentTrend", jdbcTemplate.query("""
            SELECT d::date trend_date,
                   COALESCE(SUM(ABS(l.quantity_change)) FILTER(WHERE l.type=1),0)::int inbound,
                   COALESCE(SUM(ABS(l.quantity_change)) FILTER(WHERE l.type=2),0)::int outbound
            FROM generate_series(CURRENT_DATE-6,CURRENT_DATE,'1 day') d
            LEFT JOIN sto_stock_log l ON l.operate_time::date=d::date
            GROUP BY d ORDER BY d
            """, (rs, rowNum) -> Map.of("date", rs.getDate("trend_date").toLocalDate(), "inbound", rs.getInt("inbound"), "outbound", rs.getInt("outbound"))));
        return result;
    }

    public Map<String, Object> salesReport(LocalDate startDate, LocalDate endDate, String granularity) {
        requireRange(startDate, endDate);
        String unit = switch (granularity == null ? "day" : granularity) {
            case "day" -> "day";
            case "week" -> "week";
            case "month" -> "month";
            default -> throw new BusinessException(ApiErrorCode.BAD_REQUEST, "统计粒度只能是day/week/month");
        };
        Object[] dates = {Date.valueOf(startDate), Date.valueOf(endDate.plusDays(1))};
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
            SELECT COALESCE(SUM(pay_amount),0) total_amount,COUNT(*) order_count
            FROM ord_order WHERE status IN(1,2,3,5) AND create_time>=? AND create_time<?
            """, dates);
        BigDecimal totalAmount = money((BigDecimal) totals.get("total_amount"));
        long orderCount = value((Number) totals.get("order_count"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalSalesAmount", totalAmount);
        result.put("totalOrderCount", orderCount);
        result.put("averageOrderAmount", orderCount == 0 ? BigDecimal.ZERO : totalAmount.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP));
        String trendSql = "SELECT date_trunc('" + unit + "',create_time)::date trend_date,SUM(pay_amount) amount,COUNT(*) count "
            + "FROM ord_order WHERE status IN(1,2,3,5) AND create_time>=? AND create_time<? GROUP BY trend_date ORDER BY trend_date";
        result.put("dailyTrend", jdbcTemplate.query(trendSql, (rs, rowNum) -> Map.of("date", rs.getDate("trend_date").toLocalDate(),
            "amount", rs.getBigDecimal("amount"), "count", rs.getLong("count")), dates));
        result.put("topProducts", jdbcTemplate.query("""
            SELECT oi.product_name,SUM(oi.subtotal) sales_amount,SUM(oi.quantity)::int sales_count
            FROM ord_order_item oi JOIN ord_order o ON o.id=oi.order_id
            WHERE o.status IN(1,2,3,5) AND o.create_time>=? AND o.create_time<?
            GROUP BY oi.product_name ORDER BY sales_amount DESC LIMIT 10
            """, (rs, rowNum) -> Map.of("productName", rs.getString("product_name"), "salesAmount", rs.getBigDecimal("sales_amount"),
            "salesCount", rs.getInt("sales_count")), dates));
        List<Map<String, Object>> categories = jdbcTemplate.query("""
            SELECT c.category_name,SUM(oi.subtotal) amount
            FROM ord_order_item oi JOIN ord_order o ON o.id=oi.order_id
            JOIN pro_sku k ON k.id=oi.sku_id JOIN pro_product p ON p.id=k.product_id JOIN pro_category c ON c.id=p.category_id
            WHERE o.status IN(1,2,3,5) AND o.create_time>=? AND o.create_time<?
            GROUP BY c.category_name ORDER BY amount DESC
            """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("categoryName", rs.getString("category_name"));
            BigDecimal amount = rs.getBigDecimal("amount");
            row.put("percentage", totalAmount.signum() == 0 ? BigDecimal.ZERO : amount.multiply(BigDecimal.valueOf(100)).divide(totalAmount, 1, RoundingMode.HALF_UP));
            return row;
        }, dates);
        result.put("categoryDistribution", categories);
        return result;
    }

    public Map<String, Object> purchaseReport(LocalDate startDate, LocalDate endDate) {
        requireRange(startDate, endDate);
        Object[] dates = {Date.valueOf(startDate), Date.valueOf(endDate.plusDays(1))};
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
            SELECT COALESCE(SUM(total_amount),0) total_amount,COUNT(*) order_count
            FROM pur_order WHERE create_time>=? AND create_time<?
            """, dates);
        BigDecimal total = money((BigDecimal) totals.get("total_amount"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalPurchaseAmount", total);
        result.put("totalOrderCount", value((Number) totals.get("order_count")));
        result.put("supplierRanking", jdbcTemplate.query("""
            SELECT s.supplier_name,SUM(o.total_amount) amount
            FROM pur_order o JOIN pur_supplier s ON s.id=o.supplier_id
            WHERE o.create_time>=? AND o.create_time<? GROUP BY s.id,s.supplier_name ORDER BY amount DESC
            """, (rs, rowNum) -> {
            BigDecimal amount = rs.getBigDecimal("amount");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("supplierName", rs.getString("supplier_name")); row.put("amount", amount);
            row.put("percentage", total.signum() == 0 ? BigDecimal.ZERO : amount.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP));
            return row;
        }, dates));
        return result;
    }

    private void requireRange(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)) throw new BusinessException(ApiErrorCode.BAD_REQUEST, "日期范围不合法");
    }
    private long value(Number number) { return number == null ? 0 : number.longValue(); }
    private BigDecimal money(BigDecimal value) { return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP); }
}
