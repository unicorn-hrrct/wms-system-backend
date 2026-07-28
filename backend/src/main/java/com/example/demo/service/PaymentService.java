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
        int updated = jdbcTemplate.update("""
            UPDATE pay_payment
            SET pay_status=1, provider_transaction_no=?, callback_received_at=CURRENT_TIMESTAMP, pay_time=CURRENT_TIMESTAMP
            WHERE pay_no=? AND pay_status=0
            """, request.providerTransactionNo(), request.payNo());
        if (updated == 0) {
            return Map.of("payNo", request.payNo(), "result", "ALREADY_PROCESSED");
        }
        // 6. 推进订单 status 0 -> 1
        List<Long> orderIds = jdbcTemplate.queryForList(
            "SELECT order_id FROM pay_payment WHERE pay_no=?", Long.class, request.payNo());
        if (orderIds.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "支付单未绑定订单");
        }
        Long orderId = orderIds.getFirst();
        int orderUpdated = jdbcTemplate.update("""
            UPDATE ord_order
            SET status=1, pay_time=CURRENT_TIMESTAMP, update_time=CURRENT_TIMESTAMP
            WHERE id=? AND status=0 AND expire_time > CURRENT_TIMESTAMP
            """, orderId);
        if (orderUpdated == 0) {
            // 订单已被取消或已支付，走自动退款分支（v1.2 §7.4.3）
            jdbcTemplate.update(
                "INSERT INTO ref_refund(refund_no, order_id, order_item_id, customer_id, type, status, apply_refund_amount, reason)" +
                    " SELECT 'RF-AUTO-' || o.id, o.id, oi.id, o.customer_id, 1, 0, oi.price * oi.quantity, '已扣款但订单已取消自动退款'" +
                    " FROM ord_order o JOIN ord_order_item oi ON oi.order_id=o.id WHERE o.id=? LIMIT 1",
                orderId);
            return Map.of("payNo", request.payNo(), "result", "AUTO_REFUND_INITIATED");
        }
        // 7. 确认预占 + Outbox ORDER_PAID
        List<String> reservations = jdbcTemplate.queryForList(
            "SELECT reservation_id FROM sto_stock_reservation WHERE order_no IN (SELECT order_no FROM ord_order WHERE id=?) AND status=0",
            String.class, orderId);
        for (String reservationId : reservations) {
            try {
                stockReservationService.confirm(reservationId);
            } catch (Exception ex) {
                // 单条失败不影响其他
            }
        }
        jdbcTemplate.update("INSERT INTO ord_order_timeline(order_id, event) VALUES (?, ?)", orderId, "支付成功");
        outboxEventService.addOutbox("sales.order.paid", "ORDER_PAID", orderId,
            Map.of("orderId", orderId, "payNo", request.payNo(), "amount", request.paidAmount()));
        return Map.of("payNo", request.payNo(), "orderId", orderId, "result", "SUCCESS");
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

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
