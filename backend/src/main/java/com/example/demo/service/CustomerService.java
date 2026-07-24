package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.entity.Customer;
import com.example.demo.exception.BusinessException;
import com.example.demo.mapper.CustomerMapper;
import com.example.demo.security.CurrentUserProvider;
import com.example.demo.vo.CustomerResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * v1.2 §4.0 客户档案：从 sys_user 派生/补建 crm_customer。
 */
@Service
public class CustomerService {

    private final JdbcTemplate jdbcTemplate;
    private final CustomerMapper customerMapper;
    private final CurrentUserProvider currentUser;

    public CustomerService(JdbcTemplate jdbcTemplate, CustomerMapper customerMapper, CurrentUserProvider currentUser) {
        this.jdbcTemplate = jdbcTemplate;
        this.customerMapper = customerMapper;
        this.currentUser = currentUser;
    }

    @Transactional
    public CustomerResponse getOrCreateCurrent() {
        Long userId = currentUser.requireUserId();
        List<Customer> existing = customerMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Customer>()
                .eq("user_id", userId).eq("deleted", 0));
        Customer customer;
        if (existing.isEmpty()) {
            customer = new Customer();
            customer.setUserId(userId);
            Map<String, Object> profile = jdbcTemplate.queryForMap(
                "SELECT username, nickname, phone, email FROM t_user WHERE id=? AND deleted=0", userId);
            customer.setNickname((String) profile.getOrDefault("nickname", profile.get("username")));
            customer.setPhone((String) profile.get("phone"));
            customer.setEmail((String) profile.get("email"));
            customer.setLevel("NORMAL");
            try {
                customerMapper.insert(customer);
            } catch (DuplicateKeyException race) {
                customer = customerMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Customer>()
                        .eq("user_id", userId).eq("deleted", 0)).getFirst();
            }
        } else {
            customer = existing.getFirst();
        }
        return toResponse(customer);
    }

    @Transactional
    public CustomerResponse updateCurrent(String nickname, String phone, String email) {
        CustomerResponse current = getOrCreateCurrent();
        Long customerId = current.customerId();
        StringBuilder sql = new StringBuilder("UPDATE crm_customer SET update_time=CURRENT_TIMESTAMP");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (StringUtils.hasText(nickname)) {
            sql.append(", nickname=?");
            args.add(nickname);
        }
        if (StringUtils.hasText(phone)) {
            if (!phone.matches("^1[3-9]\\d{9}$")) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "手机号格式不合法");
            }
            sql.append(", phone=?");
            args.add(phone);
        }
        if (StringUtils.hasText(email)) {
            sql.append(", email=?");
            args.add(email);
        }
        sql.append(" WHERE id=? AND deleted=0");
        args.add(customerId);
        jdbcTemplate.update(sql.toString(), args.toArray());
        return getOrCreateCurrent();
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
}