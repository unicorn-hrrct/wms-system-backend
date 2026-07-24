package com.example.demo.security;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * v1.2 §10.2.1：内部服务调用必须通过 {@code X-Service-Token} 头校验。
 * 常量时间比较，避免计时攻击；命中后置入 {@link ServicePrincipal}。
 */
@Component
public class ServiceTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Service-Token";

    private final String serviceToken;
    private final ObjectMapper objectMapper;

    public ServiceTokenAuthenticationFilter(@Value("${app.service.token:}") String serviceToken,
                                           ObjectMapper objectMapper) {
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 仅在请求携带 X-Service-Token 头时才校验；普通用户 JWT 走 JwtAuthenticationFilter
        String token = request.getHeader(HEADER);
        return token == null || token.isBlank();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (!constantTimeEquals(presented, serviceToken)) {
            writeUnauthorized(response);
            return;
        }
        ServicePrincipal principal = new ServicePrincipal();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            principal, "N/A", principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
            Result.error(ApiErrorCode.UNAUTHORIZED, "Service token 无效"));
    }
}