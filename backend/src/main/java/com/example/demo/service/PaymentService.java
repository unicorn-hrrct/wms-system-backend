package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.dto.MockCallbackRequest;
import com.example.demo.dto.PaymentPrepayRequest;
import com.example.demo.entity.PayNonce;
import com.example.demo.exception.BusinessException;
import com.example.demo.mapper.PayNonceMapper;
import com.example.demo.security.CurrentUserProvider;
import com.example.demo.vo.PaymentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v1.2 §7.4 支付服务：
 * <ul>
 *   <li>prepay — 创建预支付单，写 pay_payment(status=0)；返回 payNo + mockPayUrl</li>
 *   <li>mockCallback — Mock 通道回调；HMAC-SHA256 + nonce + 时间窗口 + 条件 SQL</li>
 *   <li>status — 查询支付状态</li>
 * </ul>
 */
@Service
public class PaymentService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final JdbcTemplate jdbcTemplate;
    private final PayNonceMapper payNonceMapper;
    private final CurrentUserProvider currentUser;
    private final StockReservationService stockReservationService;
    private final OutboxEventService outboxEventService;
    private final String mockSecret;
    private final long timestampSkewSeconds;

    public PaymentService(JdbcTemplate jdbcTemplate,
                          PayNonceMapper payNonceMapper,
                          CurrentUserProvider currentUser,
                          StockReservationService stockReservationService,
                          OutboxEventService outboxEventService,
                          @Value("${app.payment.mock.secret}") String mockSecret,
                          @Value("${app.payment.mock.timestamp-skew-seconds:300}") long timestampSkewSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.payNonceMapper = payNonceMapper;
        this.currentUser = currentUser;
        this.stockReservationService = stockReservationService;
        this.outboxEventService = outboxEventService;
        this.mockSecret = mockSecret;
        this.timestampSkewSeconds = timestampSkewSeconds;
    }

    @Transactional
    public PaymentResponse prepay(Long orderId, PaymentPrepayRequest request) {
        if (request.payType() == null || request.payType() < 1 || request.payType() > 4) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "payType 必须在 1-4 之间");
        }
        Long userId = currentUser.requireUserId();
        List<Map<String, Object>> orders = jdbcTemplate.query(
            "SELECT id, order_no, user_id, status, pay_amount, expire_time FROM ord_order WHERE id=? AND user_id=? FOR UPDATE",
            (rs, rowNum) -> Map.<String, Object>of(
                "id", rs.getLong("id"),
                "status", rs.getInt("status"),
                "expireTime", rs.getTimestamp("expire_time").toLocalDateTime(),
                "payAmount", rs.getBigDecimal("pay_amount")),
            orderId, userId);
        if (orders.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "订单不存在");
        }
        Map<String, Object> order = orders.getFirst();
        if ((Integer) order.get("status") != 0) {
            throw new BusinessException(ApiErrorCode.ORDER_STATUS_INVALID, "只有待支付订单可以预支付");
        }
        LocalDateTime expireTime = (LocalDateTime) order.get("expireTime");
        if (expireTime.isBefore(LocalDateTime.now())) {
            throw new BusinessException(ApiErrorCode.PAYMENT_TIMEOUT);
        }
        String payNo = businessNo("PAY");
        LocalDateTime payExpire = LocalDateTime.now().plusMinutes(5);
        jdbcTemplate.update("""
            INSERT INTO pay_payment(pay_no, order_id, pay_type, pay_status, pay_amount, expire_time, mock_pay_url)
            VALUES (?, ?, ?, 0, ?, ?, ?)
            """, payNo, orderId, request.payType(), order.get("payAmount"), payExpire,
            "/mock-pay?payNo=" + payNo);
        return new PaymentResponse(payNo, orderId, request.payType(),
            (BigDecimal) order.get("payAmount"), payExpire, "/mock-pay?payNo=" + payNo);
    }

    /**
     * Completes a payment from the authenticated Mock cashier page.
     *
     * <p>The page is a user-facing sandbox, so it must not call the internal
     * service callback directly. Ownership and pending order state are checked
     * before the same durable payment transition used by the callback.</p>
     */
    @Transactional
    public Map<String, Object> mockSuccess(String payNo) {
        if (!StringUtils.hasText(payNo)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "payNo is required");
        }

        Long userId = currentUser.requireUserId();
        List<MockPaymentRow> rows = jdbcTemplate.query("""
            SELECT p.pay_no, p.order_id, p.pay_status, p.pay_amount, p.expire_time payment_expire_time,
                   o.status order_status, o.expire_time order_expire_time
            FROM pay_payment p
            JOIN ord_order o ON o.id=p.order_id
            WHERE p.pay_no=? AND o.user_id=?
            FOR UPDATE
            """, (rs, rowNum) -> new MockPaymentRow(
                rs.getString("pay_no"),
                rs.getLong("order_id"),
                rs.getInt("pay_status"),
                rs.getBigDecimal("pay_amount"),
                rs.getTimestamp("payment_expire_time") == null
                    ? null : rs.getTimestamp("payment_expire_time").toLocalDateTime(),
                rs.getInt("order_status"),
                rs.getTimestamp("order_expire_time").toLocalDateTime()),
            payNo, userId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "payment not found");
        }

        MockPaymentRow payment = rows.getFirst();
        if (payment.payStatus() == 1) {
            return paymentResult(payment.payNo(), payment.orderId(), "ALREADY_PROCESSED",
                1, payment.orderStatus(), null, payment.payAmount());
        }
        if (payment.payStatus() != 0 || payment.orderStatus() != 0) {
            throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID,
                "payment or order is no longer pending");
        }

        LocalDateTime now = LocalDateTime.now();
        if (isExpired(payment.paymentExpireTime(), now) || isExpired(payment.orderExpireTime(), now)) {
            throw new BusinessException(ApiErrorCode.PAYMENT_TIMEOUT);
        }

        String providerTransactionNo = "MOCK-TX-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        return completeSuccess(payment.payNo(), providerTransactionNo, payment.payAmount(), false);
    }

    @Transactional
    public Map<String, Object> mockCallback(MockCallbackRequest request,
                                            String timestamp,
                                            String nonce,
                                            String signature) {
        // 1. 必填校验
        if (request == null || !StringUtils.hasText(request.payNo())
            || !StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce) || !StringUtils.hasText(signature)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "Mock 回调参数不完整");
        }
        // 2. 时间窗口
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            throw new BusinessException(ApiErrorCode.UNAUTHORIZED, "timestamp 不合法");
        }
        long now = Instant.now().toEpochMilli();
        if (Math.abs(now - ts) > timestampSkewSeconds * 1000L) {
            throw new BusinessException(ApiErrorCode.UNAUTHORIZED, "回调时间超出允许窗口");
        }
        // 3. HMAC-SHA256 签名
        String payloadString;
        try {
            payloadString = (request.providerTransactionNo() == null ? "" : request.providerTransactionNo())
                + "|" + (request.status() == null ? "" : request.status())
                + "|" + (request.paidAt() == null ? "" : request.paidAt())
                + "|" + (request.paidAmount() == null ? "" : request.paidAmount().toPlainString())
                + "|" + request.payNo();
            String expected = hmacSha256(mockSecret, timestamp + "|" + nonce + "|" + payloadString);
            if (!constantTimeEquals(expected, signature)) {
                throw new BusinessException(ApiErrorCode.UNAUTHORIZED, "签名校验失败");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ApiErrorCode.UNAUTHORIZED, "签名校验异常");
        }
        // 4. nonce 去重
        PayNonce nonceRow = new PayNonce();
        nonceRow.setNonce(nonce);
        try {
            payNonceMapper.insert(nonceRow);
        } catch (DuplicateKeyException race) {
            return Map.of("payNo", request.payNo(), "replayed", true);
        }
        // 5. 状态机条件 SQL
        String status = request.status() == null ? "" : request.status().toUpperCase();
        if (!"SUCCESS".equals(status)) {
            // 失败分支：标记支付失败、释放订单（如仍可取消）
            jdbcTemplate.update("UPDATE pay_payment SET pay_status=2, callback_received_at=CURRENT_TIMESTAMP WHERE pay_no=?",
                request.payNo());
            return Map.of("payNo", request.payNo(), "result", "FAILED");
        }
        return completeSuccess(request.payNo(), request.providerTransactionNo(), request.paidAmount(), true);
    }

    public Map<String, Object> status(String payNo) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
            SELECT p.pay_no, p.order_id, p.pay_type, p.pay_status, p.pay_amount, p.provider_transaction_no,
                   p.callback_received_at, p.pay_time, p.expire_time
            FROM pay_payment p WHERE p.pay_no=?
            """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("payNo", rs.getString("pay_no"));
            row.put("orderId", rs.getLong("order_id"));
            row.put("payType", rs.getInt("pay_type"));
            row.put("payStatus", rs.getInt("pay_status"));
            row.put("payAmount", rs.getBigDecimal("pay_amount"));
            row.put("providerTransactionNo", rs.getString("provider_transaction_no"));
            row.put("paidAt", rs.getTimestamp("pay_time"));
            row.put("expireTime", rs.getTimestamp("expire_time"));
            return row;
        },
            payNo);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "支付单不存在");
        }
        Map<String, Object> row = rows.getFirst();
        Integer payStatus = (Integer) row.get("payStatus");
        row.put("payStatusText", payStatusText(payStatus));
        return row;
    }

    private Map<String, Object> completeSuccess(String payNo,
                                                String providerTransactionNo,
                                                BigDecimal paidAmount,
                                                boolean allowAutoRefund) {
        if (!StringUtils.hasText(providerTransactionNo)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "providerTransactionNo is required");
        }

        List<PaymentOrderRow> rows = jdbcTemplate.query("""
            SELECT p.pay_no, p.order_id, p.pay_status, p.pay_amount, o.status order_status
            FROM pay_payment p
            JOIN ord_order o ON o.id=p.order_id
            WHERE p.pay_no=?
            FOR UPDATE
            """, (rs, rowNum) -> new PaymentOrderRow(
                rs.getString("pay_no"),
                rs.getLong("order_id"),
                rs.getInt("pay_status"),
                rs.getBigDecimal("pay_amount"),
                rs.getInt("order_status")),
            payNo);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "payment not found");
        }

        PaymentOrderRow payment = rows.getFirst();
        if (payment.payStatus() == 1) {
            return paymentResult(payment.payNo(), payment.orderId(), "ALREADY_PROCESSED",
                1, payment.orderStatus(), null, payment.payAmount());
        }
        if (payment.payStatus() != 0) {
            throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID, "payment is no longer pending");
        }
        if (paidAmount != null && paidAmount.compareTo(payment.payAmount()) != 0) {
            throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID, "paid amount does not match payment amount");
        }

        int updated;
        try {
            updated = jdbcTemplate.update("""
                UPDATE pay_payment
                SET pay_status=1, provider_transaction_no=?, callback_received_at=CURRENT_TIMESTAMP,
                    pay_time=CURRENT_TIMESTAMP
                WHERE pay_no=? AND pay_status=0
                """, providerTransactionNo, payNo);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID,
                "providerTransactionNo is already bound to another payment");
        }
        if (updated == 0) {
            return paymentResult(payment.payNo(), payment.orderId(), "ALREADY_PROCESSED",
                1, payment.orderStatus(), null, payment.payAmount());
        }

        int orderUpdated = jdbcTemplate.update("""
            UPDATE ord_order
            SET status=1, pay_time=CURRENT_TIMESTAMP, update_time=CURRENT_TIMESTAMP
            WHERE id=? AND status=0 AND expire_time > CURRENT_TIMESTAMP
            """, payment.orderId());
        if (orderUpdated == 0) {
            if (!allowAutoRefund) {
                throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID,
                    "order is no longer pending or has expired");
            }
            jdbcTemplate.update(
                "INSERT INTO ref_refund(refund_no, order_id, order_item_id, customer_id, type, status, apply_refund_amount, reason)" +
                    " SELECT 'RF-AUTO-' || o.id, o.id, oi.id, o.customer_id, 1, 0, oi.price * oi.quantity, 'Mock payment paid after order cancellation'" +
                    " FROM ord_order o JOIN ord_order_item oi ON oi.order_id=o.id WHERE o.id=? LIMIT 1",
                payment.orderId());
            return paymentResult(payment.payNo(), payment.orderId(), "AUTO_REFUND_INITIATED",
                1, payment.orderStatus(), providerTransactionNo, payment.payAmount());
        }

        List<String> reservations = jdbcTemplate.queryForList(
            "SELECT reservation_id FROM sto_stock_reservation WHERE order_no IN (SELECT order_no FROM ord_order WHERE id=?) AND status=0",
            String.class, payment.orderId());
        for (String reservationId : reservations) {
            try {
                stockReservationService.confirm(reservationId);
            } catch (Exception ex) {
                // One reservation failure should not prevent the payment state transition.
            }
        }
        jdbcTemplate.update("INSERT INTO ord_order_timeline(order_id, event) VALUES (?, ?)",
            payment.orderId(), "Payment completed");
        BigDecimal eventAmount = paidAmount == null ? payment.payAmount() : paidAmount;
        outboxEventService.addOutbox("sales.order.paid", "ORDER_PAID", payment.orderId(),
            Map.of("orderId", payment.orderId(), "payNo", payment.payNo(), "amount", eventAmount));
        return paymentResult(payment.payNo(), payment.orderId(), "SUCCESS",
            1, 1, providerTransactionNo, eventAmount);
    }

    private Map<String, Object> paymentResult(String payNo,
                                              Long orderId,
                                              String result,
                                              Integer payStatus,
                                              Integer orderStatus,
                                              String providerTransactionNo,
                                              BigDecimal paidAmount) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("payNo", payNo);
        response.put("orderId", orderId);
        response.put("result", result);
        response.put("payStatus", payStatus);
        response.put("payStatusText", payStatus == null ? null : payStatusText(payStatus));
        response.put("orderStatus", orderStatus);
        response.put("providerTransactionNo", providerTransactionNo);
        response.put("paidAmount", paidAmount);
        return response;
    }

    private boolean isExpired(LocalDateTime expireTime, LocalDateTime now) {
        return expireTime != null && expireTime.isBefore(now);
    }

    private String payStatusText(int status) {
        return switch (status) {
            case 0 -> "待支付";
            case 1 -> "支付成功";
            case 2 -> "支付失败";
            case 3 -> "已退款";
            default -> "未知";
        };
    }

    private String businessNo(String prefix) {
        return prefix + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private String hmacSha256(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private record MockPaymentRow(String payNo,
                                  Long orderId,
                                  Integer payStatus,
                                  BigDecimal payAmount,
                                  LocalDateTime paymentExpireTime,
                                  Integer orderStatus,
                                  LocalDateTime orderExpireTime) {
    }

    private record PaymentOrderRow(String payNo,
                                   Long orderId,
                                   Integer payStatus,
                                   BigDecimal payAmount,
                                   Integer orderStatus) {
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
