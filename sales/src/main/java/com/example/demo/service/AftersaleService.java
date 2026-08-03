package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.dto.AftersaleApplyRequest;
import com.example.demo.dto.AftersaleAuditRequest;
import com.example.demo.exception.BusinessException;
import com.example.demo.json.RefundTimeJsonCodec;
import com.example.demo.security.CurrentUserProvider;
import com.example.demo.security.CustomerIdProvider;
import com.example.demo.vo.AftersaleResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v1.2 §7.9 售后申请与审核。
 *
 * <p>申请只创建 PENDING 售后单；仅退款/退货退款在商家审核通过后创建
 * APPROVED 退款单。换货后续处理尚无冻结契约，因此审核后不创建退款单。</p>
 */
@Service
public class AftersaleService {

    private static final TypeReference<AftersaleResponse> AFTERSALE_RESPONSE =
        new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserProvider currentUser;
    private final CustomerIdProvider customerIdProvider;
    private final ObjectMapper objectMapper;
    private final OutboxEventService outboxEventService;
    private final SalesIdempotencyService idempotencyService;

    public AftersaleService(JdbcTemplate jdbcTemplate,
                            CurrentUserProvider currentUser,
                            CustomerIdProvider customerIdProvider,
                            ObjectMapper objectMapper,
                            OutboxEventService outboxEventService,
                            SalesIdempotencyService idempotencyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
        this.customerIdProvider = customerIdProvider;
        this.objectMapper = objectMapper;
        this.outboxEventService = outboxEventService;
        this.idempotencyService = idempotencyService;
    }

    @Transactional
    public AftersaleResponse apply(
        AftersaleApplyRequest request, String idempotencyKey) {
        long userId = currentUser.requireUserId();
        long customerId = customerIdProvider.requireCurrentCustomerIdForUpdate();
        AftersaleApplyCommand command = new AftersaleApplyCommand(request);
        return idempotencyService.execute(
            "aftersale:apply",
            customerId,
            idempotencyKey,
            command,
            AFTERSALE_RESPONSE,
            () -> applyAftersale(userId, customerId, request));
    }

    private AftersaleResponse applyAftersale(
        long userId, long customerId, AftersaleApplyRequest request) {
        validateApplyRequest(request);
        Map<String, Object> order = requireOwnedOrderItem(
            request.orderId(), request.orderItemId(), userId, customerId);
        validateOrderState((Integer) order.get("status"));

        BigDecimal applyRefundAmount = normalizeAmount(request.applyRefundAmount());
        int applyRefundQuantity = normalizeQuantity(request.applyRefundQuantity());
        BigDecimal itemAmount = (BigDecimal) order.get("subtotal");
        if (itemAmount == null) {
            itemAmount = ((BigDecimal) order.get("price"))
                .multiply(BigDecimal.valueOf((Integer) order.get("quantity")));
        }
        validateRefundLimit(
            request.orderItemId(),
            itemAmount,
            (Integer) order.get("quantity"),
            applyRefundAmount,
            applyRefundQuantity);

        String aftersaleNo = businessNo("AS");
        LocalDateTime appliedAt = nowInRefundZone();
        Long aftersaleId = jdbcTemplate.queryForObject("""
            INSERT INTO ord_aftersale(
                aftersale_no, order_id, order_item_id, user_id, customer_id, type,
                reason, images, remark, status, apply_refund_amount,
                apply_refund_quantity, create_time, update_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
            RETURNING id
            """, Long.class,
            aftersaleNo,
            request.orderId(),
            request.orderItemId(),
            userId,
            customerId,
            request.type(),
            request.reason(),
            jsonImages(request.images()),
            request.remark(),
            applyRefundAmount,
            applyRefundQuantity,
            appliedAt,
            appliedAt);
        if (aftersaleId == null) {
            throw new BusinessException(
                ApiErrorCode.INTERNAL_SERVER_ERROR, "售后单创建失败");
        }

        jdbcTemplate.update("""
            UPDATE ord_order
            SET has_partial_aftersale=TRUE, update_time=CURRENT_TIMESTAMP
            WHERE id=? AND user_id=? AND customer_id=?
            """, request.orderId(), userId, customerId);
        jdbcTemplate.update(
            "INSERT INTO ord_order_timeline(order_id, event) VALUES (?, ?)",
            request.orderId(), "提交售后申请：" + aftersaleNo);
        outboxEventService.addOutbox(
            "sales.aftersale.applied",
            "AFTERSALE_APPLIED",
            aftersaleId,
            Map.of(
                "aftersaleId", aftersaleId,
                "aftersaleNo", aftersaleNo,
                "orderId", request.orderId(),
                "orderItemId", request.orderItemId(),
                "customerId", customerId));
        return getDetail(aftersaleId);
    }

