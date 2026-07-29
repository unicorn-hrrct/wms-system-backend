package com.example.demo.security;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * v1.2 §4.0：把当前认证账号的 userId 映射为业务 customerId。
 * 登录主体由 Token 用户名重新加载，不直接采用 JWT 中的 userId claim。
 */
@Component
public class CustomerIdProvider {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserProvider currentUser;

    public CustomerIdProvider(JdbcTemplate jdbcTemplate, CurrentUserProvider currentUser) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
    }

    public long requireCurrentCustomerId() {
        return queryCurrentCustomerId("""
            SELECT id
            FROM crm_customer
            WHERE user_id=? AND deleted=0
            """);
    }

    public long requireCurrentCustomerIdForUpdate() {
        return queryCurrentCustomerId("""
            SELECT id
            FROM crm_customer
            WHERE user_id=? AND deleted=0
            FOR NO KEY UPDATE
            """);
    }

    private long queryCurrentCustomerId(String sql) {
        Long userId = currentUser.requireUserId();
        try {
            Long customerId = jdbcTemplate.queryForObject(sql, Long.class, userId);
            if (customerId == null) {
                throw customerNotFound();
            }
            return customerId;
        } catch (EmptyResultDataAccessException ex) {
            throw customerNotFound();
        }
    }

    private BusinessException customerNotFound() {
        return new BusinessException(ApiErrorCode.NOT_FOUND, "当前用户尚未建立客户档案");
    }
}
