package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Sales 写接口幂等，按客户或登录用户主体隔离并重放首次响应。 */
@Service
public class SalesIdempotencyService {

    static final String CUSTOMER = "CUSTOMER";
    static final String USER = "USER";
    static final int PROCESSING = 0;
    static final int COMPLETED = 1;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SalesIdempotencyService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T execute(String operation,
                         long customerId,
                         String idempotencyKey,
                         Object request,
                         TypeReference<T> responseType,
                         Supplier<T> action) {
        return executeForSubject(
            operation, CUSTOMER, customerId, idempotencyKey, request, responseType, action);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T executeForUser(String operation,
                                long userId,
                                String idempotencyKey,
                                Object request,
                                TypeReference<T> responseType,
                                Supplier<T> action) {
        return executeForSubject(
            operation, USER, userId, idempotencyKey, request, responseType, action);
    }

    private <T> T executeForSubject(String operation,
                                    String subjectType,
                                    long subjectId,
                                    String idempotencyKey,
                                    Object request,
                                    TypeReference<T> responseType,
                                    Supplier<T> action) {
        UUID key = parseKey(idempotencyKey);
        String hash = requestHash(request);
        lockSubject(subjectType, subjectId);
        List<StoredRequest> existing = jdbcTemplate.query("""
            SELECT id, request_hash, status, response_payload::text response_payload,
                   expires_at <= CURRENT_TIMESTAMP expired
            FROM sales_idempotency_record
            WHERE operation=? AND subject_type=? AND subject_id=? AND idempotency_key=?
            FOR UPDATE
            """, (rs, rowNum) -> new StoredRequest(
                rs.getLong("id"),
                rs.getString("request_hash"),
                rs.getInt("status"),
                rs.getString("response_payload"),
                rs.getBoolean("expired")),
            operation, subjectType, subjectId, key);

        if (!existing.isEmpty()) {
            StoredRequest stored = existing.getFirst();
            if (stored.expired()) {
                jdbcTemplate.update(
                    "DELETE FROM sales_idempotency_record WHERE id=?", stored.id());
            } else {
                verifyReplay(hash, stored);
                return readResponse(stored.responsePayload(), responseType);
            }
        }

        Long recordId;
        try {
            recordId = jdbcTemplate.queryForObject("""
                INSERT INTO sales_idempotency_record(
                    operation, subject_type, subject_id, idempotency_key, request_hash, status)
                VALUES (?, ?, ?, ?, ?, 0)
                RETURNING id
                """, Long.class, operation, subjectType, subjectId, key, hash);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(
                ApiErrorCode.IDEMPOTENCY_CONFLICT, "同一 Idempotency-Key 请求正在处理");
        }
        if (recordId == null) {
            throw internalError("幂等记录创建失败");
        }

        T response = action.get();
        String responsePayload = writeJson(response, "幂等响应序列化失败");
        int changed = jdbcTemplate.update("""
            UPDATE sales_idempotency_record
            SET status=1, response_payload=CAST(? AS JSONB), completed_at=CURRENT_TIMESTAMP
            WHERE id=? AND status=0
            """, responsePayload, recordId);
        if (changed != 1) {
            throw internalError("幂等记录完成状态更新失败");
        }
        return response;
    }

    private void lockSubject(String subjectType, long subjectId) {
        if (subjectId <= 0) {
            throw internalError("幂等主体 ID 非法");
        }
        jdbcTemplate.update("""
            INSERT INTO sales_idempotency_subject(subject_type, subject_id)
            VALUES (?, ?)
            ON CONFLICT (subject_type, subject_id) DO NOTHING
            """, subjectType, subjectId);
        Integer locked = jdbcTemplate.queryForObject("""
            SELECT 1
            FROM sales_idempotency_subject
            WHERE subject_type=? AND subject_id=?
            FOR UPDATE
            """, Integer.class, subjectType, subjectId);
        if (locked == null) {
            throw internalError("幂等主体锁定失败");
        }
    }

    String requestHash(Object request) {
        try {
            byte[] canonical = objectMapper.writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw internalError("幂等请求摘要生成失败");
        }
    }

    private UUID parseKey(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "缺少 Idempotency-Key 请求头");
        }
        String normalized = value.trim();
        try {
            UUID key = UUID.fromString(normalized);
            if (!key.toString().equalsIgnoreCase(normalized)) {
                throw new IllegalArgumentException("非标准 UUID");
            }
            return key;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "Idempotency-Key 必须是标准 UUID");
        }
    }

    private void verifyReplay(String requestHash, StoredRequest stored) {
        boolean samePayload = MessageDigest.isEqual(
            requestHash.getBytes(StandardCharsets.US_ASCII),
            stored.requestHash().getBytes(StandardCharsets.US_ASCII));
        if (!samePayload) {
            throw new BusinessException(
                ApiErrorCode.IDEMPOTENCY_CONFLICT,
                "Idempotency-Key 已存在但请求载荷不一致");
        }
        if (stored.status() != COMPLETED || stored.responsePayload() == null) {
            throw new BusinessException(
                ApiErrorCode.IDEMPOTENCY_CONFLICT, "同一 Idempotency-Key 请求正在处理");
        }
    }

    private <T> T readResponse(String payload, TypeReference<T> responseType) {
        try {
            return objectMapper.readValue(payload, responseType);
        } catch (JsonProcessingException ex) {
            throw internalError("幂等响应反序列化失败");
        }
    }

    private String writeJson(Object value, String message) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw internalError(message);
        }
    }

    private BusinessException internalError(String message) {
        return new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR, message);
    }

    record StoredRequest(
        long id,
        String requestHash,
        int status,
        String responsePayload,
        boolean expired) {
    }
}
