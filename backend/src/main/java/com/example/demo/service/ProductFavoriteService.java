package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProductFavoriteService {

    private final JdbcTemplate jdbcTemplate;
    private final CustomerService customerService;

    public ProductFavoriteService(JdbcTemplate jdbcTemplate, CustomerService customerService) {
        this.jdbcTemplate = jdbcTemplate;
        this.customerService = customerService;
    }

    public Map<String, Object> list(int pageNum, int pageSize) {
        Long customerId = currentCustomerId();
        Long total = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM crm_product_favorite f
            JOIN pro_product p ON p.id=f.product_id
            WHERE f.customer_id=? AND p.deleted=0
            """, Long.class, customerId);
        List<Map<String, Object>> list = jdbcTemplate.query("""
            SELECT f.id favorite_id, f.product_id, p.product_code, p.product_name, p.category_id,
                   c.category_name, p.main_image, p.unit, p.sale_price, p.status, f.create_time favorite_time
            FROM crm_product_favorite f
            JOIN pro_product p ON p.id=f.product_id
            LEFT JOIN pro_category c ON c.id=p.category_id
            WHERE f.customer_id=? AND p.deleted=0
            ORDER BY f.create_time DESC, f.id DESC
            LIMIT ? OFFSET ?
            """, this::favoriteRow, customerId, pageSize, (pageNum - 1) * pageSize);
        return page(total == null ? 0 : total, pageNum, pageSize, list);
    }

    @Transactional
    public Map<String, Object> add(Long productId) {
        requireActiveProduct(productId);
        Long customerId = currentCustomerId();
        jdbcTemplate.update("""
            INSERT INTO crm_product_favorite(customer_id, product_id)
            VALUES (?, ?)
            ON CONFLICT(customer_id, product_id) DO NOTHING
            """, customerId, productId);
        return favoriteDetail(customerId, productId);
    }

    public void delete(Long productId) {
        Long customerId = currentCustomerId();
        jdbcTemplate.update("DELETE FROM crm_product_favorite WHERE customer_id=? AND product_id=?", customerId, productId);
    }

    public Map<String, Object> check(Long productId) {
        requireProduct(productId);
        Long customerId = currentCustomerId();
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crm_product_favorite WHERE customer_id=? AND product_id=?",
            Integer.class, customerId, productId);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productId", productId);
        row.put("favorited", count != null && count > 0);
        return row;
    }

    private Map<String, Object> favoriteDetail(Long customerId, Long productId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
            SELECT f.id favorite_id, f.product_id, p.product_code, p.product_name, p.category_id,
                   c.category_name, p.main_image, p.unit, p.sale_price, p.status, f.create_time favorite_time
            FROM crm_product_favorite f
            JOIN pro_product p ON p.id=f.product_id
            LEFT JOIN pro_category c ON c.id=p.category_id
            WHERE f.customer_id=? AND f.product_id=? AND p.deleted=0
            """, this::favoriteRow, customerId, productId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "favorite not found");
        }
        return rows.getFirst();
    }

    private void requireProduct(Long productId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pro_product WHERE id=? AND deleted=0", Integer.class, productId);
        if (count == null || count == 0) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "product not found");
        }
    }

    private void requireActiveProduct(Long productId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pro_product WHERE id=? AND deleted=0 AND status=0", Integer.class, productId);
        if (count == null || count == 0) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "active product not found");
        }
    }

    private Long currentCustomerId() {
        return customerService.getOrCreateCurrent().customerId();
    }

    private Map<String, Object> favoriteRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("favoriteId", rs.getLong("favorite_id"));
        row.put("productId", rs.getLong("product_id"));
        row.put("productCode", rs.getString("product_code"));
        row.put("productName", rs.getString("product_name"));
        row.put("categoryId", rs.getLong("category_id"));
        row.put("categoryName", rs.getString("category_name"));
        row.put("mainImage", rs.getString("main_image"));
        row.put("unit", rs.getString("unit"));
        row.put("salePrice", rs.getBigDecimal("sale_price"));
        row.put("status", rs.getInt("status"));
        row.put("favoriteTime", rs.getTimestamp("favorite_time").toLocalDateTime());
        return row;
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
