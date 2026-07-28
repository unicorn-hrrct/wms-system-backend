package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.dto.AftersaleApplyRequest;
import com.example.demo.dto.AftersaleAuditRequest;
import com.example.demo.exception.BusinessException;
import com.example.demo.security.CurrentUserProvider;
import com.example.demo.vo.AftersaleResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AftersaleService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;
    private final OutboxEventService outboxEventService;

    public AftersaleService(JdbcTemplate jdbcTemplate,
                            CurrentUserProvider currentUser,
                            ObjectMapper objectMapper,
                            OutboxEventService outboxEventService) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.outboxEventService = outboxEventService;
    }

    @Transactional
    public AftersaleResponse apply(AftersaleApplyRequest request, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "缺少 Idempotency-Key 请求头");
        }
        Long userId = currentUser.requireUserId();
        Map<String, Object> order = requireOwnedOrderItem(request.orderId(), request.orderItemId(), userId);
        validateOrderState((Integer) order.get("status"));
        BigDecimal applyRefundAmount = normalizeAmount(request.applyRefundAmount());
        Integer applyRefundQuantity = normalizeQuantity(request.applyRefundQuantity());
        validateRefundLimit(request.orderItemId(), (BigDecimal) order.get("price"), (Integer) order.get("quantity"),
            applyRefundAmount, applyRefundQuantity);

        Long customerId = ((Number) order.get("customerId")).longValue();
        String aftersaleNo = businessNo("AS");
        Long aftersaleId;
        try {
            aftersaleId = jdbcTemplate.queryForObject("""
                INSERT INTO ord_aftersale(aftersale_no, order_id, order_item_id, user_id, customer_id, type,
                                          reason, images, remark, status, apply_refund_amount,
                                          apply_refund_quantity, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?) RETURNING id
                """, Long.class, aftersaleNo, request.orderId(), request.orderItemId(), userId, customerId,
                request.type(), request.reason(), jsonImages(request.images()), request.remark(),
                applyRefundAmount, applyRefundQuantity, idempotencyKey);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ApiErrorCode.IDEMPOTENCY_CONFLICT, "售后单 Idempotency-Key 已存在");
        }

        jdbcTemplate.update("UPDATE ord_order SET has_partial_aftersale=TRUE, update_time=CURRENT_TIMESTAMP WHERE id=?",
            request.orderId());
        jdbcTemplate.update("INSERT INTO ord_order_timeline(order_id, event) VALUES (?, ?)",
            request.orderId(), "提交售后申请：" + aftersaleNo);
        outboxEventService.addOutbox("sales.aftersale.applied", "AFTERSALE_APPLIED", aftersaleId,
            Map.of("aftersaleId", aftersaleId, "aftersaleNo", aftersaleNo, "orderId", request.orderId()));
        return getDetail(aftersaleId);
    }

    public Map<String, Object> merchantList(Integer status, String aftersaleNo, String orderNo,
                                            LocalDateTime startDate, LocalDateTime endDate,
                                            int pageNum, int pageSize) {
        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            where.append(" AND a.status=?");
            args.add(status);
        }
        if (StringUtils.hasText(aftersaleNo)) {
            where.append(" AND a.aftersale_no ILIKE ?");
            args.add("%" + aftersaleNo.trim() + "%");
        }
        if (StringUtils.hasText(orderNo)) {
            where.append(" AND o.order_no ILIKE ?");
            args.add("%" + orderNo.trim() + "%");
        }
        if (startDate != null) {
            where.append(" AND a.create_time >= ?");
            args.add(startDate);
        }
        if (endDate != null) {
            where.append(" AND a.create_time < ?");
            args.add(endDate);
        }
        Long total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ord_aftersale a JOIN ord_order o ON o.id=a.order_id " + where,
            Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) (pageNum - 1) * pageSize);
        List<AftersaleResponse> list = jdbcTemplate.query(baseSelect() + " " + where +
                " ORDER BY a.create_time DESC, a.id DESC LIMIT ? OFFSET ?",
            this::aftersaleRow, pageArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        long safeTotal = total == null ? 0 : total;
        result.put("total", safeTotal);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        result.put("pages", (safeTotal + pageSize - 1) / pageSize);
        result.put("list", list);
        return result;
    }

    public AftersaleResponse getDetail(Long aftersaleId) {
        List<AftersaleResponse> rows = jdbcTemplate.query(baseSelect() + " WHERE a.id=?",
            this::aftersaleRow, aftersaleId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "售后单不存在");
        }
        return rows.getFirst();
    }

    @Transactional
    public AftersaleResponse audit(Long aftersaleId, AftersaleAuditRequest request) {
        if (request.auditStatus() == null || (request.auditStatus() != 1 && request.auditStatus() != 2)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "auditStatus 只能是 1/2");
        }
        Map<String, Object> aftersale = requireAftersaleForUpdate(aftersaleId);
        Integer status = ((Number) aftersale.get("status")).intValue();
        if (status != 0) {
            throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID, "只有待审核售后单可审核");
        }
        if (request.auditStatus() == 2 && !StringUtils.hasText(request.auditRemark())) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "驳回必须填写 auditRemark");
        }
        if (request.auditStatus() == 1) {
            BigDecimal applyAmount = (BigDecimal) aftersale.get("applyRefundAmount");
            BigDecimal approvedAmount = request.approvedAmount() == null ? applyAmount : request.approvedAmount();
            if (approvedAmount == null) {
                approvedAmount = BigDecimal.ZERO;
            }
            if (applyAmount != null && approvedAmount.compareTo(applyAmount) > 0) {
                throw new BusinessException(ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                    "approvedAmount 不得超过 applyRefundAmount");
            }
            Integer applyQuantity = ((Number) aftersale.get("applyRefundQuantity")).intValue();
            Integer approvedQuantity = request.approvedQuantity() == null ? applyQuantity : request.approvedQuantity();
            Integer type = ((Number) aftersale.get("type")).intValue();
            if (type == 2 && (approvedQuantity == null || approvedQuantity <= 0)) {
                throw new BusinessException(ApiErrorCode.REFUND_AMOUNT_EXCEEDED, "退货退款必须填写 approvedQuantity");
            }
            int changed = jdbcTemplate.update("""
                UPDATE ord_aftersale
                SET status=1, approved_amount=?, approved_quantity=?, audit_remark=?,
                    audited_at=CURRENT_TIMESTAMP, update_time=CURRENT_TIMESTAMP
                WHERE id=? AND status=0
                """, approvedAmount, approvedQuantity, request.auditRemark(), aftersaleId);
            if (changed == 0) {
                throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID, "售后状态已变更");
            }
            Long refundId = createRefundIfNecessary(aftersaleId, aftersale, approvedAmount, approvedQuantity,
                request.auditRemark());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("aftersaleId", aftersaleId);
            payload.put("auditStatus", request.auditStatus());
            payload.put("refundId", refundId);
            outboxEventService.addOutbox("sales.aftersale.audited", "AFTERSALE_AUDITED", aftersaleId, payload);
        } else {
            int changed = jdbcTemplate.update("""
                UPDATE ord_aftersale
                SET status=2, audit_remark=?, audited_at=CURRENT_TIMESTAMP, update_time=CURRENT_TIMESTAMP
                WHERE id=? AND status=0
                """, request.auditRemark(), aftersaleId);
            if (changed == 0) {
                throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID, "售后状态已变更");
            }
            outboxEventService.addOutbox("sales.aftersale.audited", "AFTERSALE_AUDITED", aftersaleId,
                Map.of("aftersaleId", aftersaleId, "auditStatus", request.auditStatus()));
        }
        return getDetail(aftersaleId);
    }

    private Long createRefundIfNecessary(Long aftersaleId, Map<String, Object> aftersale,
                                         BigDecimal approvedAmount, Integer approvedQuantity,
                                         String auditRemark) {
        Long existingRefundId = (Long) aftersale.get("refundId");
        if (existingRefundId != null) {
            return existingRefundId;
        }
        Integer type = ((Number) aftersale.get("type")).intValue();
        if (type == 3) {
            return null;
        }
        String refundNo = businessNo("RF");
        Long refundId = jdbcTemplate.queryForObject("""
            INSERT INTO ref_refund(refund_no, order_id, order_item_id, customer_id, type, status,
                                   apply_refund_amount, apply_refund_quantity, approved_amount,
                                   approved_quantity, reason, images, audit_remark, audited_at,
                                   idempotency_key)
            VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?) RETURNING id
            """, Long.class, refundNo, aftersale.get("orderId"), aftersale.get("orderItemId"),
            aftersale.get("customerId"), type, aftersale.get("applyRefundAmount"),
            aftersale.get("applyRefundQuantity"), approvedAmount, approvedQuantity,
            aftersale.get("reason"), aftersale.get("images"), auditRemark,
            "aftersale:" + aftersaleId);
        jdbcTemplate.update("UPDATE ord_aftersale SET refund_id=?, update_time=CURRENT_TIMESTAMP WHERE id=?",
            refundId, aftersaleId);
        outboxEventService.addOutbox("sales.refund.audited", "REFUND_AUDITED", refundId,
            Map.of("refundId", refundId, "refundNo", refundNo, "aftersaleId", aftersaleId, "auditStatus", 1));
        return refundId;
    }

    private Map<String, Object> requireOwnedOrderItem(Long orderId, Long orderItemId, Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
            SELECT o.id order_id, o.order_no, o.status, o.customer_id, oi.id order_item_id,
                   oi.price, oi.quantity
            FROM ord_order o
            JOIN ord_order_item oi ON oi.order_id=o.id
            WHERE o.id=? AND o.user_id=? AND oi.id=?
            """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderId", rs.getLong("order_id"));
            row.put("orderNo", rs.getString("order_no"));
            row.put("status", rs.getInt("status"));
            row.put("customerId", rs.getLong("customer_id"));
            row.put("orderItemId", rs.getLong("order_item_id"));
            row.put("price", rs.getBigDecimal("price"));
            row.put("quantity", rs.getInt("quantity"));
            return row;
        }, orderId, userId, orderItemId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.RESOURCE_FORBIDDEN, "订单或订单项不属于当前用户");
        }
        return rows.getFirst();
    }

    private void validateOrderState(Integer status) {
        if (status == null || (status != 2 && status != 3 && status != 5)) {
            throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID, "只有已发货/已完成/售后中订单可申请售后");
        }
    }

    private void validateRefundLimit(Long orderItemId, BigDecimal price, Integer quantity,
                                     BigDecimal applyRefundAmount, Integer applyRefundQuantity) {
        BigDecimal maxRefund = price.multiply(BigDecimal.valueOf(quantity));
        if (applyRefundAmount != null && applyRefundAmount.compareTo(maxRefund) > 0) {
            throw new BusinessException(ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "退款金额 " + applyRefundAmount + " 超过订单项可退金额 " + maxRefund);
        }
        if (applyRefundQuantity != null && applyRefundQuantity > quantity) {
            throw new BusinessException(ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "退款数量 " + applyRefundQuantity + " 超过订单项数量 " + quantity);
        }
        Map<String, Object> sum = jdbcTemplate.queryForMap("""
            SELECT COALESCE((
                       SELECT SUM(COALESCE(actual_refund_amount, approved_amount, apply_refund_amount, 0)) FROM ref_refund
                       WHERE order_item_id=? AND status IN (1,3,4)
                   ), 0) + COALESCE((
                       SELECT SUM(apply_refund_amount) FROM ord_aftersale
                       WHERE order_item_id=? AND status IN (0,1)
                   ), 0) reserved_amount,
                   COALESCE((
                       SELECT SUM(COALESCE(approved_quantity, apply_refund_quantity, 0)) FROM ref_refund
                       WHERE order_item_id=? AND status IN (1,3,4)
                   ), 0) + COALESCE((
                       SELECT SUM(apply_refund_quantity) FROM ord_aftersale
                       WHERE order_item_id=? AND status IN (0,1)
                   ), 0) reserved_qty
            """, orderItemId, orderItemId, orderItemId, orderItemId);
        BigDecimal reservedAmount = toBigDecimal(sum.get("reserved_amount"));
        BigDecimal reservedQty = toBigDecimal(sum.get("reserved_qty"));
        if (applyRefundAmount != null && reservedAmount.add(applyRefundAmount).compareTo(maxRefund) > 0) {
            throw new BusinessException(ApiErrorCode.REFUND_AMOUNT_EXCEEDED, "累计退款金额超过订单项可退金额");
        }
        if (applyRefundQuantity != null
            && reservedQty.add(BigDecimal.valueOf(applyRefundQuantity)).compareTo(BigDecimal.valueOf(quantity)) > 0) {
            throw new BusinessException(ApiErrorCode.REFUND_AMOUNT_EXCEEDED, "累计退款数量超过订单项数量");
        }
    }

    private Map<String, Object> requireAftersaleForUpdate(Long aftersaleId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
            SELECT id aftersale_id, aftersale_no, order_id, order_item_id, user_id, customer_id, type,
                   status, apply_refund_amount, apply_refund_quantity, reason, images, remark,
                   audit_remark, refund_id
            FROM ord_aftersale
            WHERE id=? FOR UPDATE
            """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("aftersaleId", rs.getLong("aftersale_id"));
            row.put("aftersaleNo", rs.getString("aftersale_no"));
            row.put("orderId", rs.getLong("order_id"));
            row.put("orderItemId", rs.getLong("order_item_id"));
            row.put("userId", rs.getLong("user_id"));
            row.put("customerId", rs.getLong("customer_id"));
            row.put("type", rs.getInt("type"));
            row.put("status", rs.getInt("status"));
            row.put("applyRefundAmount", rs.getBigDecimal("apply_refund_amount"));
            row.put("applyRefundQuantity", rs.getInt("apply_refund_quantity"));
            row.put("reason", rs.getString("reason"));
            row.put("images", rs.getString("images"));
            row.put("remark", rs.getString("remark"));
            row.put("auditRemark", rs.getString("audit_remark"));
            row.put("refundId", rs.getObject("refund_id") == null ? null : rs.getLong("refund_id"));
            return row;
        }, aftersaleId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "售后单不存在");
        }
        return rows.getFirst();
    }

    private String baseSelect() {
        return """
            SELECT a.id aftersale_id, a.aftersale_no, a.order_id, o.order_no, a.order_item_id,
                   a.user_id, a.customer_id, c.nickname customer_name, a.type, a.status,
                   a.apply_refund_amount, a.apply_refund_quantity, a.approved_amount,
                   a.approved_quantity, a.reason, a.images, a.remark, a.audit_remark,
                   a.create_time, a.audited_at, a.update_time, a.refund_id, r.refund_no,
                   oi.sku_id, oi.sku_code, oi.product_name, oi.spec_values, oi.main_image,
                   oi.price, oi.quantity, oi.subtotal
            FROM ord_aftersale a
            JOIN ord_order o ON o.id=a.order_id
            JOIN ord_order_item oi ON oi.id=a.order_item_id
            JOIN crm_customer c ON c.id=a.customer_id
            LEFT JOIN ref_refund r ON r.id=a.refund_id
            """;
    }

    private AftersaleResponse aftersaleRow(ResultSet rs, int rowNum) throws SQLException {
        Integer type = rs.getInt("type");
        Integer status = rs.getInt("status");
        Long refundId = rs.getObject("refund_id") == null ? null : rs.getLong("refund_id");
        String refundNo = rs.getString("refund_no");
        Map<String, Object> orderItem = new LinkedHashMap<>();
        orderItem.put("skuId", rs.getLong("sku_id"));
        orderItem.put("skuCode", rs.getString("sku_code"));
        orderItem.put("productName", rs.getString("product_name"));
        orderItem.put("specValues", parseJsonMap(rs.getString("spec_values")));
        orderItem.put("mainImage", rs.getString("main_image"));
        orderItem.put("price", rs.getBigDecimal("price"));
        orderItem.put("quantity", rs.getInt("quantity"));
        orderItem.put("subtotal", rs.getBigDecimal("subtotal"));
        return new AftersaleResponse(
            rs.getLong("aftersale_id"),
            rs.getString("aftersale_no"),
            rs.getLong("order_id"),
            rs.getString("order_no"),
            rs.getLong("order_item_id"),
            rs.getLong("user_id"),
            rs.getLong("customer_id"),
            rs.getString("customer_name"),
            type,
            typeText(type),
            status,
            statusText(status),
            rs.getBigDecimal("apply_refund_amount"),
            rs.getInt("apply_refund_quantity"),
            rs.getBigDecimal("approved_amount"),
            rs.getObject("approved_quantity") == null ? null : rs.getInt("approved_quantity"),
            rs.getString("reason"),
            parseImages(rs.getString("images")),
            rs.getString("remark"),
            rs.getString("audit_remark"),
            toLocalDateTime(rs.getTimestamp("create_time")),
            toLocalDateTime(rs.getTimestamp("audited_at")),
            toLocalDateTime(rs.getTimestamp("update_time")),
            refundId == null ? rs.getLong("aftersale_id") : refundId,
            refundNo == null ? rs.getString("aftersale_no") : refundNo,
            orderItem);
    }

    private String jsonImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(images);
        } catch (Exception ex) {
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR, "图片数据序列化失败");
        }
    }

    private List<String> parseImages(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            return List.of(value.split(","));
        }
    }

    private Map<String, Object> parseJsonMap(String value) {
        if (!StringUtils.hasText(value)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of("raw", value);
        }
    }

    private LocalDateTime toLocalDateTime(java.sql.Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private BigDecimal normalizeAmount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Integer normalizeQuantity(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return BigDecimal.ZERO;
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
            case 1 -> "已通过";
            case 2 -> "已驳回";
            default -> "未知";
        };
    }

    private String businessNo(String prefix) {
        return prefix + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
