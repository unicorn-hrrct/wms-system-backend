package com.example.demo.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * v1.2 §4.0：从 JWT 的 userId 派生 customerId。
 * 避免在 CurrentUserProvider 里直接依赖 JdbcTemplate（保持 security 包纯净）。
 */
@Component
public class CustomerIdProvider {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserProvider currentUser;

    public CustomerIdProvider(JdbcTemplate jdbcTemplate, CurrentUserProvider currentUser) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
    }

    public Long getCurrentCustomerId() {
        Long userId = currentUser.requireUserId();
        try {
            Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_customer WHERE user_id=? AND deleted=0", Long.class, userId);
            return customerId;
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            return null;
        }
    }
}