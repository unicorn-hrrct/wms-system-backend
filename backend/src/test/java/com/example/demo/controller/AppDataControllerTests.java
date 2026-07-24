package com.example.demo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AppDataControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void appApisShouldRejectAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/v1/app/products"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void appProductApisShouldReturnCatalogData() throws Exception {
        String token = bearerToken("admin", "admin123");

        mockMvc.perform(get("/api/v1/app/categories/tree")
                .header(HttpHeaders.AUTHORIZATION, token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/api/v1/app/products")
                .header(HttpHeaders.AUTHORIZATION, token)
                .param("keyword", "SP001")
                .param("pageNum", "1")
                .param("pageSize", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.pageNum").value(1))
            .andExpect(jsonPath("$.data.list[0].productCode").value("SP001"))
            .andExpect(jsonPath("$.data.list[0].productName").value("iPhone 15 Pro"));

        mockMvc.perform(get("/api/v1/app/products/1001")
                .header(HttpHeaders.AUTHORIZATION, token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.productCode").value("SP001"))
            .andExpect(jsonPath("$.data.skuList[0].skuCode").value("SP001-BLACK-256"));
    }

    @Test
    void appInventoryApisShouldReturnWarehouseAndStockData() throws Exception {
        String token = bearerToken("alice", "alice123");

        mockMvc.perform(get("/api/v1/app/warehouses")
                .header(HttpHeaders.AUTHORIZATION, token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].warehouseCode").value("WH-A"));

        mockMvc.perform(get("/api/v1/app/locations/tree")
                .header(HttpHeaders.AUTHORIZATION, token)
                .param("warehouseId", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].warehouseId").value(1));

        mockMvc.perform(get("/api/v1/app/inventory")
                .header(HttpHeaders.AUTHORIZATION, token)
                .param("keyword", "SP001-BLACK-256"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.list[0].skuCode").value("SP001-BLACK-256"))
            .andExpect(jsonPath("$.data.list[0].productName").value("iPhone 15 Pro"))
            .andExpect(jsonPath("$.data.list[0].totalStock").value(118));

        mockMvc.perform(get("/api/v1/app/stock-logs")
                .header(HttpHeaders.AUTHORIZATION, token)
                .param("skuId", "2001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.list[0].skuCode").value("SP001-BLACK-256"))
            .andExpect(jsonPath("$.data.list[0].sourceNo").value("SO20260721001"));
    }

    private String bearerToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = objectMapper.readTree(body).path("data").path("token").asText();
        return "Bearer " + token;
    }
}
