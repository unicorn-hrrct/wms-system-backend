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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

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

        mockMvc.perform(post("/api/v1/order/create")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orderId").value(orderId));

        mockMvc.perform(post("/api/v1/order/{orderId}/pay", orderId)
                .header(HttpHeaders.AUTHORIZATION, alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payType\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.payStatus").value(1));

        mockMvc.perform(post("/api/v1/order/{orderId}/ship", orderId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken("admin", "admin123"))
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

        mockMvc.perform(post("/api/v1/order/aftersale")
                .header(HttpHeaders.AUTHORIZATION, alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":" + orderId + ",\"orderItemId\":" + orderItemId
                    + ",\"type\":1,\"reason\":\"测试退货\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(0));
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
}
