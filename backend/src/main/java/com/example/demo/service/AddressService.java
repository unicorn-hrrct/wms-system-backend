package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import com.example.demo.security.CurrentUserProvider;
import com.example.demo.security.CustomerIdProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.2 §4.3 客户收货地址：以 customer_id 为归属维度（从 JWT 派生），
 * 越权返回 40301；默认地址最多 1 条；最后一条禁止删除。
 */
@Service
public class AddressService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserProvider currentUser;
    private final CustomerIdProvider customerIdProvider;

    public AddressService(JdbcTemplate jdbcTemplate, CurrentUserProvider currentUser, CustomerIdProvider customerIdProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
        this.customerIdProvider = customerIdProvider;
    }

    private long requireCustomerId() {
        Long customerId = customerIdProvider.getCurrentCustomerId();
        if (customerId == null) {
            // 没有 crm_customer 记录则按当前用户 ID 兜底（向后兼容）
            customerId = currentUser.requireUserId();
        }
        return customerId;
    }

    public List<Map<String, Object>> list() {
        return jdbcTemplate.query("""
            SELECT id, customer_id, receiver_name, receiver_phone, province, city, district, detail_address,
                   is_default, create_time, update_time
            FROM crm_address
            WHERE customer_id = ? AND deleted = 0
            ORDER BY is_default DESC, create_time DESC, id DESC
            """, this::addressRow, requireCustomerId());
    }

    public Map<String, Object> get(Long addressId) {
        long customerId = requireCustomerId();
        List<Map<String, Object>> rows = jdbcTemplate.query(
            "SELECT * FROM crm_address WHERE id=? AND customer_id=? AND deleted=0",
            this::addressRow, addressId, customerId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.RESOURCE_FORBIDDEN, "地址不存在或不属于当前客户");
        }
        return rows.getFirst();
    }

    @Transactional
    public Map<String, Object> create(String receiverName, String receiverPhone,
                                      String province, String city, String district,
                                      String detailAddress, Boolean isDefault) {
        validatePhone(receiverPhone);
        long customerId = requireCustomerId();
        boolean makeDefault = Boolean.TRUE.equals(isDefault);
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crm_address WHERE customer_id=? AND deleted=0",
            Integer.class, customerId);
        if (count != null && count == 0) {
            makeDefault = true;
        }
        if (makeDefault) {
            jdbcTemplate.update(
                "UPDATE crm_address SET is_default=FALSE, update_time=CURRENT_TIMESTAMP WHERE customer_id=? AND deleted=0",
                customerId);
        }
        Long id = jdbcTemplate.queryForObject("""
            INSERT INTO crm_address(customer_id, receiver_name, receiver_phone, province, city, district, detail_address, is_default)
            VALUES (?,?,?,?,?,?,?,?) RETURNING id
            """, Long.class, customerId, receiverName, receiverPhone, province, city, district, detailAddress, makeDefault);
        return jdbcTemplate.queryForObject("SELECT * FROM crm_address WHERE id=?", this::addressRow, id);
    }

    @Transactional
    public Map<String, Object> update(Long addressId,
                                      String receiverName, String receiverPhone,
                                      String province, String city, String district,
                                      String detailAddress, Boolean isDefault) {
        long customerId = requireCustomerId();
        validatePhone(receiverPhone);
        // 校验归属
        Integer own = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crm_address WHERE id=? AND customer_id=? AND deleted=0",
            Integer.class, addressId, customerId);
        if (own == null || own == 0) {
            throw new BusinessException(ApiErrorCode.RESOURCE_FORBIDDEN, "地址不存在或不属于当前客户");
        }
        // 检测是否被订单引用；若已引用，地理字段视为快照，仅允许改 receiverName/receiverPhone
        Integer referenced = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ord_order WHERE address_id=?",
            Integer.class, addressId);
        boolean snapshotted = referenced != null && referenced > 0;
        StringBuilder sql = new StringBuilder("UPDATE crm_address SET update_time=CURRENT_TIMESTAMP");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(receiverName)) { sql.append(", receiver_name=?"); args.add(receiverName); }
        if (StringUtils.hasText(receiverPhone)) { sql.append(", receiver_phone=?"); args.add(receiverPhone); }
        if (!snapshotted) {
            if (StringUtils.hasText(province)) { sql.append(", province=?"); args.add(province); }
            if (StringUtils.hasText(city)) { sql.append(", city=?"); args.add(city); }
            if (StringUtils.hasText(district)) { sql.append(", district=?"); args.add(district); }
            if (StringUtils.hasText(detailAddress)) { sql.append(", detail_address=?"); args.add(detailAddress); }
        } else if (StringUtils.hasText(province) || StringUtils.hasText(city) || StringUtils.hasText(district) || StringUtils.hasText(detailAddress)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "地址已被订单引用，地理字段不可修改");
        }
        if (Boolean.TRUE.equals(isDefault)) {
            sql.append(", is_default=TRUE");
            jdbcTemplate.update(
                "UPDATE crm_address SET is_default=FALSE, update_time=CURRENT_TIMESTAMP WHERE customer_id=? AND deleted=0 AND id<>?",
                customerId, addressId);
        }
        sql.append(" WHERE id=? AND customer_id=? AND deleted=0");
        args.add(addressId); args.add(customerId);
        jdbcTemplate.update(sql.toString(), args.toArray());
        return jdbcTemplate.queryForObject("SELECT * FROM crm_address WHERE id=?", this::addressRow, addressId);
    }

    @Transactional
    public void delete(Long addressId) {
        long customerId = requireCustomerId();
        Integer own = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crm_address WHERE id=? AND customer_id=? AND deleted=0",
            Integer.class, addressId, customerId);
        if (own == null || own == 0) {
            throw new BusinessException(ApiErrorCode.RESOURCE_FORBIDDEN, "地址不存在或不属于当前客户");
        }
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crm_address WHERE customer_id=? AND deleted=0",
            Integer.class, customerId);
        if (count != null && count <= 1) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "客户最后一条地址不允许删除");
        }
        Boolean isDefault = jdbcTemplate.queryForObject(
            "SELECT is_default FROM crm_address WHERE id=?", Boolean.class, addressId);
        jdbcTemplate.update("UPDATE crm_address SET deleted=1, update_time=CURRENT_TIMESTAMP WHERE id=?", addressId);
        if (Boolean.TRUE.equals(isDefault)) {
            // 自动迁移：最早的另一条设为默认
            Long fallback = jdbcTemplate.queryForObject("""
                SELECT id FROM crm_address WHERE customer_id=? AND deleted=0
                ORDER BY create_time ASC, id ASC LIMIT 1
                """, Long.class, customerId);
            jdbcTemplate.update(
                "UPDATE crm_address SET is_default=TRUE, update_time=CURRENT_TIMESTAMP WHERE id=?",
                fallback);
        }
    }

    @Transactional
    public Map<String, Object> setDefault(Long addressId) {
        long customerId = requireCustomerId();
        Integer own = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crm_address WHERE id=? AND customer_id=? AND deleted=0",
            Integer.class, addressId, customerId);
        if (own == null || own == 0) {
            throw new BusinessException(ApiErrorCode.RESOURCE_FORBIDDEN, "地址不存在或不属于当前客户");
        }
        jdbcTemplate.update(
            "UPDATE crm_address SET is_default=FALSE, update_time=CURRENT_TIMESTAMP WHERE customer_id=? AND deleted=0 AND id<>?",
            customerId, addressId);
        jdbcTemplate.update(
            "UPDATE crm_address SET is_default=TRUE, update_time=CURRENT_TIMESTAMP WHERE id=?",
            addressId);
        return jdbcTemplate.queryForObject("SELECT * FROM crm_address WHERE id=?", this::addressRow, addressId);
    }

    private void validatePhone(String phone) {
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "手机号必须为中国大陆 11 位");
        }
    }

    private Map<String, Object> addressRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("addressId", rs.getLong("id"));
        row.put("customerId", rs.getLong("customer_id"));
        row.put("receiverName", rs.getString("receiver_name"));
        row.put("receiverPhone", rs.getString("receiver_phone"));
        row.put("province", rs.getString("province"));
        row.put("city", rs.getString("city"));
        row.put("district", rs.getString("district"));
        row.put("detailAddress", rs.getString("detail_address"));
        row.put("isDefault", rs.getBoolean("is_default"));
        row.put("createTime", rs.getTimestamp("create_time"));
        row.put("updateTime", rs.getTimestamp("update_time"));
        return row;
    }
}
