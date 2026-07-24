package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.dto.AftersaleApplyRequest;
import com.example.demo.dto.RefundAuditRequest;
import com.example.demo.dto.RefundCompleteRequest;
import com.example.demo.entity.Refund;
import com.example.demo.exception.BusinessException;
import com.example.demo.mapper.RefundMapper;
import com.example.demo.security.CurrentUserProvider;
import com.example.demo.vo.RefundItemResponse;
import com.example.demo.vo.RefundResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v1.2 §7.11 退款管理。状态机：PENDING(0)/APPROVED(1)/REJECTED(2)/REFUNDING(3)/COMPLETED(4)/CANCELLED(5)。
 */
@Service
public class RefundService {

    private final JdbcTemplate jdbcTemplate;
    private final RefundMapper refundMapper;
    private final CurrentUserProvider currentUser;
    private final StockReservationService stockReservationService;
    private final OutboxEventService outboxEventService;

    public RefundService(JdbcTemplate jdbcTemplate,
                         RefundMapper refundMapper,
                         CurrentUserProvider currentUser,
                         StockReservationService stockReservationService,
                         OutboxEventService outboxEventService) {
        this.jdbcTemplate = jdbcTemplate;
        this.refundMapper = refundMapper;
        this.currentUser = currentUser;
        this.stockReservationService = stockReservationService;
        this.outboxEventService = outboxEventService;
    }

