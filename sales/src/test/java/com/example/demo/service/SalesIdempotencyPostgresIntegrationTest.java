package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sales 写接口幂等的真实 PostgreSQL 事务边界。
 *
 * <p>测试会让 Spring 重建目标数据库并执行 V001；必须显式提供
 * {@code sales.postgres.target-url} 和
 * {@code sales.postgres.allow-destructive-target=true}，且只能指向可丢弃测试库。</p>
 */
@SpringBootTest(properties = {
    "spring.sql.init.mode=always",
    "app.outbox.publish-delay-millis=600000",
    "app.order.expire-scan-delay-millis=600000"
})
@EnabledIfSystemProperty(
    named = "sales.postgres.integration", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SalesIdempotencyPostgresIntegrationTest {

    private static final TypeReference<String> STRING_RESPONSE =
        new TypeReference<>() {};
    private static final String OPERATION =
        "test:idempotency:postgres";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SalesIdempotencyService idempotencyService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @DynamicPropertySource
    static void disposablePostgres(DynamicPropertyRegistry registry) {
        requireDestructiveTargetConfirmation();
        String targetUrl = requiredSystemProperty(
            "sales.postgres.target-url");
        registry.add("spring.datasource.url", () -> targetUrl);
        registry.add(
            "spring.datasource.username",
            () -> System.getProperty(
                "sales.postgres.user", "postgres"));
        registry.add(
            "spring.datasource.password",
            () -> setting(
                "sales.postgres.password",
                "SALES_POSTGRES_PASSWORD",
                ""));
    }

    @BeforeAll
    void initializeMigratedSchema() throws Exception {
        transactions = new TransactionTemplate(transactionManager);
        executeFile(resolveSalesMigration());
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS sales_idempotency_test_probe (
                id         BIGSERIAL PRIMARY KEY,
                subject_id BIGINT      NOT NULL,
                marker     VARCHAR(80) NOT NULL
            )
            """);
    }

    @BeforeEach
    void clearIdempotencyState() {
        jdbcTemplate.execute(
            "TRUNCATE TABLE sales_idempotency_test_probe RESTART IDENTITY");
        jdbcTemplate.update(
            "DELETE FROM sales_idempotency_record WHERE operation=?",
            OPERATION);
        jdbcTemplate.update("""
            DELETE FROM sales_idempotency_subject s
            WHERE NOT EXISTS (
                SELECT 1
                FROM sales_idempotency_record r
                WHERE r.subject_type=s.subject_type
                  AND r.subject_id=s.subject_id
            )
            """);
    }

    @AfterAll
    void removeTestProbe() {
        jdbcTemplate.execute(
            "DROP TABLE IF EXISTS sales_idempotency_test_probe");
        jdbcTemplate.update(
            "DELETE FROM sales_idempotency_record WHERE operation=?",
            OPERATION);
        jdbcTemplate.update("""
            DELETE FROM sales_idempotency_subject s
            WHERE NOT EXISTS (
                SELECT 1
                FROM sales_idempotency_record r
                WHERE r.subject_type=s.subject_type
                  AND r.subject_id=s.subject_id
            )
            """);
    }

    @Test
    void concurrentFirstRequestRunsBusinessActionOnce() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            long customerId = 99101L;
            String key = UUID.randomUUID().toString();
            IdempotencyCommand command =
                new IdempotencyCommand("concurrent");
            AtomicInteger actionCount = new AtomicInteger();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch actionStarted = new CountDownLatch(1);
            CountDownLatch releaseAction = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Supplier<String> action = () -> {
                    actionCount.incrementAndGet();
                    String response =
                        writeProbe(customerId, "concurrent-result");
                    actionStarted.countDown();
                    await(releaseAction);
                    return response;
                };
                Future<String> first = pool.submit(() -> {
                    ready.countDown();
                    await(start);
                    return executeForCustomer(
                        customerId, key, command, action);
                });
                Future<String> second = pool.submit(() -> {
                    ready.countDown();
                    await(start);
                    return executeForCustomer(
                        customerId, key, command, action);
                });

                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                assertTrue(actionStarted.await(5, TimeUnit.SECONDS));
                releaseAction.countDown();

                assertEquals(
                    "concurrent-result",
                    first.get(10, TimeUnit.SECONDS));
                assertEquals(
                    "concurrent-result",
                    second.get(10, TimeUnit.SECONDS));
            } finally {
                releaseAction.countDown();
                pool.shutdownNow();
            }

            assertEquals(1, actionCount.get());
            assertEquals(1, probeCount(customerId));
            assertEquals(1, idempotencyRecordCount(
                "CUSTOMER", customerId, key));
            assertEquals(1, completedRecordCount(
                "CUSTOMER", customerId, key));
        });
    }

    @Test
    void failedActionRollsBackRecordAndAllowsSameKeyRetry() {
        long customerId = 99102L;
        String key = UUID.randomUUID().toString();
        IdempotencyCommand command =
            new IdempotencyCommand("rollback");

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> executeForCustomer(
                customerId,
                key,
                command,
                () -> {
                    writeProbe(customerId, "rolled-back");
                    throw new IllegalStateException(
                        "simulated business failure");
                }));

        assertEquals(
            "simulated business failure", failure.getMessage());
        assertEquals(0, probeCount(customerId));
        assertEquals(0, idempotencyRecordCount(
            "CUSTOMER", customerId, key));

        String retried = executeForCustomer(
            customerId,
            key,
            command,
            () -> writeProbe(customerId, "retry-success"));

        assertEquals("retry-success", retried);
        assertEquals(1, probeCount(customerId));
        assertEquals(1, completedRecordCount(
            "CUSTOMER", customerId, key));
    }

    @Test
    void expiredRecordAllowsSameKeyWithNewPayload() {
        long customerId = 99103L;
        String key = UUID.randomUUID().toString();

        String first = executeForCustomer(
            customerId,
            key,
            new IdempotencyCommand("first"),
            () -> writeProbe(customerId, "first-result"));
        String firstHash = requestHash(
            "CUSTOMER", customerId, key);
        int expired = jdbcTemplate.update("""
            UPDATE sales_idempotency_record
            SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second'
            WHERE operation=? AND subject_type='CUSTOMER'
              AND subject_id=? AND idempotency_key=CAST(? AS UUID)
            """, OPERATION, customerId, key);
        assertEquals(1, expired);

        String second = executeForCustomer(
            customerId,
            key,
            new IdempotencyCommand("second"),
            () -> writeProbe(customerId, "second-result"));
        String secondHash = requestHash(
            "CUSTOMER", customerId, key);

        assertEquals("first-result", first);
        assertEquals("second-result", second);
        assertNotEquals(firstHash, secondHash);
        assertEquals(2, probeCount(customerId));
        assertEquals(1, completedRecordCount(
            "CUSTOMER", customerId, key));
    }

    @Test
    void unexpiredKeyWithDifferentPayloadReturns50015() {
        long customerId = 99104L;
        String key = UUID.randomUUID().toString();
        AtomicInteger conflictingActionCount = new AtomicInteger();

        executeForCustomer(
            customerId,
            key,
            new IdempotencyCommand("original"),
            () -> writeProbe(customerId, "original-result"));

        BusinessException conflict = assertThrows(
            BusinessException.class,
            () -> executeForCustomer(
                customerId,
                key,
                new IdempotencyCommand("different"),
                () -> {
                    conflictingActionCount.incrementAndGet();
                    return writeProbe(
                        customerId, "must-not-run");
                }));

        assertEquals(
            ApiErrorCode.IDEMPOTENCY_CONFLICT,
            conflict.getErrorCode());
        assertEquals(0, conflictingActionCount.get());
        assertEquals(1, probeCount(customerId));
        assertEquals(1, completedRecordCount(
            "CUSTOMER", customerId, key));
    }

    @Test
    void sameOperationAndKeyAreIsolatedBySubjectType() {
        long subjectId = 99105L;
        String key = UUID.randomUUID().toString();

        String customerResponse = executeForCustomer(
            subjectId,
            key,
            new IdempotencyCommand("customer"),
            () -> writeProbe(subjectId, "customer-result"));
        String userResponse = executeForUser(
            subjectId,
            key,
            new IdempotencyCommand("user"),
            () -> writeProbe(subjectId, "user-result"));

        assertEquals("customer-result", customerResponse);
        assertEquals("user-result", userResponse);
        assertEquals(2, probeCount(subjectId));
        assertEquals(1, completedRecordCount(
            "CUSTOMER", subjectId, key));
        assertEquals(1, completedRecordCount(
            "USER", subjectId, key));
    }

    private String executeForCustomer(
        long customerId,
        String key,
        IdempotencyCommand command,
        Supplier<String> action) {
        return transactions.execute(status ->
            idempotencyService.execute(
                OPERATION,
                customerId,
                key,
                command,
                STRING_RESPONSE,
                action));
    }

    private String executeForUser(
        long userId,
        String key,
        IdempotencyCommand command,
        Supplier<String> action) {
        return transactions.execute(status ->
            idempotencyService.executeForUser(
                OPERATION,
                userId,
                key,
                command,
                STRING_RESPONSE,
                action));
    }

    private String writeProbe(long subjectId, String marker) {
        jdbcTemplate.update("""
            INSERT INTO sales_idempotency_test_probe(subject_id, marker)
            VALUES (?, ?)
            """, subjectId, marker);
        return marker;
    }

    private int probeCount(long subjectId) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM sales_idempotency_test_probe
            WHERE subject_id=?
            """, Integer.class, subjectId);
        return count == null ? 0 : count;
    }

    private int idempotencyRecordCount(
        String subjectType, long subjectId, String key) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM sales_idempotency_record
            WHERE operation=? AND subject_type=? AND subject_id=?
              AND idempotency_key=CAST(? AS UUID)
            """, Integer.class,
            OPERATION, subjectType, subjectId, key);
        return count == null ? 0 : count;
    }

    private int completedRecordCount(
        String subjectType, long subjectId, String key) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM sales_idempotency_record
            WHERE operation=? AND subject_type=? AND subject_id=?
              AND idempotency_key=CAST(? AS UUID)
              AND status=1 AND response_payload IS NOT NULL
            """, Integer.class,
            OPERATION, subjectType, subjectId, key);
        return count == null ? 0 : count;
    }

    private String requestHash(
        String subjectType, long subjectId, String key) {
        return jdbcTemplate.queryForObject("""
            SELECT request_hash
            FROM sales_idempotency_record
            WHERE operation=? AND subject_type=? AND subject_id=?
              AND idempotency_key=CAST(? AS UUID)
            """, String.class,
            OPERATION, subjectType, subjectId, key);
    }

    private void executeFile(Path path) throws Exception {
        String sql = Files.readString(
            path, StandardCharsets.UTF_8);
        jdbcTemplate.execute(sql);
    }

    private static String requiredSystemProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                name + " 必须指向可丢弃 PostgreSQL 测试库");
        }
        return value.trim();
    }

    private static String setting(
        String propertyName,
        String environmentName,
        String defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null) {
            return propertyValue;
        }
        String environmentValue = System.getenv(environmentName);
        return environmentValue == null
            ? defaultValue
            : environmentValue;
    }

    private static void requireDestructiveTargetConfirmation() {
        String confirmation = System.getProperty(
            "sales.postgres.allow-destructive-target");
        if (!"true".equalsIgnoreCase(confirmation)) {
            throw new IllegalStateException(
                "必须显式设置 sales.postgres.allow-destructive-target=true，"
                    + "并确认 target-url 仅指向可丢弃测试库");
        }
    }

    private static Path resolveSalesMigration() {
        Path workingDirectory = Path.of(
            System.getProperty("user.dir"))
            .toAbsolutePath()
            .normalize();
        List<Path> candidates = List.of(
            workingDirectory.resolve(
                "../sales/sql/V001__customer_address_refund.sql")
                .normalize(),
            workingDirectory.resolve(
                "sales/sql/V001__customer_address_refund.sql")
                .normalize());
        return candidates.stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "找不到 Sales V001 迁移文件，已检查：" + candidates));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发测试信号超时");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试被中断", ex);
        }
    }

    private record IdempotencyCommand(String value) {
    }
}
