package com.example.demo.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "app.outbox.publish-delay-millis=600000",
    "app.order.expire-scan-delay-millis=600000"
})
@AutoConfigureMockMvc
class BusinessApiControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void supplierSystemAndAnalyticsApisShouldFollowContract() throws Exception {
        String admin = bearerToken("admin", "admin123");

        mockMvc.perform(get("/api/v1/supplier/list")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .param("name", "华为"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.list[0].supplierId").value(101));

        mockMvc.perform(get("/api/v1/dict/type/sys_order_status")
                .header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].dictLabel").value("待支付"));

        mockMvc.perform(get("/api/v1/config/list")
                .header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].configKey").value("sys.order.auto.cancel.minutes"));

        mockMvc.perform(get("/api/v1/dashboard/stock")
                .header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalSkuCount").isNumber())
            .andExpect(jsonPath("$.data.recentTrend").isArray());
    }

    @Test
    void customerAddressAndRestockApisShouldReturnFreshData() throws Exception {
        String alice = bearerToken("alice", "alice123");

        mockMvc.perform(get("/api/v1/address/list")
                .header(HttpHeaders.AUTHORIZATION, alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].addressId").value(201))
            .andExpect(jsonPath("$.data[0].updateTime").exists());

        String nickname = "Alice " + UUID.randomUUID().toString().substring(0, 8);
        String email = "alice+" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mockMvc.perform(put("/api/v1/customer/me")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname + "\",\"phone\":\"13800138001\",\"email\":\"" + email + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.nickname").value(nickname))
            .andExpect(jsonPath("$.data.email").value(email));

        String admin = bearerToken("admin", "admin123");
        mockMvc.perform(post("/api/v1/restock/generate-order")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"suggestionIds\":[2001],\"autoMerge\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.generatedOrders[0].orderId").isNumber())
            .andExpect(jsonPath("$.data.generatedOrders[0].items[0].skuId").value(2001));
    }

    @Test
    void customerCanCompleteCartOrderAndPaymentFlowIdempotently() throws Exception {
        String alice = bearerToken("alice", "alice123");
        MvcResult cartResult = mockMvc.perform(post("/api/v1/cart/add")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuId\":2001,\"quantity\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.skuId").value(2001))
            .andReturn();
        long cartItemId = json(cartResult).path("data").path("cartItemId").asLong();
        String key = "test-" + UUID.randomUUID();
        String body = "{\"addressId\":201,\"cartItemIds\":[" + cartItemId + "]}";

        MvcResult orderResult = mockMvc.perform(post("/api/v1/order/create")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(0))
            .andReturn();
        long orderId = json(orderResult).path("data").path("orderId").asLong();
        String orderNo = json(orderResult).path("data").path("orderNo").asText();
        BigDecimal orderPayAmount = json(orderResult).path("data").path("payAmount").decimalValue();

        mockMvc.perform(post("/api/v1/order/create")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orderId").value(orderId));

        MvcResult payResult = mockMvc.perform(post("/api/v1/order/{orderId}/pay", orderId)
                .header(HttpHeaders.AUTHORIZATION, alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payType\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.payStatus").value(0))
            .andReturn();
        String payNo = json(payResult).path("data").path("payNo").asText();
        BigDecimal payAmount = orderPayAmount;
        String paidAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString();
        String providerTransactionNo = "MOCK-" + UUID.randomUUID();
        String callbackBody = "{\"payNo\":\"" + payNo + "\",\"status\":\"SUCCESS\",\"providerTransactionNo\":\""
            + providerTransactionNo + "\",\"paidAt\":\"" + paidAt + "\",\"paidAmount\":" + payAmount.toPlainString() + "}";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String signature = hmacSha256("mock-pay-secret-2026",
            timestamp + "|" + nonce + "|" + providerTransactionNo + "|SUCCESS|" + paidAt + "|"
                + payAmount.toPlainString() + "|" + payNo);

        mockMvc.perform(post("/api/v1/payment/mock-callback")
                .header("X-Service-Token", "change-me-2026")
                .header("X-Mock-Timestamp", timestamp)
                .header("X-Mock-Nonce", nonce)
                .header("X-Mock-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(callbackBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.result").value("SUCCESS"));

        mockMvc.perform(get("/api/v1/payment/{payNo}/status", payNo)
                .header(HttpHeaders.AUTHORIZATION, alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.payStatus").value(1));

        String admin = bearerToken("admin", "admin123");
        MvcResult merchantOrderList = mockMvc.perform(get("/api/v1/web/order")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .param("status", "1")
                .param("orderNo", orderNo))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.list[0].orderId").value(orderId))
            .andExpect(jsonPath("$.data.list[0].username").value("alice"))
            .andExpect(jsonPath("$.data.list[0].firstProductName").value("iPhone 15 Pro"))
            .andReturn();
        org.junit.jupiter.api.Assertions.assertTrue(
            containsLong(json(merchantOrderList).path("data").path("list"), "userId", 2));

        mockMvc.perform(get("/api/v1/web/order/list")
                .header(HttpHeaders.AUTHORIZATION, bearerToken("seller", "admin123"))
                .param("customerKeyword", "alice")
                .param("pageSize", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.list[0].orderNo").value(orderNo));

        mockMvc.perform(post("/api/v1/order/{orderId}/ship", orderId)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"logisticsCompany\":\"顺丰\",\"logisticsNo\":\"SF10001\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/order/{orderId}/receive", orderId)
                .header(HttpHeaders.AUTHORIZATION, alice))
            .andExpect(status().isOk());

        MvcResult detail = mockMvc.perform(get("/api/v1/order/{orderId}", orderId)
                .header(HttpHeaders.AUTHORIZATION, alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(3))
            .andReturn();
        long orderItemId = json(detail).path("data").path("items").get(0).path("orderItemId").asLong();

        MvcResult aftersaleResult = mockMvc.perform(post("/api/v1/order/aftersale")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .header("Idempotency-Key", "refund-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":" + orderId + ",\"orderItemId\":" + orderItemId
                    + ",\"type\":1,\"reason\":\"测试退货\",\"applyRefundAmount\":"
                    + orderPayAmount.toPlainString() + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(0))
            .andExpect(jsonPath("$.data.aftersaleNo").isString())
            .andReturn();
        String aftersaleNo = json(aftersaleResult).path("data").path("aftersaleNo").asText();
        long aftersaleId = json(aftersaleResult).path("data").path("aftersaleId").asLong();

        mockMvc.perform(get("/api/v1/web/aftersale")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .param("aftersaleNo", aftersaleNo))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.list[0].aftersaleId").value(aftersaleId));

        mockMvc.perform(get("/api/v1/web/aftersale/{aftersaleId}", aftersaleId)
                .header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.aftersaleNo").value(aftersaleNo))
            .andExpect(jsonPath("$.data.orderItem.productName").value("iPhone 15 Pro"));

        MvcResult auditResult = mockMvc.perform(put("/api/v1/web/aftersale/{aftersaleId}/audit", aftersaleId)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"auditStatus\":1,\"approvedAmount\":" + orderPayAmount.toPlainString()
                    + ",\"auditRemark\":\"同意退款\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(1))
            .andExpect(jsonPath("$.data.refundNo").isString())
            .andReturn();
        String refundNo = json(auditResult).path("data").path("refundNo").asText();
        long refundId = json(auditResult).path("data").path("refundId").asLong();

        mockMvc.perform(get("/api/v1/refund/my")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .param("refundNo", refundNo))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.list[0].refundId").value(refundId));
    }

    @Test
    void purchaseInventoryFlowShouldUpdateStock() throws Exception {
        String admin = bearerToken("admin", "admin123");
        MvcResult requestResult = mockMvc.perform(post("/api/v1/purchase/request")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"supplierId\":101,\"items\":[{\"skuId\":2001,\"quantity\":2,\"expectedPrice\":7900.00}]}"))
            .andExpect(status().isOk())
            .andReturn();
        long requestId = json(requestResult).path("data").path("requestId").asLong();

        mockMvc.perform(put("/api/v1/purchase/request/{id}/audit", requestId)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"auditStatus\":1}"))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/purchase/order")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":" + requestId + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(0));

        mockMvc.perform(post("/api/v1/stock/transfer")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromWarehouseId\":1,\"toWarehouseId\":2,\"items\":[{\"skuId\":2001,\"quantity\":1,\"fromLocationId\":301,\"toLocationId\":601}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(1));
    }

    @Test
    void fullStockCheckShouldAggregateMultipleLocationsPerSku() throws Exception {
        String admin = bearerToken("admin", "admin123");
        Integer before = jdbcTemplate.queryForObject(
            "SELECT SUM(quantity)::int FROM sto_stock WHERE sku_id=2001 AND warehouse_id=1 AND deleted=0",
            Integer.class);
        MvcResult create = mockMvc.perform(post("/api/v1/stock/check")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"warehouseId\":1,\"type\":1,\"checkItems\":[]}"))
            .andExpect(status().isOk())
            .andReturn();
        long checkId = json(create).path("data").path("checkId").asLong();
        Integer itemCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sto_stock_check_item WHERE check_id=? AND sku_id=2001", Integer.class, checkId);
        Integer systemQty = jdbcTemplate.queryForObject(
            "SELECT system_qty FROM sto_stock_check_item WHERE check_id=? AND sku_id=2001", Integer.class, checkId);
        org.junit.jupiter.api.Assertions.assertEquals(1, itemCount);
        org.junit.jupiter.api.Assertions.assertEquals(before, systemQty);

        mockMvc.perform(put("/api/v1/stock/check/{id}/submit", checkId)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"results\":[{\"skuId\":2001,\"actualQty\":" + (before - 1)
                    + ",\"diffQty\":-1,\"reason\":\"盘亏\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(1));
    }

    @Test
    void ordinaryUserCannotManageSuppliers() throws Exception {
        mockMvc.perform(get("/api/v1/supplier/list")
                .header(HttpHeaders.AUTHORIZATION, bearerToken("alice", "alice123")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void customerCanApplyForMerchantAndAdminCanApprove() throws Exception {
        String alice = bearerToken("alice", "alice123");
        String admin = bearerToken("admin", "admin123");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String merchantCode = "CDX" + suffix;
        String merchantName = "Codex Merchant " + suffix;
        String idempotencyKey = "merchant-" + UUID.randomUUID();
        String applyBody = """
            {"merchantName":"%s","merchantCode":"%s","contactName":"Alice",
             "contactPhone":"13800138001","contactEmail":"alice@example.com",
             "licenseNo":"LIC-%s","businessScope":"electronics","address":"Shenzhen"}
            """.formatted(merchantName, merchantCode, suffix);

        MvcResult applyResult = mockMvc.perform(post("/api/v1/merchant/apply")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(applyBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(0))
            .andExpect(jsonPath("$.data.applicationNo").isString())
            .andExpect(jsonPath("$.data.merchantCode").value(merchantCode.toUpperCase()))
            .andReturn();
        long applicationId = json(applyResult).path("data").path("applicationId").asLong();
        String applicationNo = json(applyResult).path("data").path("applicationNo").asText();

        mockMvc.perform(post("/api/v1/merchant/apply")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(applyBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.applicationId").value(applicationId));

        mockMvc.perform(get("/api/v1/merchant/application/my")
                .header(HttpHeaders.AUTHORIZATION, alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.applicationId").value(applicationId));

        mockMvc.perform(get("/api/v1/web/merchant/applications")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .param("applicationNo", applicationNo))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.list[0].applicationId").value(applicationId));

        mockMvc.perform(get("/api/v1/web/merchant/applications/{applicationId}", applicationId)
                .header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.merchantName").value(merchantName))
            .andExpect(jsonPath("$.data.statusText").value("待审核"));

        MvcResult auditResult = mockMvc.perform(put("/api/v1/web/merchant/applications/{applicationId}/audit", applicationId)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"auditStatus\":1,\"warehouseIds\":[1],\"auditRemark\":\"approved\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(1))
            .andExpect(jsonPath("$.data.ownerId").isNumber())
            .andExpect(jsonPath("$.data.ownerCode").value(merchantCode.toUpperCase()))
            .andExpect(jsonPath("$.data.warehouseIds[0]").value(1))
            .andReturn();
        long ownerId = json(auditResult).path("data").path("ownerId").asLong();

        MvcResult ownerList = mockMvc.perform(get("/api/v1/owner/list")
                .header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk())
            .andReturn();
        org.junit.jupiter.api.Assertions.assertTrue(containsLong(json(ownerList).path("data"), "ownerId", ownerId));

        MvcResult relogin = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"alice123\"}"))
            .andExpect(status().isOk())
            .andReturn();
        org.junit.jupiter.api.Assertions.assertTrue(
            containsText(json(relogin).path("data").path("roles"), "seller"));
    }

    @Test
    void customerFavoriteHistoryTicketAndNotificationApisShouldWork() throws Exception {
        String alice = bearerToken("alice", "alice123");
        String admin = bearerToken("admin", "admin123");
        String seller = bearerToken("seller", "admin123");
        jdbcTemplate.update("DELETE FROM crm_product_favorite WHERE customer_id=5002 AND product_id=1001");
        jdbcTemplate.update("DELETE FROM crm_browse_history WHERE customer_id=5002 AND product_id=1001");
        jdbcTemplate.update("DELETE FROM msg_notification_read WHERE user_id=2");

        mockMvc.perform(post("/api/v1/favorites")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":1001}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.productId").value(1001))
            .andExpect(jsonPath("$.data.productName").value("iPhone 15 Pro"));

        mockMvc.perform(get("/api/v1/favorites/check")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .param("productId", "1001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.favorited").value(true));

        mockMvc.perform(delete("/api/v1/favorites/{productId}", 1001)
                .header(HttpHeaders.AUTHORIZATION, alice))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/favorites/check")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .param("productId", "1001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.favorited").value(false));

        mockMvc.perform(post("/api/v1/browse-history")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":1001,\"skuId\":2001}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.productId").value(1001))
            .andExpect(jsonPath("$.data.viewCount").value(1));

        mockMvc.perform(post("/api/v1/browse-history")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":1001,\"skuId\":2001}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewCount").value(2));

        MvcResult historyList = mockMvc.perform(get("/api/v1/browse-history")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .param("pageSize", "20"))
            .andExpect(status().isOk())
            .andReturn();
        org.junit.jupiter.api.Assertions.assertTrue(
            containsLong(json(historyList).path("data").path("list"), "productId", 1001));

        mockMvc.perform(delete("/api/v1/browse-history")
                .header(HttpHeaders.AUTHORIZATION, alice))
            .andExpect(status().isOk());

        MvcResult ticketResult = mockMvc.perform(post("/api/v1/customer-service/tickets")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"Need help " + UUID.randomUUID()
                    + "\",\"category\":\"order\",\"content\":\"Please check my order.\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(0))
            .andExpect(jsonPath("$.data.messages[0].senderType").value("CUSTOMER"))
            .andReturn();
        long ticketId = json(ticketResult).path("data").path("ticketId").asLong();

        mockMvc.perform(get("/api/v1/customer-service/tickets/my")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .param("status", "0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(post("/api/v1/customer-service/tickets/{ticketId}/messages", ticketId)
                .header(HttpHeaders.AUTHORIZATION, seller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"We are handling this.\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(1))
            .andExpect(jsonPath("$.data.messages[1].senderType").value("AGENT"));

        mockMvc.perform(put("/api/v1/customer-service/tickets/{ticketId}/assign", ticketId)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeId\":6}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assignedTo").value(6));

        mockMvc.perform(put("/api/v1/customer-service/tickets/{ticketId}/status", ticketId)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.statusText").value("RESOLVED"));

        mockMvc.perform(put("/api/v1/customer-service/tickets/{ticketId}/close", ticketId)
                .header(HttpHeaders.AUTHORIZATION, alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(3));

        MvcResult notificationResult = mockMvc.perform(post("/api/v1/notifications")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Promo " + UUID.randomUUID()
                    + "\",\"content\":\"A private message for Alice.\",\"type\":\"promo\","
                    + "\"targetType\":\"USER\",\"targetUserId\":2,\"bizType\":\"MARKETING\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.targetUserId").value(2))
            .andExpect(jsonPath("$.data.type").value("PROMO"))
            .andReturn();
        long notificationId = json(notificationResult).path("data").path("notificationId").asLong();

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                .header(HttpHeaders.AUTHORIZATION, alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unreadCount").isNumber());

        mockMvc.perform(put("/api/v1/notifications/{notificationId}/read", notificationId)
                .header(HttpHeaders.AUTHORIZATION, alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.read").value(true));

        MvcResult promoReadList = mockMvc.perform(get("/api/v1/notifications")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .param("type", "PROMO")
                .param("read", "true"))
            .andExpect(status().isOk())
            .andReturn();
        org.junit.jupiter.api.Assertions.assertTrue(
            containsLong(json(promoReadList).path("data").path("list"), "notificationId", notificationId));

        mockMvc.perform(put("/api/v1/notifications/read-all")
                .header(HttpHeaders.AUTHORIZATION, alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.readCount").isNumber());

        mockMvc.perform(put("/api/v1/notifications/{notificationId}/status", notificationId)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(0));
    }

    private String bearerToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return "Bearer " + json(result).path("data").path("token").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private boolean containsLong(JsonNode list, String field, long value) {
        for (JsonNode item : list) {
            if (item.path(field).asLong(Long.MIN_VALUE) == value) {
                return true;
            }
        }
        return false;
    }

    private boolean containsText(JsonNode list, String value) {
        for (JsonNode item : list) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private String hmacSha256(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
}
