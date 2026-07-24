package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import com.example.demo.security.CurrentUserProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProcurementService {

    private final JdbcTemplate jdbcTemplate;
    private final StockMutationService stockMutationService;
    private final CurrentUserProvider currentUser;

    public ProcurementService(JdbcTemplate jdbcTemplate,
                              StockMutationService stockMutationService,
                              CurrentUserProvider currentUser) {
        this.jdbcTemplate = jdbcTemplate;
        this.stockMutationService = stockMutationService;
        this.currentUser = currentUser;
    }

    public Map<String, Object> listRequests(String requestNo, Integer status, LocalDate startDate, LocalDate endDate) {
        StringBuilder sql = new StringBuilder("""
            SELECT r.id, r.request_no, r.supplier_id, s.supplier_name, r.total_amount,
                   r.status, r.remark, u.username applicant, r.create_time, r.audit_remark, r.audit_time
            FROM pur_request r
            JOIN pur_supplier s ON s.id = r.supplier_id
            JOIN t_user u ON u.id = r.applicant_id
            WHERE 1=1
            """);
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(requestNo)) {
            sql.append(" AND r.request_no ILIKE ?");
            args.add("%" + requestNo.trim() + "%");
        }
        if (status != null) {
            sql.append(" AND r.status = ?");
            args.add(status);
        }
        if (startDate != null) {
            sql.append(" AND r.create_time >= ?");
            args.add(Date.valueOf(startDate));
        }
        if (endDate != null) {
            sql.append(" AND r.create_time < ?");
            args.add(Date.valueOf(endDate.plusDays(1)));
        }
        sql.append(" ORDER BY r.create_time DESC, r.id DESC");
        List<Map<String, Object>> list = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("requestId", rs.getLong("id"));
            row.put("requestNo", rs.getString("request_no"));
            row.put("supplierId", rs.getLong("supplier_id"));
            row.put("supplierName", rs.getString("supplier_name"));
            row.put("totalAmount", rs.getBigDecimal("total_amount"));
            row.put("status", rs.getInt("status"));
            row.put("remark", rs.getString("remark"));
            row.put("applicant", rs.getString("applicant"));
            row.put("createTime", rs.getTimestamp("create_time").toLocalDateTime());
            row.put("auditRemark", rs.getString("audit_remark"));
            row.put("auditTime", rs.getTimestamp("audit_time") == null ? null : rs.getTimestamp("audit_time").toLocalDateTime());
            return row;
        }, args.toArray());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", list.size());
        response.put("list", list);
        return response;
    }

    @Transactional
    public Map<String, Object> createRequest(Long supplierId, List<RequestItem> items, String remark) {
        requireExists("SELECT COUNT(*) FROM pur_supplier WHERE id = ? AND status = 0", supplierId, "供应商不存在或已停用");
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "采购明细不能为空");
        }
        long distinctSkuCount = items.stream().map(RequestItem::skuId).distinct().count();
        if (distinctSkuCount != items.size()) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "同一SKU不能重复出现在采购明细中");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (RequestItem item : items) {
            requireExists("SELECT COUNT(*) FROM pro_sku WHERE id = ? AND deleted = 0", item.skuId(), "SKU不存在");
            if (item.quantity() == null || item.quantity() < 1 || item.expectedPrice() == null || item.expectedPrice().signum() < 0) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "采购数量和预计价格不合法");
            }
            total = total.add(item.expectedPrice().multiply(BigDecimal.valueOf(item.quantity())));
        }
        String requestNo = businessNo("PR");
        Long requestId = jdbcTemplate.queryForObject("""
            INSERT INTO pur_request(request_no, supplier_id, applicant_id, total_amount, remark)
            VALUES (?, ?, ?, ?, ?) RETURNING id
            """, Long.class, requestNo, supplierId, currentUser.requireUserId(), total, remark);
        for (RequestItem item : items) {
            String skuCode = jdbcTemplate.queryForObject("SELECT sku_code FROM pro_sku WHERE id = ?", String.class, item.skuId());
            jdbcTemplate.update("""
                INSERT INTO pur_request_item(request_id, sku_id, sku_code, quantity, expected_price, remark)
                VALUES (?, ?, ?, ?, ?, ?)
                """, requestId, item.skuId(), skuCode, item.quantity(), item.expectedPrice(), item.remark());
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", requestId);
        response.put("requestNo", requestNo);
        response.put("status", 0);
        return response;
    }

    @Transactional
    public void auditRequest(Long requestId, Integer auditStatus, String auditRemark) {
        if (auditStatus == null || (auditStatus != 1 && auditStatus != 2)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "审核状态只能是1或2");
        }
        int changed = jdbcTemplate.update("""
            UPDATE pur_request SET status = ?, auditor_id = ?, audit_remark = ?,
                audit_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
            WHERE id = ? AND status = 0
            """, auditStatus, currentUser.requireUserId(), auditRemark, requestId);
        if (changed == 0) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pur_request WHERE id = ?", Integer.class, requestId);
            if (count == null || count == 0) {
                throw new BusinessException(ApiErrorCode.NOT_FOUND, "采购申请不存在");
            }
            throw new BusinessException(ApiErrorCode.ORDER_STATUS_INVALID, "只有待审核申请可以审核");
        }
    }

    @Transactional
    public Map<String, Object> createOrder(Long requestId, LocalDate deliveryDate) {
        List<RequestHeader> headers = jdbcTemplate.query("""
            SELECT supplier_id, total_amount, status FROM pur_request WHERE id = ? FOR UPDATE
            """, (rs, rowNum) -> new RequestHeader(rs.getLong("supplier_id"), rs.getBigDecimal("total_amount"), rs.getInt("status")), requestId);
        if (headers.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "采购申请不存在");
        }
        if (headers.getFirst().status() != 1) {
            throw new BusinessException(ApiErrorCode.ORDER_STATUS_INVALID, "采购申请未审核通过");
        }
        Integer existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pur_order WHERE request_id = ?", Integer.class, requestId);
        if (existing != null && existing > 0) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "该申请已生成采购订单");
        }
        String orderNo = businessNo("PO");
        RequestHeader header = headers.getFirst();
        Long orderId = jdbcTemplate.queryForObject("""
            INSERT INTO pur_order(order_no, request_id, supplier_id, delivery_date, total_amount)
            VALUES (?, ?, ?, ?, ?) RETURNING id
            """, Long.class, orderNo, requestId, header.supplierId(), deliveryDate, header.totalAmount());
        jdbcTemplate.update("""
            INSERT INTO pur_order_item(order_id, sku_id, sku_code, quantity, price)
            SELECT ?, sku_id, sku_code, quantity, expected_price FROM pur_request_item WHERE request_id = ?
            """, orderId, requestId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderId", orderId);
        response.put("orderNo", orderNo);
        response.put("supplierId", header.supplierId());
        response.put("totalAmount", header.totalAmount());
        response.put("status", 0);
        return response;
    }

    @Transactional
    public Map<String, Object> forceCloseOrder(Long orderId, String closeReason, Integer closeType,
                                               List<DiscardedItem> discardedItems) {
        if (!StringUtils.hasText(closeReason)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "强制完结原因不能为空");
        }
        int type = closeType == null ? 3 : closeType;
        if (type < 1 || type > 3) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "完结类型只能是1/2/3");
        }
        List<Map<String, Object>> orders = jdbcTemplate.query("""
            SELECT id, order_no, status FROM pur_order WHERE id = ? FOR UPDATE
            """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("orderNo", rs.getString("order_no"));
            row.put("status", rs.getInt("status"));
            return row;
        }, orderId);
        if (orders.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "采购订单不存在");
        }
        int status = (Integer) orders.getFirst().get("status");
        if (status != 1) {
            throw new BusinessException(ApiErrorCode.FORCE_CLOSE_NOT_ALLOWED);
        }
        Integer pendingReturn = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pur_return WHERE order_id = ? AND status = 0", Integer.class, orderId);
        if (pendingReturn != null && pendingReturn > 0) {
            throw new BusinessException(ApiErrorCode.FORCE_CLOSE_NOT_ALLOWED, "存在未完结退货单，不允许强制完结");
        }

        if (discardedItems != null && !discardedItems.isEmpty()) {
            for (DiscardedItem item : discardedItems) {
                List<OrderItemRow> rows = jdbcTemplate.query("""
                    SELECT id, sku_id, quantity, inbound_quantity, returned_quantity, discarded_quantity
                    FROM pur_order_item WHERE id = ? AND order_id = ? FOR UPDATE
                    """, (rs, rowNum) -> new OrderItemRow(rs.getLong("sku_id"), rs.getInt("quantity"),
                    rs.getInt("inbound_quantity"), rs.getInt("returned_quantity"), rs.getLong("id"),
                    rs.getInt("discarded_quantity")), item.orderItemId(), orderId);
                if (rows.isEmpty()) {
                    throw new BusinessException(ApiErrorCode.BAD_REQUEST, "采购订单明细不存在: " + item.orderItemId());
                }
                OrderItemRow row = rows.getFirst();
                int remaining = row.quantity() - row.inboundQuantity() - row.discardedQuantity();
                if (item.discardedQuantity() == null || item.discardedQuantity() < 1 || item.discardedQuantity() > remaining) {
                    throw new BusinessException(ApiErrorCode.BAD_REQUEST, "作废数量不合法");
                }
                jdbcTemplate.update("UPDATE pur_order_item SET discarded_quantity = discarded_quantity + ? WHERE id = ?",
                    item.discardedQuantity(), item.orderItemId());
            }
        } else {
            jdbcTemplate.update("""
                UPDATE pur_order_item
                SET discarded_quantity = GREATEST(quantity - inbound_quantity - discarded_quantity, 0) + discarded_quantity
                WHERE order_id = ?
                """, orderId);
        }

        Long closeLogId = jdbcTemplate.queryForObject("""
            INSERT INTO pur_order_close_log(order_id, close_reason, close_type, operator_id)
            VALUES (?, ?, ?, ?) RETURNING id
            """, Long.class, orderId, closeReason.trim(), type, currentUser.requireUserId());
        jdbcTemplate.update("UPDATE pur_order SET status = 3, update_time = CURRENT_TIMESTAMP WHERE id = ?", orderId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderId", orderId);
        response.put("orderNo", orders.getFirst().get("orderNo"));
        response.put("status", 3);
        response.put("closedAt", LocalDateTime.now());
        response.put("closeLogId", closeLogId);
        return response;
    }

    @Transactional
    public Map<String, Object> inbound(Long orderId, Long warehouseId, List<InboundItem> items) {
        List<Integer> orderStatus = jdbcTemplate.query(
            "SELECT status FROM pur_order WHERE id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getInt(1), orderId);
        if (orderStatus.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "采购订单不存在");
        }
        if (orderStatus.getFirst() == 2 || orderStatus.getFirst() == 3) {
            throw new BusinessException(ApiErrorCode.ORDER_STATUS_INVALID, "采购订单已完结，不能继续入库");
        }
        requireExists("SELECT COUNT(*) FROM sto_warehouse WHERE id = ? AND status = 0", warehouseId, "仓库不存在");
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "入库明细不能为空");
        }
        String inboundNo = businessNo("IB");
        Long inboundId = jdbcTemplate.queryForObject("""
            INSERT INTO pur_inbound(inbound_no, order_id, warehouse_id, operator_id)
            VALUES (?, ?, ?, ?) RETURNING id
            """, Long.class, inboundNo, orderId, warehouseId, currentUser.requireUserId());
        int index = 0;
        for (InboundItem item : items) {
            if (!StringUtils.hasText(item.batchNo())) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "batchNo 必填");
            }
            validateBatchDates(item.skuId(), item.productionDate(), item.expireDate());
            Integer batchDup = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sto_stock
                WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ? AND deleted = 0
                """, Integer.class, item.skuId(), warehouseId, item.batchNo().trim());
            if (batchDup != null && batchDup > 0) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "批次号在同一SKU仓库下已存在: " + item.batchNo());
            }
            List<OrderItemRow> rows = jdbcTemplate.query("""
                SELECT id, sku_id, quantity, inbound_quantity, returned_quantity, discarded_quantity
                FROM pur_order_item WHERE id = ? AND order_id = ? FOR UPDATE
                """, (rs, rowNum) -> new OrderItemRow(rs.getLong("sku_id"), rs.getInt("quantity"),
                rs.getInt("inbound_quantity"), rs.getInt("returned_quantity"), rs.getLong("id"),
                rs.getInt("discarded_quantity")), item.orderItemId(), orderId);
            if (rows.isEmpty() || !rows.getFirst().skuId().equals(item.skuId())) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "采购订单明细不匹配");
            }
            OrderItemRow orderItem = rows.getFirst();
            int remaining = orderItem.quantity() - orderItem.inboundQuantity() - orderItem.discardedQuantity();
            if (item.actualQuantity() == null || item.actualQuantity() < 1 || item.actualQuantity() > remaining) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "入库数量超过待入库数量");
            }
            requireExists("SELECT COUNT(*) FROM sto_location WHERE id = ? AND warehouse_id = ? AND status = 0 AND deleted = 0",
                item.locationId(), warehouseId, "入库库位不属于指定仓库");
            jdbcTemplate.update("""
                INSERT INTO pur_inbound_item(inbound_id, order_item_id, sku_id, actual_quantity, location_id,
                                             batch_no, production_date, expire_date, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, inboundId, item.orderItemId(), item.skuId(), item.actualQuantity(), item.locationId(),
                item.batchNo().trim(), item.productionDate(), item.expireDate(), item.remark());
            stockMutationService.adjust(item.skuId(), warehouseId, item.locationId(), item.actualQuantity(), 0, 1,
                inboundNo, "inbound:" + inboundId + ":" + index++, currentUser.requireUsername(),
                item.batchNo().trim(), item.productionDate(), item.expireDate(), "采购入库");
            jdbcTemplate.update("UPDATE pur_order_item SET inbound_quantity = inbound_quantity + ? WHERE id = ?",
                item.actualQuantity(), item.orderItemId());
        }
        Integer remaining = jdbcTemplate.queryForObject("""
            SELECT COALESCE(SUM(quantity - inbound_quantity - discarded_quantity), 0)
            FROM pur_order_item WHERE order_id = ?
            """, Integer.class, orderId);
        jdbcTemplate.update("UPDATE pur_order SET status = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?",
            remaining != null && remaining == 0 ? 2 : 1, orderId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("inboundId", inboundId);
        response.put("inboundNo", inboundNo);
        response.put("status", 1);
        return response;
    }

    @Transactional
    public Map<String, Object> createReturn(Long orderId, String reason, List<ReturnItem> items) {
        requireExists("SELECT COUNT(*) FROM pur_order WHERE id = ?", orderId, "采购订单不存在");
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "退货原因不能为空");
        }
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "退货明细不能为空");
        }
        Integer pending = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pur_return WHERE order_id = ? AND status = 0", Integer.class, orderId);
        if (pending != null && pending > 0) {
            throw new BusinessException(ApiErrorCode.RETURN_DUPLICATE);
        }
        String returnNo = businessNo("RT");
        LocalDateTime lockedAt = LocalDateTime.now();
        Long returnId = jdbcTemplate.queryForObject("""
            INSERT INTO pur_return(return_no, order_id, reason, status, applicant_id, locked_at)
            VALUES (?, ?, ?, 0, ?, ?) RETURNING id
            """, Long.class, returnNo, orderId, reason, currentUser.requireUserId(), lockedAt);
        int index = 0;
        for (ReturnItem item : items) {
            List<OrderItemRow> orderItems = jdbcTemplate.query("""
                SELECT id, sku_id, quantity, inbound_quantity, returned_quantity, discarded_quantity
                FROM pur_order_item WHERE order_id = ? AND sku_id = ? ORDER BY id LIMIT 1 FOR UPDATE
                """, (rs, rowNum) -> new OrderItemRow(rs.getLong("sku_id"), rs.getInt("quantity"),
                rs.getInt("inbound_quantity"), rs.getInt("returned_quantity"), rs.getLong("id"),
                rs.getInt("discarded_quantity")), orderId, item.skuId());
            if (orderItems.isEmpty()) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "退货SKU不属于采购订单");
            }
            OrderItemRow orderItem = orderItems.getFirst();
            if (item.quantity() == null || item.quantity() < 1
                || orderItem.returnedQuantity() + item.quantity() > orderItem.inboundQuantity()) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "退货数量超过已入库数量");
            }
            StockMutationService.StockAllocation allocation = stockMutationService.requireAllocation(item.skuId(), item.quantity());
            jdbcTemplate.update("""
                INSERT INTO pur_return_item(return_id, order_item_id, sku_id, quantity, warehouse_id, location_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, returnId, orderItem.id(), item.skuId(), item.quantity(), allocation.warehouseId(), allocation.locationId());
            stockMutationService.adjust(item.skuId(), allocation.warehouseId(), allocation.locationId(),
                0, item.quantity(), 2, returnNo, "return-lock:" + returnId + ":" + index++,
                currentUser.requireUsername(), null, "退货预占：" + reason);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("returnId", returnId);
        response.put("returnNo", returnNo);
        response.put("status", 0);
        response.put("lockedAt", lockedAt);
        return response;
    }

    @Transactional
    public Map<String, Object> confirmReturn(Long returnId, String confirmRemark) {
        ReturnHeader header = requireReturn(returnId);
        if (header.status() != 0) {
            throw new BusinessException(ApiErrorCode.RETURN_STATUS_INVALID);
        }
        List<ReturnLine> lines = listReturnLines(returnId);
        int index = 0;
        for (ReturnLine line : lines) {
            stockMutationService.adjust(line.skuId(), line.warehouseId(), line.locationId(),
                -line.quantity(), -line.quantity(), 2, header.returnNo(),
                "return-confirm:" + returnId + ":" + index++, currentUser.requireUsername(), null, "退货出库");
            jdbcTemplate.update("UPDATE pur_order_item SET returned_quantity = returned_quantity + ? WHERE id = ?",
                line.quantity(), line.orderItemId());
        }
        jdbcTemplate.update("""
            UPDATE pur_return SET status = 1, confirm_remark = ?, confirm_time = CURRENT_TIMESTAMP
            WHERE id = ?
            """, confirmRemark, returnId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("returnId", returnId);
        response.put("returnNo", header.returnNo());
        response.put("status", 1);
        return response;
    }

    @Transactional
    public Map<String, Object> rejectReturn(Long returnId, String rejectReason) {
        if (!StringUtils.hasText(rejectReason)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "驳回原因不能为空");
        }
        ReturnHeader header = requireReturn(returnId);
        if (header.status() != 0) {
            throw new BusinessException(ApiErrorCode.RETURN_STATUS_INVALID);
        }
        List<ReturnLine> lines = listReturnLines(returnId);
        int index = 0;
        for (ReturnLine line : lines) {
            stockMutationService.adjust(line.skuId(), line.warehouseId(), line.locationId(),
                0, -line.quantity(), 2, header.returnNo(),
                "return-reject:" + returnId + ":" + index++, currentUser.requireUsername(), null, "退货驳回：" + rejectReason);
        }
        jdbcTemplate.update("""
            UPDATE pur_return SET status = 2, reject_reason = ?, reject_time = CURRENT_TIMESTAMP
            WHERE id = ?
            """, rejectReason, returnId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("returnId", returnId);
        response.put("returnNo", header.returnNo());
        response.put("status", 2);
        return response;
    }

    private void validateBatchDates(Long skuId, LocalDate productionDate, LocalDate expireDate) {
        if (productionDate != null && expireDate != null && !expireDate.isAfter(productionDate)) {
            throw new BusinessException(ApiErrorCode.BATCH_DATE_INVALID);
        }
        Integer required = jdbcTemplate.query("""
            SELECT c.requires_batch_date
            FROM pro_sku s
            JOIN pro_product p ON p.id = s.product_id
            JOIN pro_category c ON c.id = p.category_id
            WHERE s.id = ?
            """, rs -> rs.next() ? rs.getInt(1) : 0, skuId);
        if (required != null && required == 1 && (productionDate == null || expireDate == null)) {
            throw new BusinessException(ApiErrorCode.BATCH_DATE_INVALID, "该品类必须填写生产日期和到期日期");
        }
    }

    private ReturnHeader requireReturn(Long returnId) {
        List<ReturnHeader> rows = jdbcTemplate.query("""
            SELECT return_no, status FROM pur_return WHERE id = ? FOR UPDATE
            """, (rs, rowNum) -> new ReturnHeader(rs.getString("return_no"), rs.getInt("status")), returnId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "退货单不存在");
        }
        return rows.getFirst();
    }

    private List<ReturnLine> listReturnLines(Long returnId) {
        return jdbcTemplate.query("""
            SELECT order_item_id, sku_id, quantity, warehouse_id, location_id
            FROM pur_return_item WHERE return_id = ?
            """, (rs, rowNum) -> new ReturnLine(rs.getLong("order_item_id"), rs.getLong("sku_id"),
            rs.getInt("quantity"), rs.getLong("warehouse_id"), rs.getLong("location_id")), returnId);
    }

    private void requireExists(String sql, Object id, String message) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, id);
        if (count == null || count == 0) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, message);
        }
    }

    private void requireExists(String sql, Object first, Object second, String message) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, first, second);
        if (count == null || count == 0) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, message);
        }
    }

    private String businessNo(String prefix) {
        return prefix + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    public record RequestItem(Long skuId, String skuCode, Integer quantity, BigDecimal expectedPrice, String remark) {
    }

    public record InboundItem(Long orderItemId, Long skuId, Integer actualQuantity,
                              Long locationId, String batchNo, LocalDate productionDate,
                              LocalDate expireDate, String remark) {
    }

    public record ReturnItem(Long skuId, Integer quantity) {
    }

    public record DiscardedItem(Long orderItemId, Integer discardedQuantity) {
    }

    private record RequestHeader(Long supplierId, BigDecimal totalAmount, int status) {
    }

    private record OrderItemRow(Long skuId, int quantity, int inboundQuantity, int returnedQuantity,
                                Long id, int discardedQuantity) {
    }

    private record ReturnHeader(String returnNo, int status) {
    }

    private record ReturnLine(Long orderItemId, Long skuId, int quantity, Long warehouseId, Long locationId) {
    }
}