    public Map<String, Object> merchantList(
        Integer status,
        String aftersaleNo,
        String orderNo,
        LocalDateTime startDate,
        LocalDateTime endDate,
        int pageNum,
        int pageSize) {
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
            "SELECT COUNT(*) FROM ord_aftersale a "
                + "JOIN ord_order o ON o.id=a.order_id " + where,
            Long.class,
            args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) (pageNum - 1) * pageSize);
        List<AftersaleResponse> list = jdbcTemplate.query(
            baseSelect() + " " + where
                + " ORDER BY a.create_time DESC, a.id DESC LIMIT ? OFFSET ?",
            this::aftersaleRow,
            pageArgs.toArray());
        return page(total == null ? 0 : total, pageNum, pageSize, list);
    }

    public AftersaleResponse getDetail(Long aftersaleId) {
        List<AftersaleResponse> rows = jdbcTemplate.query(
            baseSelect() + " WHERE a.id=?",
            this::aftersaleRow,
            aftersaleId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "售后单不存在");
        }
        return rows.getFirst();
    }

    @Transactional
    public AftersaleResponse audit(
        Long aftersaleId,
        AftersaleAuditRequest request,
        String idempotencyKey) {
        long userId = currentUser.requireUserId();
        AftersaleAuditCommand command =
            new AftersaleAuditCommand(aftersaleId, request);
        return idempotencyService.executeForUser(
            "aftersale:audit",
            userId,
            idempotencyKey,
            command,
            AFTERSALE_RESPONSE,
            () -> auditAftersale(aftersaleId, request));
    }

    private AftersaleResponse auditAftersale(
        Long aftersaleId, AftersaleAuditRequest request) {
        validateAuditStatus(request);
        Map<String, Object> aftersale = requireAftersaleForUpdate(aftersaleId);
        int status = ((Number) aftersale.get("status")).intValue();
        if (status != 0) {
            throw new BusinessException(
                ApiErrorCode.ORDER_STATE_INVALID, "只有待审核售后单可审核");
        }

        LocalDateTime auditedAt = nowInRefundZone();
        Long refundId = null;
        if (request.auditStatus() == 1) {
            Approval approval = validateApproval(aftersale, request);
            int changed = jdbcTemplate.update("""
                UPDATE ord_aftersale
                SET status=1, approved_amount=?, approved_quantity=?,
                    audit_remark=?, audited_at=?, update_time=?
                WHERE id=? AND status=0
                """,
                approval.amount(),
                approval.quantity(),
                request.auditRemark(),
                auditedAt,
                auditedAt,
                aftersaleId);
            if (changed == 0) {
                throw new BusinessException(
                    ApiErrorCode.ORDER_STATE_INVALID, "售后状态已变更");
            }
            refundId = createRefundIfNecessary(
                aftersaleId,
                aftersale,
                approval.amount(),
                approval.quantity(),
                request.auditRemark(),
                auditedAt);
        } else {
            int changed = jdbcTemplate.update("""
                UPDATE ord_aftersale
                SET status=2, audit_remark=?, audited_at=?, update_time=?
                WHERE id=? AND status=0
                """,
                request.auditRemark(),
                auditedAt,
                auditedAt,
                aftersaleId);
            if (changed == 0) {
                throw new BusinessException(
                    ApiErrorCode.ORDER_STATE_INVALID, "售后状态已变更");
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("aftersaleId", aftersaleId);
        payload.put("auditStatus", request.auditStatus());
        payload.put("refundId", refundId);
        outboxEventService.addOutbox(
            "sales.aftersale.audited",
            "AFTERSALE_AUDITED",
            aftersaleId,
            payload);
        return getDetail(aftersaleId);
    }

    private Long createRefundIfNecessary(
        Long aftersaleId,
        Map<String, Object> aftersale,
        BigDecimal approvedAmount,
        Integer approvedQuantity,
        String auditRemark,
        LocalDateTime auditedAt) {
        Long existingRefundId = nullableLong(aftersale.get("refundId"));
        if (existingRefundId != null) {
            return existingRefundId;
        }
        int type = ((Number) aftersale.get("type")).intValue();
        if (type == 3) {
            return null;
        }

        String refundNo = businessNo("RF");
        Long refundId = jdbcTemplate.queryForObject("""
            INSERT INTO ref_refund(
                refund_no, order_id, order_item_id, customer_id, type, status,
                apply_refund_amount, apply_refund_quantity, approved_amount,
                approved_quantity, reason, images, audit_remark, applied_at,
                audited_at, create_time, update_time)
            VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """, Long.class,
            refundNo,
            aftersale.get("orderId"),
            aftersale.get("orderItemId"),
            aftersale.get("customerId"),
            type,
            aftersale.get("applyRefundAmount"),
            aftersale.get("applyRefundQuantity"),
            approvedAmount,
            approvedQuantity,
            aftersale.get("reason"),
            aftersale.get("images"),
            auditRemark,
            auditedAt,
            auditedAt,
            auditedAt,
            auditedAt);
        if (refundId == null) {
            throw new BusinessException(
                ApiErrorCode.INTERNAL_SERVER_ERROR, "退款单创建失败");
        }

        if (type == 2) {
            jdbcTemplate.update("""
                INSERT INTO ref_refund_item(
                    refund_id, sku_id, warehouse_id, location_id, quantity)
                SELECT ?, sku_id, warehouse_id, location_id, ?
                FROM ord_order_item
                WHERE id=? AND order_id=?
                """,
                refundId,
                approvedQuantity,
                aftersale.get("orderItemId"),
                aftersale.get("orderId"));
        }
        jdbcTemplate.update("""
            UPDATE ord_aftersale
            SET refund_id=?, update_time=?
            WHERE id=? AND status=1 AND refund_id IS NULL
            """, refundId, auditedAt, aftersaleId);
        outboxEventService.addOutbox(
            "sales.refund.audited",
            "REFUND_AUDITED",
            refundId,
            Map.of(
                "refundId", refundId,
                "refundNo", refundNo,
                "aftersaleId", aftersaleId,
                "auditStatus", 1));
        return refundId;
    }

    private Map<String, Object> requireOwnedOrderItem(
        Long orderId, Long orderItemId, long userId, long customerId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
            SELECT o.id order_id, o.order_no, o.status,
                   oi.id order_item_id, oi.price, oi.quantity, oi.subtotal
            FROM ord_order o
            JOIN ord_order_item oi ON oi.order_id=o.id
            WHERE o.id=? AND o.user_id=? AND o.customer_id=? AND oi.id=?
            FOR UPDATE OF oi
            """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderId", rs.getLong("order_id"));
            row.put("orderNo", rs.getString("order_no"));
            row.put("status", rs.getInt("status"));
            row.put("orderItemId", rs.getLong("order_item_id"));
            row.put("price", rs.getBigDecimal("price"));
            row.put("quantity", rs.getInt("quantity"));
            row.put("subtotal", rs.getBigDecimal("subtotal"));
            return row;
        }, orderId, userId, customerId, orderItemId);
        if (rows.isEmpty()) {
            throw new BusinessException(
                ApiErrorCode.RESOURCE_FORBIDDEN,
                "订单或订单项不属于当前客户");
        }
        return rows.getFirst();
    }

    private void validateOrderState(Integer status) {
        if (status == null || (status != 2 && status != 3)) {
            throw new BusinessException(
                ApiErrorCode.ORDER_STATE_INVALID,
                "只有已发货/已完成订单可申请售后");
        }
    }

    private void validateApplyRequest(AftersaleApplyRequest request) {
        if ((request.type() == 1 || request.type() == 2)
            && (request.applyRefundAmount() == null
                || request.applyRefundAmount().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BusinessException(
                ApiErrorCode.BAD_REQUEST,
                "仅退款或退货退款必须填写正数退款金额");
        }
        if (request.type() == 2
            && (request.applyRefundQuantity() == null
                || request.applyRefundQuantity() <= 0)) {
            throw new BusinessException(
                ApiErrorCode.BAD_REQUEST,
                "退货退款必须填写正数退款数量");
        }
    }

    private void validateRefundLimit(
        Long orderItemId,
        BigDecimal itemAmount,
        int itemQuantity,
        BigDecimal applyRefundAmount,
        int applyRefundQuantity) {
        if (applyRefundAmount.compareTo(itemAmount) > 0) {
            throw new BusinessException(
                ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "退款金额超过订单项可退金额");
        }
        if (applyRefundQuantity > itemQuantity) {
            throw new BusinessException(
                ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "退款数量超过订单项数量");
        }

        Map<String, Object> occupied = jdbcTemplate.queryForMap("""
            SELECT
                COALESCE((
                    SELECT SUM(CASE
                        WHEN status=0 THEN apply_refund_amount
                        WHEN status=1 THEN COALESCE(approved_amount, apply_refund_amount)
                        ELSE 0 END)
                    FROM ord_aftersale
                    WHERE order_item_id=?
                      AND status IN (0,1)
                      AND refund_id IS NULL
                ), 0)
                + COALESCE((
                    SELECT SUM(CASE
                        WHEN status=0 THEN apply_refund_amount
                        WHEN status=1 THEN COALESCE(approved_amount, apply_refund_amount)
                        WHEN status IN (3,4)
                            THEN COALESCE(actual_refund_amount, approved_amount, apply_refund_amount)
                        ELSE 0 END)
                    FROM ref_refund
                    WHERE order_item_id=? AND status IN (0,1,3,4)
                ), 0) occupied_amount,
                COALESCE((
                    SELECT SUM(CASE
                        WHEN status=0 THEN apply_refund_quantity
                        WHEN status=1 THEN COALESCE(approved_quantity, apply_refund_quantity)
                        ELSE 0 END)
                    FROM ord_aftersale
                    WHERE order_item_id=?
                      AND status IN (0,1)
                      AND refund_id IS NULL
                ), 0)
                + COALESCE((
                    SELECT SUM(CASE
                        WHEN status=0 THEN apply_refund_quantity
                        WHEN status IN (1,3,4)
                            THEN COALESCE(approved_quantity, apply_refund_quantity)
                        ELSE 0 END)
                    FROM ref_refund
                    WHERE order_item_id=? AND status IN (0,1,3,4)
                ), 0) occupied_quantity
            """, orderItemId, orderItemId, orderItemId, orderItemId);
        BigDecimal occupiedAmount =
            toBigDecimal(occupied.get("occupied_amount"));
        BigDecimal occupiedQuantity =
            toBigDecimal(occupied.get("occupied_quantity"));
        if (occupiedAmount.add(applyRefundAmount).compareTo(itemAmount) > 0) {
            throw new BusinessException(
                ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "累计退款金额超过订单项可退金额");
        }
        if (occupiedQuantity.add(BigDecimal.valueOf(applyRefundQuantity))
            .compareTo(BigDecimal.valueOf(itemQuantity)) > 0) {
            throw new BusinessException(
                ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "累计退款数量超过订单项数量");
        }
    }

    private void validateAuditStatus(AftersaleAuditRequest request) {
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

    private Approval validateApproval(
        Map<String, Object> aftersale, AftersaleAuditRequest request) {
        int type = ((Number) aftersale.get("type")).intValue();
        BigDecimal applyAmount =
            toBigDecimal(aftersale.get("applyRefundAmount"));
        BigDecimal approvedAmount =
            request.approvedAmount() == null
                ? (type == 1 || type == 2 ? applyAmount : null)
                : request.approvedAmount();
        if ((type == 1 || type == 2)
            && (approvedAmount == null
                || approvedAmount.compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BusinessException(
                ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "approvedAmount 必须为正数");
        }
        if (approvedAmount != null
            && approvedAmount.compareTo(applyAmount) > 0) {
            throw new BusinessException(
                ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "approvedAmount 不得超过 applyRefundAmount");
        }

        int applyQuantity =
            ((Number) aftersale.get("applyRefundQuantity")).intValue();
        Integer approvedQuantity =
            request.approvedQuantity() == null
                ? (applyQuantity > 0 ? applyQuantity : null)
                : request.approvedQuantity();
        if (type == 2
            && (approvedQuantity == null
                || approvedQuantity <= 0
                || approvedQuantity > applyQuantity)) {
            throw new BusinessException(
                ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "approvedQuantity 必须为正数且不得超过 applyRefundQuantity");
        }
        if (approvedQuantity != null && approvedQuantity > applyQuantity) {
            throw new BusinessException(
                ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
                "approvedQuantity 不得超过 applyRefundQuantity");
        }
        return new Approval(approvedAmount, approvedQuantity);
    }

    private Map<String, Object> requireAftersaleForUpdate(Long aftersaleId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
            SELECT id aftersale_id, aftersale_no, order_id, order_item_id,
                   user_id, customer_id, type, status, apply_refund_amount,
                   apply_refund_quantity, reason, images, remark,
                   audit_remark, refund_id
            FROM ord_aftersale
            WHERE id=?
            FOR UPDATE
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
            row.put(
                "applyRefundAmount", rs.getBigDecimal("apply_refund_amount"));
            row.put(
                "applyRefundQuantity", rs.getInt("apply_refund_quantity"));
            row.put("reason", rs.getString("reason"));
            row.put("images", rs.getString("images"));
            row.put("remark", rs.getString("remark"));
            row.put("auditRemark", rs.getString("audit_remark"));
            row.put(
                "refundId",
                rs.getObject("refund_id") == null
                    ? null
                    : rs.getLong("refund_id"));
            return row;
        }, aftersaleId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "售后单不存在");
        }
        return rows.getFirst();
    }

    private String baseSelect() {
        return """
            SELECT a.id aftersale_id, a.aftersale_no, a.order_id, o.order_no,
                   a.order_item_id, a.user_id, a.customer_id,
                   c.nickname customer_name, a.type, a.status,
                   a.apply_refund_amount, a.apply_refund_quantity,
                   a.approved_amount, a.approved_quantity, a.reason, a.images,
                   a.remark, a.audit_remark, a.create_time, a.audited_at,
                   a.update_time, a.refund_id, r.refund_no, oi.sku_id,
                   oi.sku_code, oi.product_name, oi.spec_values, oi.main_image,
                   oi.price, oi.quantity, oi.subtotal
            FROM ord_aftersale a
            JOIN ord_order o
              ON o.id=a.order_id AND o.customer_id=a.customer_id
            JOIN ord_order_item oi
              ON oi.id=a.order_item_id AND oi.order_id=a.order_id
            JOIN crm_customer c
              ON c.id=a.customer_id AND c.user_id=a.user_id
            LEFT JOIN ref_refund r ON r.id=a.refund_id
            """;
    }

    private AftersaleResponse aftersaleRow(
        ResultSet rs, int rowNum) throws SQLException {
        int type = rs.getInt("type");
        int status = rs.getInt("status");
        Long refundId =
            rs.getObject("refund_id") == null
                ? null
                : rs.getLong("refund_id");
        Map<String, Object> orderItem = new LinkedHashMap<>();
        orderItem.put("skuId", rs.getLong("sku_id"));
        orderItem.put("skuCode", rs.getString("sku_code"));
        orderItem.put("productName", rs.getString("product_name"));
        orderItem.put(
            "specValues", parseJsonMap(rs.getString("spec_values")));
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
            rs.getObject("approved_quantity") == null
                ? null
                : rs.getInt("approved_quantity"),
            rs.getString("reason"),
            parseImages(rs.getString("images")),
            rs.getString("remark"),
            rs.getString("audit_remark"),
            rs.getObject("create_time", LocalDateTime.class),
            rs.getObject("audited_at", LocalDateTime.class),
            rs.getObject("update_time", LocalDateTime.class),
            refundId,
            rs.getString("refund_no"),
            orderItem);
    }

    private String jsonImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(images);
        } catch (Exception ex) {
            throw new BusinessException(
                ApiErrorCode.INTERNAL_SERVER_ERROR, "图片数据序列化失败");
        }
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

    private Map<String, Object> parseJsonMap(String value) {
        if (!StringUtils.hasText(value)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                value, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of("raw", value);
        }
    }

    private BigDecimal normalizeAmount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int normalizeQuantity(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return BigDecimal.ZERO;
    }

    private Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
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
            case 1 -> "已通过";
            case 2 -> "已驳回";
            default -> "未知";
        };
    }

    private LocalDateTime nowInRefundZone() {
        return RefundTimeJsonCodec.toStorageDateTime(
            OffsetDateTime.now(RefundTimeJsonCodec.REFUND_ZONE));
    }

    private String businessNo(String prefix) {
        return prefix
            + LocalDate.now(RefundTimeJsonCodec.REFUND_ZONE)
                .format(DateTimeFormatter.BASIC_ISO_DATE)
            + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8)
                .toUpperCase();
    }

    private record AftersaleApplyCommand(AftersaleApplyRequest request) {
    }

    private record AftersaleAuditCommand(
        Long aftersaleId, AftersaleAuditRequest request) {
    }

    private record Approval(
        BigDecimal amount, Integer quantity) {
    }
}