    @Transactional
    public RefundResponse applyAftersale(AftersaleApplyRequest request, String idempotencyKey) {
        Long userId = currentUser.requireUserId();
        // 校验订单归属 + 主状态
        List<Map<String, Object>> orders = jdbcTemplate.query(
            "SELECT o.id, o.status, o.customer_id, o.pay_amount, oi.id item_id, oi.price, oi.quantity" +
                " FROM ord_order o JOIN ord_order_item oi ON oi.order_id=o.id" +
                " WHERE o.id=? AND o.user_id=? AND oi.id=?",
            (rs, rowNum) -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("orderId", rs.getLong("id"));
                map.put("status", rs.getInt("status"));
                map.put("customerId", rs.getLong("customer_id"));
                map.put("payAmount", rs.getBigDecimal("pay_amount"));
                map.put("itemId", rs.getLong("item_id"));
                map.put("price", rs.getBigDecimal("price"));
                map.put("quantity", rs.getInt("quantity"));
                return map;
            }, request.orderId(), userId, request.orderItemId());
        if (orders.isEmpty()) {
            throw new BusinessException(ApiErrorCode.RESOURCE_FORBIDDEN, "订单或订单项不属于当前用户");
        }
        Map<String, Object> order = orders.getFirst();
        int status = (Integer) order.get("status");
        if (status != 2 && status != 3 && status != 5) {
            throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID, "只有已发货/已完成/售后中订单可申请售后");
        }
        // 校验可退额度（50016）
        BigDecimal price = (BigDecimal) order.get("price");
        int qty = (Integer) order.get("quantity");
        BigDecimal maxRefund = price.multiply(BigDecimal.valueOf(qty));
        if (request.applyRefundAmount() != null && request.applyRefundAmount().compareTo(maxRefund) > 0) {
            throw new BusinessException(ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "退款金额 " + request.applyRefundAmount() + " 超过订单项可退金额 " + maxRefund);
        }
        if (request.applyRefundQuantity() != null && request.applyRefundQuantity() > qty) {
            throw new BusinessException(ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "退款数量 " + request.applyRefundQuantity() + " 超过订单项数量 " + qty);
        }
        // 已退款金额/数量校验
        Map<String, Object> sum = jdbcTemplate.queryForMap("""
            SELECT COALESCE(SUM(actual_refund_amount),0) refunded_amount,
                   COALESCE(SUM(apply_refund_quantity),0) refunded_qty
            FROM ref_refund WHERE order_item_id=? AND status IN (1,3,4)
            """, request.orderItemId());
        BigDecimal refundedAmount = toBigDecimal(sum.get("refunded_amount"));
        BigDecimal refundedQty = toBigDecimal(sum.get("refunded_qty"));
        if (request.applyRefundAmount() != null
            && refundedAmount.add(request.applyRefundAmount()).compareTo(maxRefund) > 0) {
            throw new BusinessException(ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "累计退款金额超过订单项可退金额");
        }
        if (request.applyRefundQuantity() != null
            && refundedQty.add(BigDecimal.valueOf(request.applyRefundQuantity())).compareTo(BigDecimal.valueOf(qty)) > 0) {
            throw new BusinessException(ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "累计退款数量超过订单项数量");
        }
        Long customerId = (Long) order.get("customerId");
        if (customerId == null) {
            customerId = userId;
        }
        String refundNo = businessNo("RF");
        Long refundId;
        try {
            refundId = jdbcTemplate.queryForObject("""
                INSERT INTO ref_refund(refund_no, order_id, order_item_id, customer_id, type, status,
                    apply_refund_amount, apply_refund_quantity, reason, images, idempotency_key)
                VALUES (?,?,?,?,?,0,?,?,?,?,?) RETURNING id
                """, Long.class, refundNo, request.orderId(), request.orderItemId(), customerId,
                request.type(),
                request.applyRefundAmount() == null ? BigDecimal.ZERO : request.applyRefundAmount(),
                request.applyRefundQuantity() == null ? 0 : request.applyRefundQuantity(),
                request.reason(),
                request.images() == null ? null : String.join(",", request.images()),
                idempotencyKey);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ApiErrorCode.IDEMPOTENCY_CONFLICT, "退款单 Idempotency-Key 已存在");
        }
        // 标记订单存在部分售后（has_partial_aftersale=true），但保留原主状态（v1.2）
        jdbcTemplate.update("UPDATE ord_order SET has_partial_aftersale=TRUE, update_time=CURRENT_TIMESTAMP WHERE id=?",
            request.orderId());
        jdbcTemplate.update("INSERT INTO ord_order_timeline(order_id, event) VALUES (?, ?)",
            request.orderId(), "提交售后申请：" + refundNo);
        outboxEventService.addOutbox("sales.refund.applied", "REFUND_APPLIED", refundId,
            Map.of("refundId", refundId, "refundNo", refundNo, "orderId", request.orderId()));
        return loadRefund(refundId);
    }

    public Map<String, Object> merchantList(Integer status, String refundNo, String orderNo,
                                            LocalDateTime startDate, LocalDateTime endDate,
                                            int pageNum, int pageSize) {
        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null) { where.append(" AND r.status=?"); args.add(status); }
        if (StringUtils.hasText(refundNo)) { where.append(" AND r.refund_no ILIKE ?"); args.add("%" + refundNo + "%"); }
        if (StringUtils.hasText(orderNo)) {
            where.append(" AND r.order_id IN (SELECT id FROM ord_order WHERE order_no ILIKE ?)");
            args.add("%" + orderNo + "%");
        }
        if (startDate != null) { where.append(" AND r.applied_at >= ?"); args.add(startDate); }
        if (endDate != null) { where.append(" AND r.applied_at < ?"); args.add(endDate); }
        Long total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ref_refund r " + where, Long.class, args.toArray());
        args.add(pageSize); args.add((long) (pageNum - 1) * pageSize);
        List<Map<String, Object>> list = jdbcTemplate.query(
            "SELECT r.*, o.order_no FROM ref_refund r JOIN ord_order o ON o.id=r.order_id " + where +
                " ORDER BY r.applied_at DESC LIMIT ? OFFSET ?",
            this::refundRow, args.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("list", list);
        return result;
    }

    public Map<String, Object> myList(Integer status, String refundNo, String orderNo,
                                     LocalDateTime startDate, LocalDateTime endDate,
                                     int pageNum, int pageSize) {
        Long customerId = currentUser.requireUserId();
        StringBuilder where = new StringBuilder("WHERE r.customer_id=?");
        List<Object> args = new ArrayList<>();
        args.add(customerId);
        if (status != null) { where.append(" AND r.status=?"); args.add(status); }
        if (StringUtils.hasText(refundNo)) { where.append(" AND r.refund_no ILIKE ?"); args.add("%" + refundNo + "%"); }
        if (StringUtils.hasText(orderNo)) {
            where.append(" AND r.order_id IN (SELECT id FROM ord_order WHERE order_no ILIKE ?)");
            args.add("%" + orderNo + "%");
        }
        if (startDate != null) { where.append(" AND r.applied_at >= ?"); args.add(startDate); }
        if (endDate != null) { where.append(" AND r.applied_at < ?"); args.add(endDate); }
        Long total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ref_refund r " + where, Long.class, args.toArray());
        args.add(pageSize); args.add((long) (pageNum - 1) * pageSize);
        List<Map<String, Object>> list = jdbcTemplate.query(
            "SELECT r.*, o.order_no FROM ref_refund r JOIN ord_order o ON o.id=r.order_id " + where +
                " ORDER BY r.applied_at DESC LIMIT ? OFFSET ?",
            this::refundRow, args.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("list", list);
        return result;
    }

    public RefundResponse getDetail(Long refundId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
            "SELECT r.*, o.order_no FROM ref_refund r JOIN ord_order o ON o.id=r.order_id WHERE r.id=?",
            this::refundRow, refundId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "退款单不存在");
        }
        return toResponse(rows.getFirst());
    }

    @Transactional
    public RefundResponse audit(Long refundId, RefundAuditRequest request) {
        if (request.auditStatus() == null || (request.auditStatus() != 1 && request.auditStatus() != 2)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "auditStatus 只能是 1/2");
        }
        List<Refund> rows = refundMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Refund>()
                .eq("id", refundId));
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "退款单不存在");
        }
        Refund refund = rows.getFirst();
        if (refund.getStatus() != 0) {
            throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID, "只有 PENDING 状态可审核");
        }
        if (request.auditStatus() == 2 && !StringUtils.hasText(request.auditRemark())) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "驳回必须填 auditRemark");
        }
        int changed;
        if (request.auditStatus() == 1) {
            if (request.approvedAmount() == null
                || refund.getApplyRefundAmount() != null
                    && request.approvedAmount().compareTo(refund.getApplyRefundAmount()) > 0) {
                throw new BusinessException(ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                    "approvedAmount 不能为空且不得超过 applyRefundAmount");
            }
            if (refund.getType() == 2 && (request.approvedQuantity() == null || request.approvedQuantity() <= 0)) {
                throw new BusinessException(ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                    "退货退款必须填写 approvedQuantity");
            }
            changed = jdbcTemplate.update("""
                UPDATE ref_refund SET status=1, approved_amount=?, approved_quantity=?, audit_remark=?,
                    audited_at=CURRENT_TIMESTAMP, update_time=CURRENT_TIMESTAMP
                WHERE id=? AND status=0
                """, request.approvedAmount(), request.approvedQuantity(), request.auditRemark(), refundId);
        } else {
            changed = jdbcTemplate.update("""
                UPDATE ref_refund SET status=2, audit_remark=?, audited_at=CURRENT_TIMESTAMP, update_time=CURRENT_TIMESTAMP
                WHERE id=? AND status=0
                """, request.auditRemark(), refundId);
        }
        if (changed == 0) {
            throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID, "状态机非法转换");
        }
        outboxEventService.addOutbox("sales.refund.audited", "REFUND_AUDITED", refundId,
            Map.of("refundId", refundId, "auditStatus", request.auditStatus()));
        return getDetail(refundId);
    }

    @Transactional
    public RefundResponse complete(Long refundId, RefundCompleteRequest request) {
        int changed = jdbcTemplate.update("""
            UPDATE ref_refund SET status=3, actual_refund_amount=?, provider_refund_no=?, restock=?,
                completed_at=CURRENT_TIMESTAMP, update_time=CURRENT_TIMESTAMP
            WHERE id=? AND status=1
            """, request.actualRefundAmount(), request.providerRefundNo(), request.restock(), refundId);
        if (changed == 0) {
            throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID, "只有 APPROVED 状态可执行退款");
        }
        // 标记 COMPLETED
        jdbcTemplate.update("""
            UPDATE ref_refund SET status=4, update_time=CURRENT_TIMESTAMP WHERE id=? AND status=3
            """, refundId);
        // 退库存：restock=true 回补；false 仅记录
        if (Boolean.TRUE.equals(request.restock())) {
            List<String> reservations = jdbcTemplate.queryForList(
                "SELECT DISTINCT reservation_id FROM sto_stock_reservation WHERE order_no=" +
                    "(SELECT order_no FROM ord_order o JOIN ref_refund r ON r.order_id=o.id WHERE r.id=?) LIMIT 1",
                String.class, refundId);
            for (String reservationId : reservations) {
                try {
                    stockReservationService.restock(reservationId, request.providerRefundNo());
                } catch (Exception ignored) {
                }
            }
        }
        outboxEventService.addOutbox("sales.refund.completed", "REFUND_COMPLETED", refundId,
            Map.of("refundId", refundId, "restock", request.restock(),
                "actualRefundAmount", request.actualRefundAmount()));
        return getDetail(refundId);
    }

    @Transactional
    public void cancel(Long refundId) {
        Long userId = currentUser.requireUserId();
        int changed = jdbcTemplate.update("""
            UPDATE ref_refund SET status=5, update_time=CURRENT_TIMESTAMP
            WHERE id=? AND status=0 AND customer_id=?
            """, refundId, userId);
        if (changed == 0) {
            throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID, "仅本人 PENDING 单可取消");
        }
    }

    private RefundResponse loadRefund(Long refundId) {
        return getDetail(refundId);
    }

    private Map<String, Object> refundRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("refundId", rs.getLong("id"));
        row.put("refundNo", rs.getString("refund_no"));
        row.put("orderId", rs.getLong("order_id"));
        row.put("orderNo", rs.getString("order_no"));
        row.put("orderItemId", rs.getLong("order_item_id"));
        row.put("customerId", rs.getLong("customer_id"));
        row.put("type", rs.getInt("type"));
        row.put("status", rs.getInt("status"));
        row.put("applyRefundAmount", rs.getBigDecimal("apply_refund_amount"));
        row.put("applyRefundQuantity", rs.getInt("apply_refund_quantity"));
        row.put("approvedAmount", rs.getBigDecimal("approved_amount"));
        row.put("approvedQuantity", rs.getObject("approved_quantity") == null ? null : rs.getInt("approved_quantity"));
        row.put("actualRefundAmount", rs.getBigDecimal("actual_refund_amount"));
        row.put("providerRefundNo", rs.getString("provider_refund_no"));
        row.put("restock", rs.getBoolean("restock"));
        row.put("reason", rs.getString("reason"));
        row.put("auditRemark", rs.getString("audit_remark"));
        row.put("appliedAt", rs.getTimestamp("applied_at"));
        row.put("auditedAt", rs.getTimestamp("audited_at"));
        row.put("completedAt", rs.getTimestamp("completed_at"));
        String images = rs.getString("images");
        row.put("images", images == null || images.isBlank() ? List.of() : List.of(images.split(",")));
        return row;
    }

    private RefundResponse toResponse(Map<String, Object> row) {
        Integer type = (Integer) row.get("type");
        Integer status = (Integer) row.get("status");
        String typeText = switch (type) {
            case 1 -> "仅退款";
            case 2 -> "退货退款";
            case 3 -> "换货";
            default -> "未知";
        };
        String statusText = switch (status) {
            case 0 -> "待审核";
            case 1 -> "已审核通过";
            case 2 -> "已驳回";
            case 3 -> "退款中";
            case 4 -> "退款完成";
            case 5 -> "售后取消";
            default -> "未知";
        };
        List<RefundItemResponse> items = jdbcTemplate.query(
            "SELECT sku_id, warehouse_id, location_id, quantity FROM ref_refund_item WHERE refund_id=?",
            (rs, rowNum) -> new RefundItemResponse(
                rs.getLong("sku_id"),
                rs.getObject("warehouse_id") == null ? null : rs.getLong("warehouse_id"),
                rs.getObject("location_id") == null ? null : rs.getLong("location_id"),
                rs.getInt("quantity")),
            (Long) row.get("refundId"));
        return new RefundResponse(
            (Long) row.get("refundId"),
            (String) row.get("refundNo"),
            (Long) row.get("orderId"),
            (Long) row.get("orderItemId"),
            type,
            typeText,
            (BigDecimal) row.get("applyRefundAmount"),
            (Integer) row.get("applyRefundQuantity"),
            (BigDecimal) row.get("approvedAmount"),
            (BigDecimal) row.get("actualRefundAmount"),
            status,
            statusText,
            (String) row.get("reason"),
            (List<String>) row.get("images"),
            toLocalDateTime(row.get("appliedAt")),
            (Boolean) row.get("restock"),
            (String) row.get("providerRefundNo"),
            toLocalDateTime(row.get("completedAt")),
            (String) row.get("auditRemark"),
            items);
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        return BigDecimal.ZERO;
    }

    private String businessNo(String prefix) {
        return prefix + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }
}