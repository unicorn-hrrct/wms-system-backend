package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BrowseHistoryService {

    private final JdbcTemplate jdbcTemplate;
    private final CustomerService customerService;

    public BrowseHistoryService(JdbcTemplate jdbcTemplate, CustomerService customerService) {
        this.jdbcTemplate = jdbcTemplate;
        this.customerService = customerService;
    }

    public Map<String, Object> list(int pageNum, int pageSize) {
        Long customerId = currentCustomerId();
        Long total = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM crm_browse_history h
            JOIN pro_product p ON p.id=h.product_id
            WHERE h.customer_id=? AND p.deleted=0
            """, Long.class, customerId);
        List<Map<String, Object>> list = jdbcTemplate.query("""
            SELECT h.id history_id, h.product_id, h.sku_id, COALESCE(p.product_code, '') product_code,
                   h.product_name, h.main_image, p.category_id, c.category_name, p.sale_price,
                   p.status, h.view_count, h.last_view_time, h.create_time
            FROM crm_browse_history h
            JOIN pro_product p ON p.id=h.product_id
            LEFT JOIN pro_category c ON c.id=p.category_id
            WHERE h.customer_id=? AND p.deleted=0
            ORDER BY h.last_view_time DESC, h.id DESC
            LIMIT ? OFFSET ?
            """, this::historyRow, customerId, pageSize, (pageNum - 1) * pageSize);
        return page(total == null ? 0 : total, pageNum, pageSize, list);
    }

    @Transactional
    public Map<String, Object> record(Long productId, Long skuId) {
        Map<String, Object> product = requireActiveProduct(productId);
        if (skuId != null) {
            requireActiveSku(productId, skuId);
        }
        Long customerId = currentCustomerId();
        jdbcTemplate.update("""
            INSERT INTO crm_browse_history(customer_id, product_id, sku_id, product_name, main_image)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(customer_id, product_id) DO UPDATE SET
                sku_id=EXCLUDED.sku_id,
                product_name=EXCLUDED.product_name,
                main_image=EXCLUDED.main_image,
                view_count=crm_browse_history.view_count + 1,
                last_view_time=CURRENT_TIMESTAMP,
                update_time=CURRENT_TIMESTAMP
            """, customerId, productId, skuId, product.get("productName"), product.get("mainImage"));
        return detail(customerId, productId);
    }

    public void delete(Long productId) {
        jdbcTemplate.update("DELETE FROM crm_browse_history WHERE customer_id=? AND product_id=?", currentCustomerId(), productId);
    }

    public void clear() {
        jdbcTemplate.update("DELETE FROM crm_browse_history WHERE customer_id=?", currentCustomerId());
    }

    private Map<String, Object> detail(Long customerId, Long productId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
            SELECT h.id history_id, h.product_id, h.sku_id, COALESCE(p.product_code, '') product_code,
                   h.product_name, h.main_image, p.category_id, c.category_name, p.sale_price,
                   p.status, h.view_count, h.last_view_time, h.create_time
            FROM crm_browse_history h
            JOIN pro_product p ON p.id=h.product_id
            LEFT JOIN pro_category c ON c.id=p.category_id
            WHERE h.customer_id=? AND h.product_id=? AND p.deleted=0
            """, this::historyRow, customerId, productId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "browse history not found");
        }
        return rows.getFirst();
    }

    private Map<String, Object> requireActiveProduct(Long productId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
            SELECT id, product_name, main_image
            FROM pro_product
            WHERE id=? AND deleted=0 AND status=0
            """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", rs.getLong("id"));
            row.put("productName", rs.getString("product_name"));
            row.put("mainImage", rs.getString("main_image"));
            return row;
        }, productId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "active product not found");
        }
        return rows.getFirst();
    }

    private void requireActiveSku(Long productId, Long skuId) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM pro_sku
            WHERE id=? AND product_id=? AND deleted=0 AND status=0
            """, Integer.class, skuId, productId);
        if (count == null || count == 0) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "active sku not found");
        }
    }

    private Long currentCustomerId() {
        return customerService.getOrCreateCurrent().customerId();
    }

    private Map<String, Object> historyRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("historyId", rs.getLong("history_id"));
        row.put("productId", rs.getLong("product_id"));
        long skuId = rs.getLong("sku_id");
        row.put("skuId", rs.wasNull() ? null : skuId);
        row.put("productCode", rs.getString("product_code"));
        row.put("productName", rs.getString("product_name"));
        row.put("mainImage", rs.getString("main_image"));
        row.put("categoryId", rs.getLong("category_id"));
        row.put("categoryName", rs.getString("category_name"));
        row.put("salePrice", rs.getBigDecimal("sale_price"));
        row.put("status", rs.getInt("status"));
        row.put("viewCount", rs.getInt("view_count"));
        row.put("lastViewTime", ts(rs, "last_view_time"));
        row.put("createTime", ts(rs, "create_time"));
        return row;
    }

    private Object ts(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private Map<String, Object> page(long total, int pageNum, int pageSize, List<?> list) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("total", total);
        row.put("pageNum", pageNum);
        row.put("pageSize", pageSize);
        row.put("pages", (total + pageSize - 1) / pageSize);
        row.put("list", list);
        return row;
    }
}
