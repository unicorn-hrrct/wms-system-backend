package com.example.demo.service;

import com.example.demo.config.RabbitMqConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxEventService {

    public static final String DEFAULT_SOURCE = "sales";
    public static final int DEFAULT_SCHEMA_VERSION = 1;

    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public OutboxEventService(JdbcTemplate jdbcTemplate, RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * v1.2 §10.2.1 统一事件信封：把业务事件落到 outbox 表；事务提交后由 publishPending() 投递。
     *
     * @param routingKey  e.g. sales.order.created / sales.refund.completed / inventory.low_stock
     * @param eventType   ORDER_CREATED / ORDER_PAID / ORDER_CANCELLED / ORDER_SHIPPED / REFUND_COMPLETED 等
     * @param aggregateId 业务聚合 id（订单号 / 退款单号 / 预占 id）；可空
     * @param payload     业务载荷
     */
    public void addOutbox(String routingKey, String eventType, Object aggregateId, Object payload) {
        String eventId = UUID.randomUUID().toString();
        String traceId = MDC.get("traceId");
        if (traceId == null) traceId = UUID.randomUUID().toString();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messageId", eventId);
        envelope.put("eventType", eventType == null ? routingKey : eventType);
        envelope.put("schemaVersion", DEFAULT_SCHEMA_VERSION);
        envelope.put("source", DEFAULT_SOURCE);
        envelope.put("occurredAt", LocalDateTime.now().toString());
        envelope.put("traceId", traceId);
        envelope.put("aggregateType", routingKey.split("\\.")[0]);
        envelope.put("aggregateId", aggregateId == null ? "" : String.valueOf(aggregateId));
        envelope.put("payload", payload);

        try {
            jdbcTemplate.update("""
                INSERT INTO sys_outbox_event(event_id, routing_key, event_type, source, schema_version, trace_id,
                                              aggregate_type, aggregate_id, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """,
                eventId, routingKey, envelope.get("eventType"), DEFAULT_SOURCE, DEFAULT_SCHEMA_VERSION, traceId,
                envelope.get("aggregateType"), envelope.get("aggregateId"), objectMapper.writeValueAsString(envelope));
        } catch (Exception ex) {
            throw new com.example.demo.exception.BusinessException(
                com.example.demo.common.ApiErrorCode.INTERNAL_SERVER_ERROR, "outbox 写入失败: " + ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${app.outbox.publish-delay-millis:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxRow> rows = jdbcTemplate.query("""
            SELECT id,event_id,routing_key,payload::text payload
            FROM sys_outbox_event
            WHERE status=0 AND next_retry_time<=CURRENT_TIMESTAMP
            ORDER BY id LIMIT 50 FOR UPDATE SKIP LOCKED
            """, (rs, rowNum) -> new OutboxRow(rs.getLong("id"), rs.getString("event_id"),
            rs.getString("routing_key"), rs.getString("payload")));
        for (OutboxRow row : rows) {
            try {
                rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, row.routingKey(), row.payload(), message -> {
                    message.getMessageProperties().setMessageId(row.eventId());
                    message.getMessageProperties().setContentType("application/json");
                    return message;
                });
                jdbcTemplate.update("UPDATE sys_outbox_event SET status=1,published_time=CURRENT_TIMESTAMP WHERE id=?", row.id());
            } catch (RuntimeException ex) {
                jdbcTemplate.update("""
                    UPDATE sys_outbox_event SET retry_count=retry_count+1,
                        next_retry_time=CURRENT_TIMESTAMP + INTERVAL '10 seconds'
                    WHERE id=?
                    """, row.id());
            }
        }
    }

    @RabbitListener(queues = {RabbitMqConfig.ORDER_EVENTS_QUEUE, RabbitMqConfig.STOCK_ALERT_QUEUE})
    public void consume(String payload) throws Exception {
        JsonNode event = objectMapper.readTree(payload);
        String eventId = event.path("messageId").asText(event.path("eventId").asText(null));
        if (eventId == null) {
            return;
        }
        jdbcTemplate.update("""
            INSERT INTO sys_consumed_event(event_id,consumer) VALUES (?, 'core-audit')
            ON CONFLICT(event_id) DO NOTHING
            """, eventId);
    }

    private record OutboxRow(Long id, String eventId, String routingKey, String payload) {
    }
}
