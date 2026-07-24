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
class UserControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void listShouldUseUnifiedResult() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                .header(HttpHeaders.AUTHORIZATION, bearerToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("查询成功"))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    void pageShouldUsePageResult() throws Exception {
        mockMvc.perform(get("/api/v1/users/page")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .param("pageNum", "1")
                .param("pageSize", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.total").exists())
            .andExpect(jsonPath("$.data.pageNum").value(1))
            .andExpect(jsonPath("$.data.pageSize").value(10))
            .andExpect(jsonPath("$.data.list").isArray());
    }

    @Test
    void createShouldRejectInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"\",\"email\":\"bad-email\",\"age\":-1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void listShouldRejectNonAdminUser() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                .header(HttpHeaders.AUTHORIZATION, bearerToken("alice", "alice123")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void getShouldReturnNotFoundForMissingUser() throws Exception {
        mockMvc.perform(get("/api/v1/users/999999")
                .header(HttpHeaders.AUTHORIZATION, bearerToken()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("用户不存在"));
    }

    @Test
    void unknownApiShouldUseUnifiedNotFoundResult() throws Exception {
        mockMvc.perform(get("/api/v1/not-exists")
                .header(HttpHeaders.AUTHORIZATION, bearerToken()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("资源不存在"));
    }

    private String bearerToken() throws Exception {
        return bearerToken("admin", "admin123");
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
