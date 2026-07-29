package com.example.demo.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.sql.init.mode=never",
    "app.outbox.publish-delay-millis=600000",
    "app.order.expire-scan-delay-millis=600000"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CustomerAddressAftersaleRefundHttpIntegrationTest {

    private static final long USER_ID = 2L;
    private static final long CUSTOMER_ID = 5002L;
    private static final long OTHER_CUSTOMER_ID = 5003L;
    private static final long SEED_ADDRESS_ID = 201L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void resetDatabaseToMigratedUpstreamBaseline() throws Exception {
        execute("""
            DROP TABLE IF EXISTS sales_idempotency_record CASCADE;
            DROP TABLE IF EXISTS sales_idempotency_subject CASCADE;
            """);
        executeFile(Path.of("src/main/resources/schema.sql"));
        executeFile(Path.of("src/main/resources/data.sql"));
        executeFile(Path.of(
            "../sales/sql/V001__customer_address_refund.sql"));
    }

    @Test
    void customerAndAddressHttpFlowUsesCustomerOwnershipAndWriteReplay()
        throws Exception {
        String alice = bearerToken("alice", "alice123");

        mockMvc.perform(get("/api/v1/customer/me")
                .header(HttpHeaders.AUTHORIZATION, alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.customerId").value(CUSTOMER_ID))
            .andExpect(jsonPath("$.data.userId").value(USER_ID));

        String customerBody = """
            {"nickname":"Alice HTTP","email":"alice.http@example.com"}
            """;
        mockMvc.perform(put("/api/v1/customer/me")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content(customerBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        String customerKey = UUID.randomUUID().toString();
        for (int replay = 0; replay < 2; replay++) {
            mockMvc.perform(put("/api/v1/customer/me")
                    .header(HttpHeaders.AUTHORIZATION, alice)
                    .header("Idempotency-Key", customerKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(customerBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("Alice HTTP"))
                .andExpect(jsonPath("$.data.email")
                    .value("alice.http@example.com"));
        }

        long firstCreatedId = createAddress(
            alice, "HTTP 地址一", "集成测试路 1 号", true);
        long replayedId = createAddressWithKey(
            alice,
            "HTTP 地址一",
            "集成测试路 1 号",
            true,
            lastCreateKey);
        assertEquals(firstCreatedId, replayedId);
        assertEquals(1, count("""
            SELECT COUNT(*) FROM crm_address
            WHERE customer_id=? AND detail_address='集成测试路 1 号'
              AND deleted=0
            """, CUSTOMER_ID));

        long secondCreatedId = createAddress(
            alice, "HTTP 地址二", "集成测试路 2 号", false);
        mockMvc.perform(put(
                "/api/v1/address/{addressId}/default", secondCreatedId)
                .header(HttpHeaders.AUTHORIZATION, alice)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.addressId").value(secondCreatedId))
            .andExpect(jsonPath("$.data.isDefault").value(true));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM crm_address
            WHERE customer_id=? AND is_default=TRUE AND deleted=0
            """, CUSTOMER_ID));

        mockMvc.perform(delete(
                "/api/v1/address/{addressId}", secondCreatedId)
                .header(HttpHeaders.AUTHORIZATION, alice)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isOk());
        assertEquals(1, count("""
            SELECT COUNT(*) FROM crm_address
            WHERE id=? AND customer_id=? AND is_default=TRUE AND deleted=0
            """, SEED_ADDRESS_ID, CUSTOMER_ID));

        Long foreignAddressId = jdbcTemplate.queryForObject("""
            INSERT INTO crm_address(
                customer_id, receiver_name, receiver_phone, province, city,
                district, detail_address, is_default)
            VALUES (?, '其他客户', '13900139002', '广东省', '深圳市',
                    '南山区', '其他客户地址', FALSE)
            RETURNING id
            """, Long.class, OTHER_CUSTOMER_ID);
        mockMvc.perform(get(
                "/api/v1/address/{addressId}", foreignAddressId)
                .header(HttpHeaders.AUTHORIZATION, alice))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    void aftersaleApprovalCreatesOneRefundAndCompletionWritesOutbox()
        throws Exception {
        String alice = bearerToken("alice", "alice123");
        String seller = bearerToken("seller", "admin123");
        long orderId = createOrder();
        long orderItemId = createOrderItem(orderId);
        String applyKey = UUID.randomUUID().toString();
        String applyBody = """
            {
              "orderId":%d,
              "orderItemId":%d,
              "type":1,
              "reason":"HTTP 两阶段退款测试",
              "applyRefundAmount":10.00
            }
            """.formatted(orderId, orderItemId);

        MvcResult applied = applyAftersale(alice, applyKey, applyBody);
        JsonNode appliedData = json(applied).path("data");
        long aftersaleId = appliedData.path("aftersaleId").asLong();
        String aftersaleNo = appliedData.path("aftersaleNo").asText();
        assertTrue(appliedData.path("refundId").isNull());
        assertEquals(0, count(
            "SELECT COUNT(*) FROM ref_refund"));

        MvcResult replayed = applyAftersale(alice, applyKey, applyBody);
        assertEquals(
            aftersaleId,
            json(replayed).path("data").path("aftersaleId").asLong());
        assertEquals(1, count("""
            SELECT COUNT(*) FROM ord_aftersale
            WHERE id=? AND status=0 AND refund_id IS NULL
            """, aftersaleId));

        mockMvc.perform(get("/api/v1/web/aftersale")
                .header(HttpHeaders.AUTHORIZATION, alice))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/v1/web/aftersale")
                .header(HttpHeaders.AUTHORIZATION, seller)
                .param("aftersaleNo", aftersaleNo))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.list[0].aftersaleId")
                .value(aftersaleId));

        String auditKey = UUID.randomUUID().toString();
        String auditBody = """
            {
              "auditStatus":1,
              "approvedAmount":10.00,
              "auditRemark":"同意退款"
            }
            """;
        MvcResult audited = auditAftersale(
            seller, aftersaleId, auditKey, auditBody);
        JsonNode auditedData = json(audited).path("data");
        long refundId = auditedData.path("refundId").asLong();
        String refundNo = auditedData.path("refundNo").asText();
        assertEquals(1, auditedData.path("status").asInt());

        MvcResult auditReplay = auditAftersale(
            seller, aftersaleId, auditKey, auditBody);
        assertEquals(
            refundId,
            json(auditReplay).path("data").path("refundId").asLong());
        assertEquals(1, count("""
            SELECT COUNT(*) FROM ref_refund
            WHERE id=? AND status=1 AND customer_id=?
            """, refundId, CUSTOMER_ID));

        mockMvc.perform(get("/api/v1/refund/my")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .param("refundNo", refundNo))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.list[0].refundId").value(refundId))
            .andExpect(jsonPath("$.data.list[0].status").value(1));

        String completeKey = UUID.randomUUID().toString();
        String completeBody = """
            {
              "actualRefundAmount":10.00,
              "providerRefundNo":"%s",
              "restock":false
            }
            """.formatted(businessNo("HTTP-PROVIDER-"));
        for (int replay = 0; replay < 2; replay++) {
            mockMvc.perform(put(
                    "/api/v1/web/refund/{refundId}/complete", refundId)
                    .header(HttpHeaders.AUTHORIZATION, seller)
                    .header("Idempotency-Key", completeKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(completeBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(4))
                .andExpect(jsonPath("$.data.restock").value(false))
                .andExpect(jsonPath("$.data.completedAt")
                    .value(org.hamcrest.Matchers.endsWith("+08:00")));
        }
        assertEquals(1, count("""
            SELECT COUNT(*) FROM sys_outbox_event
            WHERE routing_key='sales.refund.completed'
              AND event_type='REFUND_COMPLETED'
              AND aggregate_id=?
              AND payload->'payload'->>'restock'='false'
            """, Long.toString(refundId)));

        mockMvc.perform(put(
                "/api/v1/refund/{refundId}/cancel", refundId)
                .header(HttpHeaders.AUTHORIZATION, alice)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(50014));
    }

    private String lastCreateKey;

    private long createAddress(
        String token,
        String receiverName,
        String detailAddress,
        boolean isDefault) throws Exception {
        lastCreateKey = UUID.randomUUID().toString();
        return createAddressWithKey(
            token,
            receiverName,
            detailAddress,
            isDefault,
            lastCreateKey);
    }

    private long createAddressWithKey(
        String token,
        String receiverName,
        String detailAddress,
        boolean isDefault,
        String idempotencyKey) throws Exception {
        String body = """
            {
              "receiverName":"%s",
              "receiverPhone":"13900139001",
              "province":"广东省",
              "city":"深圳市",
              "district":"福田区",
              "detailAddress":"%s",
              "isDefault":%s
            }
            """.formatted(receiverName, detailAddress, isDefault);
        MvcResult created = mockMvc.perform(post("/api/v1/address")
                .header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.customerId").value(CUSTOMER_ID))
            .andReturn();
        return json(created).path("data").path("addressId").asLong();
    }

    private MvcResult applyAftersale(
        String token, String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/order/aftersale")
                .header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(0))
            .andReturn();
    }

    private MvcResult auditAftersale(
        String token,
        long aftersaleId,
        String idempotencyKey,
        String body) throws Exception {
        return mockMvc.perform(put(
                "/api/v1/web/aftersale/{aftersaleId}/audit", aftersaleId)
                .header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
    }

    private long createOrder() {
        Long id = jdbcTemplate.queryForObject("""
            INSERT INTO ord_order(
                order_no, user_id, customer_id, address_id, address_snapshot,
                total_amount, discount_amount, freight, pay_amount, status,
                idempotency_key, expire_time)
            VALUES (?, ?, ?, ?, CAST(? AS JSONB), ?, 0, 0, ?, 3, ?,
                    CURRENT_TIMESTAMP + INTERVAL '1 hour')
            RETURNING id
            """, Long.class,
            businessNo("HTTP-ORDER-"),
            USER_ID,
            CUSTOMER_ID,
            SEED_ADDRESS_ID,
            "{\"receiverName\":\"李明\",\"receiverPhone\":\"13900139000\"}",
            new BigDecimal("100.00"),
            new BigDecimal("100.00"),
            UUID.randomUUID().toString());
        return id.longValue();
    }

    private long createOrderItem(long orderId) {
        Long id = jdbcTemplate.queryForObject("""
            INSERT INTO ord_order_item(
                order_id, sku_id, sku_code, product_name, spec_values,
                price, quantity, subtotal, warehouse_id, location_id)
            VALUES (?, 2001, 'SP001-BLACK-256', 'iPhone 15 Pro', '{}',
                    100.00, 1, 100.00, 1, 301)
            RETURNING id
            """, Long.class, orderId);
        return id.longValue();
    }

    private String bearerToken(String username, String password)
        throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username
                    + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return "Bearer "
            + json(result).path("data").path("token").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(
            result.getResponse().getContentAsString(
                StandardCharsets.UTF_8));
    }

    private int count(String sql, Object... args) {
        Integer value =
            jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void executeFile(Path path) throws Exception {
        execute(Files.readString(path, StandardCharsets.UTF_8));
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String businessNo(String prefix) {
        String suffix =
            UUID.randomUUID().toString().replace("-", "");
        return prefix + suffix.substring(0, 40 - prefix.length());
    }
}
