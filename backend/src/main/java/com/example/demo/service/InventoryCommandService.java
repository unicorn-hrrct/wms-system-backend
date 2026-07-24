package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import com.example.demo.security.CurrentUserProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InventoryCommandService {

    private final JdbcTemplate jdbcTemplate;
    private final StockMutationService stockMutationService;
    private final CurrentUserProvider currentUser;

    public InventoryCommandService(JdbcTemplate jdbcTemplate,
                                   StockMutationService stockMutationService,
                                   CurrentUserProvider currentUser) {
        this.jdbcTemplate = jdbcTemplate;
        this.stockMutationService = stockMutationService;
        this.currentUser = currentUser;
    }

    public List<Map<String, Object>> alerts(Long warehouseId, String alertType) {
        StringBuilder sql = new StringBuilder("""
            SELECT s.sku_id, k.sku_code, p.product_name,
                   SUM(s.quantity)::int AS current_stock,
                   SUM(s.quantity - s.locked_quantity)::int AS available_stock,
                   COALESCE(MIN(s.min_stock), MAX(k.safety_stock), r.min_alert, 20) AS min_stock,
                   COALESCE(MIN(s.max_stock), r.max_alert, 500) AS max_stock,
                   MIN(s.expire_date) AS nearest_expire,
                   COALESCE(MAX(k.expiry_warn_days), 30) AS warn_days
            FROM sto_stock s
            JOIN pro_sku k ON k.id = s.sku_id AND k.deleted = 0
            JOIN pro_product p ON p.id = k.product_id AND p.deleted = 0
            LEFT JOIN sto_stock_alert_rule r ON r.sku_id = s.sku_id AND r.enabled = TRUE
            WHERE s.deleted = 0
            """);
        List<Object> args = new java.util.ArrayList<>();
        if (warehouseId != null) {
            sql.append(" AND s.warehouse_id = ?");
            args.add(warehouseId);
        }
        sql.append("""
            GROUP BY s.sku_id, k.sku_code, p.product_name, r.min_alert, r.max_alert
            ORDER BY available_stock
            """);
        List<Map<String, Object>> rows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("skuId", rs.getLong("sku_id"));
            row.put("skuCode", rs.getString("sku_code"));
            row.put("productName", rs.getString("product_name"));
            row.put("currentStock", rs.getInt("current_stock"));
            row.put("availableStock", rs.getInt("available_stock"));
            row.put("minStock", rs.getInt("min_stock"));
            row.put("maxStock", rs.getInt("max_stock"));
            java.sql.Date expire = rs.getDate("nearest_expire");
            row.put("nearestExpire", expire == null ? null : expire.toLocalDate());
            row.put("warnDays", rs.getInt("warn_days"));
            return row;
        }, args.toArray());

        java.util.Set<String> types = parseAlertTypes(alertType);
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Map<String, Object> base : rows) {
            int available = (Integer) base.get("availableStock");
            int min = (Integer) base.get("minStock");
            int max = (Integer) base.get("maxStock");
            LocalDate expire = (LocalDate) base.get("nearestExpire");
            int warnDays = (Integer) base.get("warnDays");
            if (types.contains("LOW_STOCK") && available < min) {
                result.add(alertRow(base, "LOW_STOCK",
                    "可用库存 " + available + " 件，低于安全水位 " + min + " 件"));
            }
            if (types.contains("OVER_STOCK") && available > max) {
                result.add(alertRow(base, "OVER_STOCK",
                    "可用库存 " + available + " 件，高于超储水位 " + max + " 件"));
            }
            if (types.contains("EXPIRING") && expire != null && !expire.isAfter(today.plusDays(warnDays))) {
                result.add(alertRow(base, "EXPIRING",
                    "最近批次将于 " + expire + " 到期，预警天数 " + warnDays));
            }
        }
        return result;
    }

    @Transactional
    public Map<String, Object> updateSafetyStock(Long skuId, Long warehouseId, Long locationId,
                                                 Integer minStock, Integer maxStock, Boolean force) {
        requireExists("SELECT COUNT(*) FROM pro_sku WHERE id = ? AND deleted = 0", skuId, "SKU不存在");
        requireWarehouse(warehouseId);
        if (minStock == null || minStock < 0) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "minStock 必须 ≥ 0");
        }
        if (maxStock != null && maxStock < minStock) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "maxStock 不能小于 minStock");
        }
        StringBuilder sql = new StringBuilder("""
            SELECT id, quantity, locked_quantity FROM sto_stock
            WHERE sku_id = ? AND warehouse_id = ? AND deleted = 0
            """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(skuId);
        args.add(warehouseId);
        if (locationId != null) {
            sql.append(" AND location_id = ?");
            args.add(locationId);
        }
        List<Map<String, Object>> stocks = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("available", rs.getInt("quantity") - rs.getInt("locked_quantity"));
            return row;
        }, args.toArray());
        if (stocks.isEmpty()) {
            if (locationId == null) {
                throw new BusinessException(ApiErrorCode.NOT_FOUND, "该仓库下没有可配置的库存记录");
            }
            Long id = jdbcTemplate.queryForObject("""
                INSERT INTO sto_stock(sku_id, warehouse_id, location_id, quantity, locked_quantity, min_stock, max_stock)
                VALUES (?, ?, ?, 0, 0, ?, ?) RETURNING id
                """, Long.class, skuId, warehouseId, locationId, minStock, maxStock);
            Map<String, Object> created = new LinkedHashMap<>();
            created.put("skuId", skuId);
            created.put("warehouseId", warehouseId);
            created.put("locationId", locationId);
            created.put("minStock", minStock);
            created.put("maxStock", maxStock);
            created.put("stockId", id);
            return created;
        }
        boolean exceeded = stocks.stream().anyMatch(s -> ((Integer) s.get("available")) > 0
            && minStock > (Integer) s.get("available"));
        if (exceeded && !Boolean.TRUE.equals(force)) {
            throw new BusinessException(ApiErrorCode.SAFETY_STOCK_EXCEEDED);
        }
        for (Map<String, Object> stock : stocks) {
            jdbcTemplate.update("""
                UPDATE sto_stock SET min_stock = ?, max_stock = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?
                """, minStock, maxStock, stock.get("id"));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("skuId", skuId);
        response.put("warehouseId", warehouseId);
        response.put("locationId", locationId);
        response.put("minStock", minStock);
        response.put("maxStock", maxStock);
        response.put("updatedCount", stocks.size());
        response.put("force", Boolean.TRUE.equals(force));
        if (exceeded) {
            response.put("tipCode", ApiErrorCode.SAFETY_STOCK_EXCEEDED.getCode());
            response.put("tipMessage", ApiErrorCode.SAFETY_STOCK_EXCEEDED.getMessage());
        }
        return response;
    }

    @Transactional
    public Map<String, Object> deleteLocation(Long locationId, Boolean force, Boolean cascade) {
        List<Map<String, Object>> locations = jdbcTemplate.query("""
            SELECT id, warehouse_id, location_name, deleted FROM sto_location WHERE id = ?
            """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("warehouseId", rs.getLong("warehouse_id"));
            row.put("name", rs.getString("location_name"));
            row.put("deleted", rs.getInt("deleted"));
            return row;
        }, locationId);
        if (locations.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "库位不存在");
        }
        if ((Integer) locations.getFirst().get("deleted") == 1) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "库位已删除");
        }
        Long warehouseId = (Long) locations.getFirst().get("warehouseId");
        Integer childCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sto_location WHERE parent_id = ? AND deleted = 0", Integer.class, locationId);
        if (childCount != null && childCount > 0) {
            if (!Boolean.TRUE.equals(cascade)) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "存在子节点，请先删除子节点或传 cascade=true");
            }
            List<Long> children = jdbcTemplate.query(
                "SELECT id FROM sto_location WHERE parent_id = ? AND deleted = 0",
                (rs, rowNum) -> rs.getLong(1), locationId);
            for (Long childId : children) {
                deleteLocation(childId, force, true);
            }
        }

        List<Map<String, Object>> stocks = jdbcTemplate.query("""
            SELECT id, sku_id, quantity, locked_quantity FROM sto_stock
            WHERE location_id = ? AND deleted = 0 AND (quantity > 0 OR locked_quantity > 0)
            """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stockId", rs.getLong("id"));
            row.put("skuId", rs.getLong("sku_id"));
            row.put("quantity", rs.getInt("quantity"));
            row.put("lockedQuantity", rs.getInt("locked_quantity"));
            return row;
        }, locationId);

        int transferred = 0;
        if (!stocks.isEmpty()) {
            if (!Boolean.TRUE.equals(force)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("stocks", stocks);
                throw new BusinessException(ApiErrorCode.LOCATION_HAS_STOCK,
                    ApiErrorCode.LOCATION_HAS_STOCK.getMessage() + "：" + stocks);
            }
            Long scrapId = ensureScrapLocation(warehouseId);
            int idx = 0;
            for (Map<String, Object> stock : stocks) {
                Long skuId = (Long) stock.get("skuId");
                int qty = (Integer) stock.get("quantity");
                int locked = (Integer) stock.get("lockedQuantity");
                if (qty > 0) {
                    stockMutationService.adjust(skuId, warehouseId, locationId, -qty, -locked, 4,
                        "LOC-DEL", "loc-del-out:" + locationId + ":" + idx, currentUser.requireUsername(),
                        null, "库位强制删除转出");
                    stockMutationService.adjust(skuId, warehouseId, scrapId, qty, locked, 4,
                        "LOC-DEL", "loc-del-in:" + locationId + ":" + idx, currentUser.requireUsername(),
                        null, "库位强制删除转入废品库位");
                    transferred += qty;
                }
                idx++;
            }
        }

        boolean hardDelete = Boolean.TRUE.equals(force);
        if (hardDelete) {
            jdbcTemplate.update("UPDATE sto_stock SET location_id = NULL WHERE location_id = ?", locationId);
            jdbcTemplate.update("DELETE FROM sto_location WHERE id = ?", locationId);
        } else {
            jdbcTemplate.update("""
                UPDATE sto_location SET deleted = 1, status = 1, update_time = CURRENT_TIMESTAMP WHERE id = ?
                """, locationId);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("locationId", locationId);
        response.put("deletedAt", java.time.LocalDateTime.now());
        response.put("softDeleted", !hardDelete);
        response.put("stockTransferred", transferred);
        if (Boolean.TRUE.equals(force) && transferred > 0) {
            response.put("tipCode", ApiErrorCode.FORCE_DELETE_LOCATION_OK.getCode());
            response.put("tipMessage", ApiErrorCode.FORCE_DELETE_LOCATION_OK.getMessage());
        }
        return response;
    }

    private Long ensureScrapLocation(Long warehouseId) {
        Long id = jdbcTemplate.query("""
            SELECT id FROM sto_location
            WHERE warehouse_id = ? AND location_code = 'SCRAP' AND deleted = 0
            LIMIT 1
            """, rs -> rs.next() ? rs.getLong(1) : null, warehouseId);
        if (id != null) {
            return id;
        }
        return jdbcTemplate.queryForObject("""
            INSERT INTO sto_location(warehouse_id, parent_id, location_type, location_code, location_name, sort_order)
            VALUES (?, 0, 3, 'SCRAP', '废品库位', 9999) RETURNING id
            """, Long.class, warehouseId);
    }

    private java.util.Set<String> parseAlertTypes(String alertType) {
        if (alertType == null || alertType.isBlank()) {
            return java.util.Set.of("LOW_STOCK", "OVER_STOCK", "EXPIRING");
        }
        java.util.Set<String> types = new java.util.LinkedHashSet<>();
        for (String part : alertType.split(",")) {
            String t = part.trim().toUpperCase();
            if (!t.isEmpty()) {
                types.add(t);
            }
        }
        return types.isEmpty() ? java.util.Set.of("LOW_STOCK", "OVER_STOCK", "EXPIRING") : types;
    }

    private Map<String, Object> alertRow(Map<String, Object> base, String type, String message) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("skuId", base.get("skuId"));
        row.put("skuCode", base.get("skuCode"));
        row.put("productName", base.get("productName"));
        row.put("currentStock", base.get("currentStock"));
        row.put("availableStock", base.get("availableStock"));
        row.put("minStock", base.get("minStock"));
        row.put("maxStock", base.get("maxStock"));
        row.put("alertType", type);
        row.put("alertMessage", message);
        return row;
    }

    private void requireExists(String sql, Object id, String message) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, id);
        if (count == null || count == 0) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, message);
        }
    }

    @Transactional
    public Map<String, Object> createCheck(Long warehouseId, Integer type, List<CheckItem> requestedItems) {
        requireWarehouse(warehouseId);
        if (type == null || (type != 1 && type != 2)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "盘点类型只能是1或2");
        }
        String checkNo = businessNo("CK");
        Long checkId = jdbcTemplate.queryForObject("""
            INSERT INTO sto_stock_check(check_no, warehouse_id, type, operator_id)
            VALUES (?, ?, ?, ?) RETURNING id
            """, Long.class, checkNo, warehouseId, type, currentUser.requireUserId());

        List<CheckItem> items;
        if (type == 1) {
            items = jdbcTemplate.query("""
                SELECT sku_id, SUM(quantity)::int system_qty FROM sto_stock
                WHERE warehouse_id = ? AND deleted = 0 GROUP BY sku_id ORDER BY sku_id
                """, (rs, rowNum) -> new CheckItem(rs.getLong("sku_id"), rs.getInt("system_qty")), warehouseId);
        } else {
            if (requestedItems == null || requestedItems.isEmpty()) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "抽盘必须提供盘点明细");
            }
            items = requestedItems;
            long distinctSkuCount = items.stream().map(CheckItem::skuId).distinct().count();
            if (distinctSkuCount != items.size()) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "同一SKU不能重复出现在盘点明细中");
            }
        }
        for (CheckItem item : items) {
            Integer actualSystemQty = jdbcTemplate.query("""
                SELECT SUM(quantity)::int FROM sto_stock
                WHERE sku_id = ? AND warehouse_id = ? AND deleted = 0
                """, rs -> rs.next() ? (Integer) rs.getObject(1) : null, item.skuId(), warehouseId);
            if (actualSystemQty == null) {
                throw new BusinessException(ApiErrorCode.NOT_FOUND, "盘点库存不存在，SKU=" + item.skuId());
            }
            if (item.systemQty() != null && !item.systemQty().equals(actualSystemQty)) {
                throw new BusinessException(ApiErrorCode.STOCK_DEDUCT_CONFLICT, "盘点期间系统库存已变化，SKU=" + item.skuId());
            }
            jdbcTemplate.update("""
                INSERT INTO sto_stock_check_item(check_id, sku_id, location_id, system_qty)
                VALUES (?, ?, NULL, ?)
                """, checkId, item.skuId(), actualSystemQty);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkId", checkId);
        result.put("checkNo", checkNo);
        result.put("status", 0);
        result.put("itemCount", items.size());
        return result;
    }

    @Transactional
    public Map<String, Object> submitCheck(Long checkId, List<CheckResult> results) {
        CheckHeader header = requireOpenCheck(checkId);
        if (results == null || results.isEmpty()) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "盘点结果不能为空");
        }
        for (CheckResult result : results) {
            List<CheckStoredItem> items = jdbcTemplate.query("""
                SELECT id, system_qty FROM sto_stock_check_item
                WHERE check_id = ? AND sku_id = ?
                ORDER BY id LIMIT 1
                """, (rs, rowNum) -> new CheckStoredItem(rs.getLong("id"), rs.getInt("system_qty")),
                checkId, result.skuId());
            if (items.isEmpty()) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "SKU不在本次盘点范围：" + result.skuId());
            }
            CheckStoredItem item = items.getFirst();
            int diff = result.actualQty() - item.systemQty();
            if (result.diffQty() != null && result.diffQty() != diff) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "盘点差异数量与系统计算不一致");
            }
            Long locationId;
            if (diff < 0) {
                locationId = stockMutationService.requireAllocation(result.skuId(), header.warehouseId(), -diff).locationId();
            } else {
                locationId = jdbcTemplate.query("""
                    SELECT location_id FROM sto_stock
                    WHERE sku_id = ? AND warehouse_id = ? AND deleted = 0
                    ORDER BY id LIMIT 1
                    """, rs -> rs.next() ? rs.getLong(1) : null, result.skuId(), header.warehouseId());
            }
            if (locationId == null) {
                throw new BusinessException(ApiErrorCode.NOT_FOUND, "盘点库存库位不存在");
            }
            stockMutationService.adjust(result.skuId(), header.warehouseId(), locationId, diff, 0, 3,
                header.checkNo(), "check:" + checkId + ":" + result.skuId(), currentUser.requireUsername(), null, result.reason());
            jdbcTemplate.update("""
                UPDATE sto_stock_check_item SET actual_qty = ?, diff_qty = ?, reason = ? WHERE id = ?
                """, result.actualQty(), diff, result.reason(), item.id());
        }
        jdbcTemplate.update("UPDATE sto_stock_check SET status = 1, submit_time = CURRENT_TIMESTAMP WHERE id = ?", checkId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("checkId", checkId);
        response.put("checkNo", header.checkNo());
        response.put("status", 1);
        return response;
    }

    @Transactional
    public Map<String, Object> transfer(Long fromWarehouseId, Long toWarehouseId,
                                        List<TransferItem> items, String remark) {
        requireWarehouse(fromWarehouseId);
        requireWarehouse(toWarehouseId);
        if (fromWarehouseId.equals(toWarehouseId)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "调出仓库和调入仓库不能相同");
        }
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "调拨明细不能为空");
        }
        String transferNo = businessNo("TR");
        Long transferId = jdbcTemplate.queryForObject("""
            INSERT INTO sto_stock_transfer(transfer_no, from_warehouse_id, to_warehouse_id, remark, operator_id)
            VALUES (?, ?, ?, ?, ?) RETURNING id
            """, Long.class, transferNo, fromWarehouseId, toWarehouseId, remark, currentUser.requireUserId());
        int index = 0;
        for (TransferItem item : items) {
            requireLocation(item.fromLocationId(), fromWarehouseId);
            requireLocation(item.toLocationId(), toWarehouseId);
            if (item.quantity() == null || item.quantity() < 1) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "调拨数量必须大于0");
            }
            jdbcTemplate.update("""
                INSERT INTO sto_stock_transfer_item(transfer_id, sku_id, quantity, from_location_id, to_location_id)
                VALUES (?, ?, ?, ?, ?)
                """, transferId, item.skuId(), item.quantity(), item.fromLocationId(), item.toLocationId());
            String prefix = "transfer:" + transferId + ":" + index++;
            stockMutationService.adjust(item.skuId(), fromWarehouseId, item.fromLocationId(), -item.quantity(), 0, 4,
                transferNo, prefix + ":out", currentUser.requireUsername(), null, remark);
            stockMutationService.adjust(item.skuId(), toWarehouseId, item.toLocationId(), item.quantity(), 0, 4,
                transferNo, prefix + ":in", currentUser.requireUsername(), null, remark);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("transferId", transferId);
        response.put("transferNo", transferNo);
        response.put("status", 1);
        return response;
    }

    private CheckHeader requireOpenCheck(Long checkId) {
        List<CheckHeader> rows = jdbcTemplate.query("SELECT check_no, warehouse_id, status FROM sto_stock_check WHERE id = ?",
            (rs, rowNum) -> new CheckHeader(rs.getString("check_no"), rs.getLong("warehouse_id"), rs.getInt("status")), checkId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "盘点单不存在");
        }
        if (rows.getFirst().status() != 0) {
            throw new BusinessException(ApiErrorCode.ORDER_STATUS_INVALID, "盘点单已提交");
        }
        return rows.getFirst();
    }

    private void requireWarehouse(Long warehouseId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sto_warehouse WHERE id = ? AND status = 0", Integer.class, warehouseId);
        if (count == null || count == 0) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "仓库不存在");
        }
    }

    private void requireLocation(Long locationId, Long warehouseId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sto_location WHERE id = ? AND warehouse_id = ? AND status = 0 AND deleted = 0",
            Integer.class, locationId, warehouseId);
        if (count == null || count == 0) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "库位不属于指定仓库");
        }
    }

    private String businessNo(String prefix) {
        return prefix + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    public record CheckItem(Long skuId, Integer systemQty) {
    }

    public record CheckResult(Long skuId, Integer actualQty, Integer diffQty, String reason) {
    }

    public record TransferItem(Long skuId, Integer quantity, Long fromLocationId, Long toLocationId) {
    }

    private record CheckHeader(String checkNo, Long warehouseId, int status) {
    }

    private record CheckStoredItem(Long id, int systemQty) {
    }
}
