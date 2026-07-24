package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 10.5 多仓库 / 多货主：货主列表 / 按货主查询库存 / 跨货主隔离校验。
 */
@Service
public class OwnerService {

    private final JdbcTemplate jdbcTemplate;

    public OwnerService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 10.5.1 货主列表 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> owners = jdbcTemplate.query(
            "SELECT id, owner_code, owner_name, contact, phone, status FROM own_owner ORDER BY id",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ownerId", rs.getLong("id"));
                row.put("ownerCode", rs.getString("owner_code"));
                row.put("ownerName", rs.getString("owner_name"));
                row.put("contact", rs.getString("contact"));
                row.put("phone", rs.getString("phone"));
                row.put("status", rs.getInt("status"));
                return row;
            });
        for (Map<String, Object> owner : owners) {
            Long ownerId = ((Number) owner.get("ownerId")).longValue();
            List<Long> warehouseIds = jdbcTemplate.query(
                "SELECT warehouse_id FROM sto_owner_warehouse WHERE owner_id = ? ORDER BY warehouse_id",
                (rs, rowNum) -> rs.getLong(1), ownerId);
            owner.put("warehouseIds", warehouseIds);
        }
        return owners;
    }

    /** 10.5.2 按货主查询库存 */
    public Map<String, Object> stockByOwner(Long ownerId, Long warehouseId) {
        requireOwner(ownerId);
        StringBuilder sql = new StringBuilder("""
            SELECT k.id sku_id, k.sku_code, p.product_name, w.warehouse_name,
                   s.quantity, s.locked_quantity, s.batch_no
            FROM sto_stock s
            JOIN pro_sku k ON k.id = s.sku_id AND k.deleted = 0
            JOIN pro_product p ON p.id = k.product_id AND p.deleted = 0
            JOIN sto_warehouse w ON w.id = s.warehouse_id
            JOIN sto_owner_warehouse ow ON ow.warehouse_id = w.id AND ow.owner_id = ?
            WHERE s.deleted = 0
            """);
        List<Object> args = new ArrayList<>();
        args.add(ownerId);
        if (warehouseId != null) {
            sql.append(" AND s.warehouse_id = ?");
            args.add(warehouseId);
        }
        sql.append(" ORDER BY s.sku_id, s.warehouse_id");
        List<Map<String, Object>> list = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("skuId", rs.getLong("sku_id"));
            row.put("skuCode", rs.getString("sku_code"));
            row.put("productName", rs.getString("product_name"));
            row.put("warehouseName", rs.getString("warehouse_name"));
            row.put("quantity", rs.getInt("quantity"));
            row.put("lockedQuantity", rs.getInt("locked_quantity"));
            row.put("availableStock", rs.getInt("quantity") - rs.getInt("locked_quantity"));
            row.put("batchNo", rs.getString("batch_no"));
            return row;
        }, args.toArray());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ownerId", ownerId);
        response.put("total", list.size());
        response.put("list", list);
        return response;
    }

    /** 10.5.3 跨货主库存隔离校验 */
    public Map<String, Object> isolationCheck(Long ownerId, Long warehouseId, Long skuId, String action) {
        if (!"INBOUND".equalsIgnoreCase(action) && !"OUTBOUND".equalsIgnoreCase(action)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "action 仅支持 INBOUND/OUTBOUND");
        }
        requireOwner(ownerId);
        Integer ownerCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sto_owner_warehouse WHERE owner_id = ? AND warehouse_id = ?",
            Integer.class, ownerId, warehouseId);
        boolean allowed = ownerCount != null && ownerCount > 0;

        Integer skuCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pro_sku WHERE id = ? AND deleted = 0",
            Integer.class, skuId);
        boolean skuExists = skuCount != null && skuCount > 0;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ownerId", ownerId);
        response.put("warehouseId", warehouseId);
        response.put("skuId", skuId);
        response.put("action", action.toUpperCase());
        response.put("allowed", allowed && skuExists);
        response.put("reason", !skuExists ? "SKU不存在"
            : (!allowed ? "该货主无权操作此仓库" : "校验通过"));
        return response;
    }

    private void requireOwner(Long ownerId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM own_owner WHERE id = ? AND status = 0", Integer.class, ownerId);
        if (count == null || count == 0) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "货主不存在或已停用");
        }
    }
}