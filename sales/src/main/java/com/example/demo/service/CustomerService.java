package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.entity.Customer;
import com.example.demo.exception.BusinessException;
import com.example.demo.mapper.CustomerMapper;
import com.example.demo.security.CurrentUserProvider;
import com.example.demo.security.CustomerIdProvider;
import com.example.demo.vo.CustomerResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * v1.2 §4.0 客户档案：从 sys_user 派生/补建 crm_customer。
 */
@Service
public class CustomerService {

    private static final TypeReference<CustomerResponse> CUSTOMER_RESPONSE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final CustomerMapper customerMapper;
    private final CurrentUserProvider currentUser;
    private final CustomerIdProvider customerIdProvider;
    private final SalesIdempotencyService idempotencyService;

    public CustomerService(JdbcTemplate jdbcTemplate,
                           CustomerMapper customerMapper,
                           CurrentUserProvider currentUser,
                           CustomerIdProvider customerIdProvider,
                           SalesIdempotencyService idempotencyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.customerMapper = customerMapper;
        this.currentUser = currentUser;
        this.customerIdProvider = customerIdProvider;
        this.idempotencyService = idempotencyService;
    }

    @Transactional
    public CustomerResponse getOrCreateCurrent() {
        Long userId = currentUser.requireUserId();
        List<Customer> existing = findActiveByUserId(userId);
        if (!existing.isEmpty()) {
            return toResponse(existing.getFirst());
        }

        jdbcTemplate.update("""
            INSERT INTO crm_customer(user_id, nickname, phone, email, level)
            SELECT id, COALESCE(NULLIF(BTRIM(nickname), ''), username), phone, email, 'NORMAL'
            FROM t_user
            WHERE id=? AND deleted=0
            ON CONFLICT (user_id) DO NOTHING
            """, userId);

        return findActiveByUserId(userId).stream()
            .findFirst()
            .map(this::toResponse)
            .orElseThrow(() -> new BusinessException(
                ApiErrorCode.NOT_FOUND, "客户档案不存在或已删除"));
    }

    @Transactional
    public CustomerResponse updateCurrent(
        String nickname, String phone, String email, String idempotencyKey) {
        getOrCreateCurrent();
        long customerId = customerIdProvider.requireCurrentCustomerIdForUpdate();
        CustomerUpdateCommand command = new CustomerUpdateCommand(nickname, phone, email);
        return idempotencyService.execute(
            "customer:update", customerId, idempotencyKey, command, CUSTOMER_RESPONSE,
            () -> updateCustomer(customerId, command));
    }

    private CustomerResponse updateCustomer(long customerId, CustomerUpdateCommand command) {
        StringBuilder sql = new StringBuilder("UPDATE crm_customer SET update_time=CURRENT_TIMESTAMP");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (StringUtils.hasText(command.nickname())) {
            sql.append(", nickname=?");
            args.add(command.nickname());
        }
        if (StringUtils.hasText(command.phone())) {
            throw new BusinessException(
                ApiErrorCode.BAD_REQUEST,
                "手机号变更需通过验证码流程，当前接口暂不支持");
        }
        if (StringUtils.hasText(command.email())) {
            sql.append(", email=?");
            args.add(command.email());
        }
        sql.append(" WHERE id=? AND deleted=0");
        args.add(customerId);
        sql.append(" RETURNING id, user_id, nickname, phone, email, level, registered_at");
        return jdbcTemplate.queryForObject(
            sql.toString(), this::customerResponseRow, args.toArray());
    }

    private record CustomerUpdateCommand(String nickname, String phone, String email) {
    }

    private List<Customer> findActiveByUserId(Long userId) {
        return customerMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Customer>()
                .eq("user_id", userId)
                .eq("deleted", 0));
    }

    private CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(
            customer.getId(),
            customer.getUserId(),
            customer.getNickname(),
            customer.getPhone(),
            customer.getEmail(),
            customer.getLevel(),
            customer.getRegisteredAt());
    }

    private CustomerResponse customerResponseRow(ResultSet rs, int rowNum) throws SQLException {
        var registeredAt = rs.getTimestamp("registered_at");
        return new CustomerResponse(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("nickname"),
            rs.getString("phone"),
            rs.getString("email"),
            rs.getString("level"),
            registeredAt == null ? null : registeredAt.toLocalDateTime());
    }
}
