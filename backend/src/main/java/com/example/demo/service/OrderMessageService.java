package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.config.RabbitMqConfig;
import com.example.demo.exception.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 10.2 订单异步处理：发送订单事件到 MQ 并落库 ord_message_log。
 *
 * <p>幂等保证：以 (source_no, event_type, consumer) 在 mq_consume_log 落库；
 * 乱序防护（10.2.3）通过 CANCEL_PENDING 占位记录实现，详见消费者端。
 */
@Service
public class OrderMessageService {

    private static final Set<String> ALLOWED_EVENTS =
        Set.of("ORDER_CREATED", "ORDER_PAID", "ORDER_CANCELLED");

    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public OrderMessageService(JdbcTemplate jdbcTemplate,
                               RabbitTemplate rabbitTemplate,
                               ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 发送订单事件。
     */
    @Transactional
    public Map<String, Object> send(Long orderId, String eventType, Object payload) {
        if (!ALLOWED_EVENTS.contains(eventType)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "eventType 仅支持 ORDER_CREATED / ORDER_PAID / ORDER_CANCELLED");
        }
        String messageId = "MSG-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        String sourceNo = String.valueOf(orderId);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messageId", messageId);
        envelope.put("eventType", eventType);
        envelope.put("source", "sales");
        envelope.put("occurAt", LocalDateTime.now().toString());
        envelope.put("sourceNo", sourceNo);
        envelope.put("payload", payload == null ? Map.of() : payload);

        try {
            String body = objectMapper.writeValueAsString(envelope);
            jdbcTemplate.update("""
                INSERT INTO ord_message_log(message_id, event_type, order_id, payload, status, send_time)
                VALUES (?, ?, ?, CAST(? AS jsonb), 'DELIVERED', CURRENT_TIMESTAMP)
                """, messageId, eventType, orderId, body);
            String routingKey = switch (eventType) {
                case "ORDER_CREATED" -> "sales.order.created";
                case "ORDER_PAID" -> "sales.order.paid";
                case "ORDER_CANCELLED" -> "sales.order.cancelled";
                default -> throw new BusinessException(ApiErrorCode.BAD_REQUEST, "eventType 非法");
            };
            rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, routingKey, body, postProcess(messageId));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("messageId", messageId);
            response.put("eventType", eventType);
            response.put("routingKey", routingKey);
            response.put("sourceNo", sourceNo);
            response.put("status", "DELIVERED");
            return response;
        } catch (Exception ex) {
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR, "MQ 发送失败：" + ex.getMessage());
        }
    }

    /**
     * 查询消息发送状态。
     */
    public Map<String, Object> status(String messageId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
            "SELECT message_id, event_type, status, send_time, consume_time FROM ord_message_log WHERE message_id = ?",
            (rs, rowNum) -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("messageId", rs.getString("message_id"));
                map.put("eventType", rs.getString("event_type"));
                map.put("status", rs.getString("status"));
                Timestamp send = rs.getTimestamp("send_time");
                map.put("sendTime", send == null ? null : send.toLocalDateTime());
                Timestamp consume = rs.getTimestamp("consume_time");
                map.put("consumeTime", consume == null ? null : consume.toLocalDateTime());
                return map;
            }, messageId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "消息不存在");
        }
        return rows.getFirst();
    }

    /**
     * 记录消费结果（10.2.3 幂等）。供 RabbitMQ Listener 调用。
     */
    @Transactional
    public void markConsumed(String messageId, String consumer, String eventType, String sourceNo) {
        jdbcTemplate.update("""
            INSERT INTO mq_consume_log(message_id, source_no, event_type, consumer, status, payload_hash)
            VALUES (?, ?, ?, ?, 1, ?)
            ON CONFLICT (message_id, consumer) DO NOTHING
            """, messageId, sourceNo, eventType, consumer, String.valueOf(messageId.hashCode()));
        jdbcTemplate.update("UPDATE ord_message_log SET status = 'CONSUMED', consume_time = CURRENT_TIMESTAMP WHERE message_id = ?",
            messageId);
    }

    private MessagePostProcessor postProcess(String messageId) {
        return msg -> {
            msg.getMessageProperties().setMessageId(messageId);
            msg.getMessageProperties().setContentType("application/json");
            return msg;
        };
    }

    /** 辅助：解析 payload 字符串。 */
    public Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
