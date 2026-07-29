package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.dto.RefundAuditRequest;
import com.example.demo.dto.RefundCompleteRequest;
import com.example.demo.exception.BusinessException;
import com.example.demo.json.RefundTimeJsonCodec;
import com.example.demo.security.CurrentUserProvider;
import com.example.demo.security.CustomerIdProvider;
import com.example.demo.vo.RefundItemResponse;
import com.example.demo.vo.RefundResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.2 §7.11 退款管理。
 *
 * <p>售后申请由 {@link AftersaleService} 独立创建；本服务只处理退款查询、
 * 审核、完成和取消。库存回补只通过 refund.completed Outbox 事件通知 IVP，
 * 不直接写库存。</p>
 */
@Service
public class RefundService {

    private static final TypeReference<Void> VOID_RESPONSE =
        new TypeReference<>() {};
    private static final TypeReference<RefundResponse> REFUND_RESPONSE =
        new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserProvider currentUser;
    private final CustomerIdProvider customerIdProvider;
    private final OutboxEventService outboxEventService;
    private final SalesIdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public RefundService(JdbcTemplate jdbcTemplate,
                         CurrentUserProvider currentUser,
                         CustomerIdProvider customerIdProvider,
                         OutboxEventService outboxEventService,
                         SalesIdempotencyService idempotencyService,
                         ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
        this.customerIdProvider = customerIdProvider;
        this.outboxEventService = outboxEventService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> merchantList(
        Integer status,
        String refundNo,
        String orderNo,
        LocalDateTime startDate,
        LocalDateTime endDate,
        int pageNum,
        int pageSize) {
        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilters(
            where, args, status, refundNo, orderNo, startDate, endDate);
        return queryPage(where, args, pageNum, pageSize);
    }

    public Map<String, Object> myList(
        Integer status,
        String refundNo,
        String orderNo,
        LocalDateTime startDate,
        LocalDateTime endDate,
        int pageNum,
        int pageSize) {
        long customerId = customerIdProvider.requireCurrentCustomerId();
        StringBuilder where =
            new StringBuilder("WHERE r.customer_id=?");
        List<Object> args = new ArrayList<>();
        args.add(customerId);
        appendFilters(
            where, args, status, refundNo, orderNo, startDate, endDate);
        return queryPage(where, args, pageNum, pageSize);
    }

    public RefundResponse getDetail(Long refundId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
            """
            SELECT r.*, o.order_no
            FROM ref_refund r
            JOIN ord_order o
              ON o.id=r.order_id AND o.customer_id=r.customer_id
            JOIN ord_order_item oi
              ON oi.id=r.order_item_id AND oi.order_id=r.order_id
            WHERE r.id=?
            """,
            this::refundRow,
            refundId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "退款单不存在");
        }
        return toResponse(rows.getFirst());
    }

    @Transactional
    public RefundResponse audit(
        Long refundId,
        RefundAuditRequest request,
        String idempotencyKey) {
        long userId = currentUser.requireUserId();
        RefundAuditCommand command =
            new RefundAuditCommand(refundId, request);
        return idempotencyService.executeForUser(
            "refund:audit",
            userId,
            idempotencyKey,
            command,
            REFUND_RESPONSE,
            () -> auditRefund(refundId, request));
    }

    private RefundResponse auditRefund(
        Long refundId, RefundAuditRequest request) {
        validateAuditRequest(request);
        RefundState refund = requireRefundForUpdate(refundId);
        if (refund.status() != 0) {
            throw new BusinessException(
                ApiErrorCode.ORDER_STATE_INVALID,
                "只有 PENDING 状态退款单可审核");
        }

        LocalDateTime auditedAt = nowInRefundZone();
        int changed;
        if (request.auditStatus() == 1) {
            validateRefundApproval(refund, request);
            changed = jdbcTemplate.update("""
                UPDATE ref_refund
                SET status=1, approved_amount=?, approved_quantity=?,
                    audit_remark=?, audited_at=?, update_time=?
                WHERE id=? AND status=0
                """,
                request.approvedAmount(),
                request.approvedQuantity(),
                request.auditRemark(),
                auditedAt,
                auditedAt,
                refundId);
        } else {
            changed = jdbcTemplate.update("""
                UPDATE ref_refund
                SET status=2, audit_remark=?, audited_at=?, update_time=?
                WHERE id=? AND status=0
                """,
                request.auditRemark(),
                auditedAt,
                auditedAt,
                refundId);
        }
        if (changed == 0) {
            throw new BusinessException(
                ApiErrorCode.ORDER_STATE_INVALID, "状态机非法转换");
        }
        outboxEventService.addOutbox(
            "sales.refund.audited",
            "REFUND_AUDITED",
            refundId,
            Map.of(
                "refundId", refundId,
                "auditStatus", request.auditStatus()));
        return getDetail(refundId);
    }

    @Transactional
    public RefundResponse complete(
        Long refundId,
        RefundCompleteRequest request,
        String idempotencyKey) {
        long userId = currentUser.requireUserId();
        RefundCompleteCommand command =
            new RefundCompleteCommand(refundId, request);
        return idempotencyService.executeForUser(
            "refund:complete",
            userId,
            idempotencyKey,
            command,
            REFUND_RESPONSE,
            () -> completeRefund(refundId, request));
    }

    private RefundResponse completeRefund(
        Long refundId, RefundCompleteRequest request) {
        RefundState refund = requireRefundForUpdate(refundId);
        if (refund.status() != 1) {
            throw new BusinessException(
                ApiErrorCode.ORDER_STATE_INVALID,
                "只有 APPROVED 状态退款单可执行");
        }
        if (Boolean.TRUE.equals(request.restock()) && refund.type() != 2) {
            throw new BusinessException(
                ApiErrorCode.BAD_REQUEST,
                "仅退货退款允许退回商品并回补库存");
        }
        if (request.actualRefundAmount().compareTo(BigDecimal.ZERO) <= 0
            || refund.approvedAmount() == null
            || request.actualRefundAmount()
                .compareTo(refund.approvedAmount()) > 0) {
            throw new BusinessException(
                ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "actualRefundAmount 必须为正数且不得超过 approvedAmount");
        }

        Map<String, Object> eventPayload = loadCompletionEventPayload(
            refundId, request.actualRefundAmount(), request.restock());
        OffsetDateTime completedAt =
            OffsetDateTime.now(RefundTimeJsonCodec.REFUND_ZONE);
        LocalDateTime storedCompletedAt =
            RefundTimeJsonCodec.toStorageDateTime(completedAt);
        int refunding = jdbcTemplate.update("""
            UPDATE ref_refund
            SET status=3, actual_refund_amount=?, provider_refund_no=?,
                restock=?, completed_at=?, update_time=?
            WHERE id=? AND status=1
            """,
            request.actualRefundAmount(),
            request.providerRefundNo(),
            request.restock(),
            storedCompletedAt,
            storedCompletedAt,
            refundId);
        if (refunding == 0) {
            throw new BusinessException(
                ApiErrorCode.ORDER_STATE_INVALID,
                "只有 APPROVED 状态退款单可执行");
        }
        int completed = jdbcTemplate.update("""
            UPDATE ref_refund
            SET status=4, update_time=?
            WHERE id=? AND status=3
            """, storedCompletedAt, refundId);
        if (completed == 0) {
            throw new BusinessException(
                ApiErrorCode.ORDER_STATE_INVALID, "退款完成状态写入失败");
        }

        eventPayload.put("completedAt", completedAt);
        outboxEventService.addOutbox(
            "sales.refund.completed",
            "REFUND_COMPLETED",
            refundId,
            eventPayload);
        return getDetail(refundId);
    }

    @Transactional
    public void cancel(Long refundId, String idempotencyKey) {
        long customerId =
            customerIdProvider.requireCurrentCustomerIdForUpdate();
        RefundCancelCommand command = new RefundCancelCommand(refundId);
        idempotencyService.execute(
            "refund:cancel",
            customerId,
            idempotencyKey,
            command,
            VOID_RESPONSE,
            () -> {
                cancelRefund(refundId, customerId);
                return null;
            });
    }

    private void cancelRefund(Long refundId, long customerId) {
        int changed = jdbcTemplate.update("""
            UPDATE ref_refund
            SET status=5, update_time=?
            WHERE id=? AND status=0 AND customer_id=?
            """, nowInRefundZone(), refundId, customerId);
        if (changed == 0) {
            throwRefundCancelException(refundId, customerId);
        }
    }

    private void throwRefundCancelException(
        Long refundId, long customerId) {
        List<Long> owners = jdbcTemplate.query(
            "SELECT customer_id FROM ref_refund WHERE id=?",
            (rs, rowNum) -> rs.getLong("customer_id"),
            refundId);
        if (owners.isEmpty()) {
            throw new BusinessException(
                ApiErrorCode.NOT_FOUND, "退款单不存在");
        }
        if (owners.getFirst() != customerId) {
            throw new BusinessException(
                ApiErrorCode.RESOURCE_FORBIDDEN,
                "退款单不属于当前客户");
        }
        throw new BusinessException(
            ApiErrorCode.ORDER_STATE_INVALID,
            "只有 PENDING 状态的退款单可取消");
    }

    private void appendFilters(
        StringBuilder where,
        List<Object> args,
        Integer status,
        String refundNo,
        String orderNo,
        LocalDateTime startDate,
        LocalDateTime endDate) {
        if (status != null) {
            where.append(" AND r.status=?");
            args.add(status);
        }
        if (StringUtils.hasText(refundNo)) {
            where.append(" AND r.refund_no ILIKE ?");
            args.add("%" + refundNo.trim() + "%");
        }
        if (StringUtils.hasText(orderNo)) {
            where.append("""
                 AND r.order_id IN (
                     SELECT id FROM ord_order WHERE order_no ILIKE ?)
                """);
            args.add("%" + orderNo.trim() + "%");
        }
        if (startDate != null) {
            where.append(" AND r.applied_at >= ?");
            args.add(startDate);
        }
        if (endDate != null) {
            where.append(" AND r.applied_at < ?");
            args.add(endDate);
        }
    }

    private Map<String, Object> queryPage(
        StringBuilder where,
        List<Object> args,
        int pageNum,
        int pageSize) {
        Long total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ref_refund r " + where,
            Long.class,
            args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) (pageNum - 1) * pageSize);
        List<Map<String, Object>> list = jdbcTemplate.query(
            """
            SELECT r.*, o.order_no
            FROM ref_refund r
            JOIN ord_order o
              ON o.id=r.order_id AND o.customer_id=r.customer_id
            """
                + where
                + " ORDER BY r.applied_at DESC, r.id DESC LIMIT ? OFFSET ?",
            this::refundRow,
            pageArgs.toArray());
        return page(total == null ? 0 : total, pageNum, pageSize, list);
    }

    private RefundState requireRefundForUpdate(Long refundId) {
        List<RefundState> rows = jdbcTemplate.query("""
            SELECT id, order_id, order_item_id, customer_id, type, status,
                   apply_refund_amount, apply_refund_quantity,
                   approved_amount, approved_quantity
            FROM ref_refund
            WHERE id=?
            FOR UPDATE
            """, (rs, rowNum) -> new RefundState(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getLong("order_item_id"),
            rs.getLong("customer_id"),
            rs.getInt("type"),
            rs.getInt("status"),
            rs.getBigDecimal("apply_refund_amount"),
            rs.getInt("apply_refund_quantity"),
            rs.getBigDecimal("approved_amount"),
            rs.getObject("approved_quantity") == null
                ? null
                : rs.getInt("approved_quantity")), refundId);
        if (rows.isEmpty()) {
            throw new BusinessException(
                ApiErrorCode.NOT_FOUND, "退款单不存在");
        }
        return rows.getFirst();
    }

    private void validateAuditRequest(RefundAuditRequest request) {
        if (request.auditStatus() == null
            || (request.auditStatus() != 1 && request.auditStatus() != 2)) {
            throw new BusinessException(
                ApiErrorCode.BAD_REQUEST, "auditStatus 只能是 1/2");
        }
        if (request.auditStatus() == 2
            && !StringUtils.hasText(request.auditRemark())) {
            throw new BusinessException(
                ApiErrorCode.BAD_REQUEST, "驳回必须填写 auditRemark");
        }
    }

    private void validateRefundApproval(
        RefundState refund, RefundAuditRequest request) {
        if (request.approvedAmount() == null
            || request.approvedAmount().compareTo(BigDecimal.ZERO) <= 0
            || request.approvedAmount()
                .compareTo(refund.applyRefundAmount()) > 0) {
            throw new BusinessException(
                ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "approvedAmount 必须为正数且不得超过 applyRefundAmount");
        }
        if (refund.type() == 2
            && (request.approvedQuantity() == null
                || request.approvedQuantity() <= 0
                || request.approvedQuantity()
                    > refund.applyRefundQuantity())) {
            throw new BusinessException(
                ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "approvedQuantity 必须为正数且不得超过 applyRefundQuantity");
        }
        if (request.approvedQuantity() != null
            && request.approvedQuantity() > refund.applyRefundQuantity()) {
            throw new BusinessException(
                ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "approvedQuantity 不得超过 applyRefundQuantity");
        }
    }

    private Map<String, Object> loadCompletionEventPayload(
        Long refundId,
        BigDecimal actualRefundAmount,
        Boolean restock) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
            SELECT r.refund_no, r.order_item_id, o.order_no,
                   oi.sku_id, oi.warehouse_id, oi.location_id,
                   COALESCE(r.approved_quantity,
                            r.apply_refund_quantity, 0) refund_quantity,
                   oi.quantity order_item_quantity
            FROM ref_refund r
            JOIN ord_order o
              ON o.id=r.order_id AND o.customer_id=r.customer_id
            JOIN ord_order_item oi
              ON oi.id=r.order_item_id AND oi.order_id=r.order_id
            WHERE r.id=?
            FOR UPDATE OF oi
            """, refundId);
        int refundQuantity =
            ((Number) row.get("refund_quantity")).intValue();
        int orderItemQuantity =
            ((Number) row.get("order_item_quantity")).intValue();

        if (Boolean.TRUE.equals(restock)) {
            Long alreadyRestocked = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(
                    COALESCE(approved_quantity, apply_refund_quantity, 0)
                ), 0)
                FROM ref_refund
                WHERE order_item_id=?
                  AND id<>?
                  AND status IN (3,4)
                  AND restock=TRUE
                """, Long.class, row.get("order_item_id"), refundId);
            long cumulative =
                (alreadyRestocked == null ? 0L : alreadyRestocked)
                    + refundQuantity;
            if (refundQuantity <= 0 || cumulative > orderItemQuantity) {
                throw new BusinessException(
                    ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                    "累计回补数量必须为正数且不得超过订单项实际数量");
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundId", refundId);
        payload.put("refundNo", row.get("refund_no"));
        payload.put("orderNo", row.get("order_no"));
        payload.put("orderItemId", row.get("order_item_id"));
        payload.put("skuId", row.get("sku_id"));
        payload.put("warehouseId", row.get("warehouse_id"));
        payload.put("locationId", row.get("location_id"));
        payload.put("quantity", refundQuantity);
        payload.put("restock", restock);
        payload.put("actualRefundAmount", actualRefundAmount);
        return payload;
    }

    private Map<String, Object> refundRow(
        java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("refundId", rs.getLong("id"));
        row.put("refundNo", rs.getString("refund_no"));
        row.put("orderId", rs.getLong("order_id"));
        row.put("orderNo", rs.getString("order_no"));
        row.put("orderItemId", rs.getLong("order_item_id"));
        row.put("customerId", rs.getLong("customer_id"));
        row.put("type", rs.getInt("type"));
        row.put("status", rs.getInt("status"));
        row.put(
            "applyRefundAmount", rs.getBigDecimal("apply_refund_amount"));
        row.put(
            "applyRefundQuantity", rs.getInt("apply_refund_quantity"));
        row.put("approvedAmount", rs.getBigDecimal("approved_amount"));
        row.put(
            "approvedQuantity",
            rs.getObject("approved_quantity") == null
                ? null
                : rs.getInt("approved_quantity"));
        row.put(
            "actualRefundAmount",
            rs.getBigDecimal("actual_refund_amount"));
        row.put(
            "providerRefundNo", rs.getString("provider_refund_no"));
        row.put("restock", rs.getBoolean("restock"));
        row.put("reason", rs.getString("reason"));
        row.put("auditRemark", rs.getString("audit_remark"));
        row.put(
            "appliedAt",
            RefundTimeJsonCodec.toOffsetDateTime(
                rs.getObject("applied_at", LocalDateTime.class)));
        row.put(
            "auditedAt",
            RefundTimeJsonCodec.toOffsetDateTime(
                rs.getObject("audited_at", LocalDateTime.class)));
        row.put(
            "completedAt",
            RefundTimeJsonCodec.toOffsetDateTime(
                rs.getObject("completed_at", LocalDateTime.class)));
        row.put("images", parseImages(rs.getString("images")));
        return row;
    }

    private RefundResponse toResponse(Map<String, Object> row) {
        int type = ((Number) row.get("type")).intValue();
        int status = ((Number) row.get("status")).intValue();
        List<RefundItemResponse> items = jdbcTemplate.query("""
            SELECT sku_id, warehouse_id, location_id, quantity
            FROM ref_refund_item
            WHERE refund_id=?
            ORDER BY id
            """, (rs, rowNum) -> new RefundItemResponse(
            rs.getLong("sku_id"),
            rs.getObject("warehouse_id") == null
                ? null
                : rs.getLong("warehouse_id"),
            rs.getObject("location_id") == null
                ? null
                : rs.getLong("location_id"),
            rs.getInt("quantity")), row.get("refundId"));
        return new RefundResponse(
            ((Number) row.get("refundId")).longValue(),
            (String) row.get("refundNo"),
            ((Number) row.get("orderId")).longValue(),
            ((Number) row.get("orderItemId")).longValue(),
            type,
            typeText(type),
            (BigDecimal) row.get("applyRefundAmount"),
            ((Number) row.get("applyRefundQuantity")).intValue(),
            (BigDecimal) row.get("approvedAmount"),
            (BigDecimal) row.get("actualRefundAmount"),
            status,
            statusText(status),
            (String) row.get("reason"),
            castImages(row.get("images")),
            toStorageDateTime(row.get("appliedAt")),
            (Boolean) row.get("restock"),
            (String) row.get("providerRefundNo"),
            toStorageDateTime(row.get("completedAt")),
            (String) row.get("auditRemark"),
            items);
    }

    private List<String> parseImages(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                value, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return List.of(value.split(","));
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> castImages(Object value) {
        return value instanceof List<?> ? (List<String>) value : List.of();
    }

    private LocalDateTime toStorageDateTime(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) {
            return RefundTimeJsonCodec.toStorageDateTime(offsetDateTime);
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        return null;
    }

    private Map<String, Object> page(
        long total, int pageNum, int pageSize, List<?> list) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        result.put("pages", (total + pageSize - 1) / pageSize);
        result.put("list", list);
        return result;
    }

    private String typeText(Integer type) {
        return switch (type == null ? -1 : type) {
            case 1 -> "仅退款";
            case 2 -> "退货退款";
            case 3 -> "换货";
            default -> "未知";
        };
    }

    private String statusText(Integer status) {
        return switch (status == null ? -1 : status) {
            case 0 -> "待审核";
            case 1 -> "已审核通过";
            case 2 -> "已驳回";
            case 3 -> "退款中";
            case 4 -> "退款完成";
            case 5 -> "售后取消";
            default -> "未知";
        };
    }

    private LocalDateTime nowInRefundZone() {
        return RefundTimeJsonCodec.toStorageDateTime(
            OffsetDateTime.now(RefundTimeJsonCodec.REFUND_ZONE));
    }

    private record RefundState(
        long id,
        long orderId,
        long orderItemId,
        long customerId,
        int type,
        int status,
        BigDecimal applyRefundAmount,
        int applyRefundQuantity,
        BigDecimal approvedAmount,
        Integer approvedQuantity) {
    }

    private record RefundAuditCommand(
        Long refundId, RefundAuditRequest request) {
    }

    private record RefundCompleteCommand(
        Long refundId, RefundCompleteRequest request) {
    }

    private record RefundCancelCommand(Long refundId) {
    }
}
