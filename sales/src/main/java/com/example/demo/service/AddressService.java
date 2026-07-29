package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import com.example.demo.security.CustomerIdProvider;
import com.fasterxml.jackson.core.type.TypeReference;
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
 * v1.2 §4.3 客户收货地址：以当前认证账号映射的 customer_id 为归属维度，
 * 越权返回 40301；默认地址最多 1 条；最后一条禁止删除。
 */
@Service
public class AddressService {

    private static final TypeReference<Map<String, Object>> ADDRESS_RESPONSE = new TypeReference<>() {};
    private static final TypeReference<Void> VOID_RESPONSE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final CustomerIdProvider customerIdProvider;
    private final SalesIdempotencyService idempotencyService;

    public AddressService(JdbcTemplate jdbcTemplate,
                          CustomerIdProvider customerIdProvider,
                          SalesIdempotencyService idempotencyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.customerIdProvider = customerIdProvider;
        this.idempotencyService = idempotencyService;
    }

    private long requireCustomerId() {
        return customerIdProvider.requireCurrentCustomerId();
    }

    private long requireCustomerIdForUpdate() {
        return customerIdProvider.requireCurrentCustomerIdForUpdate();
    }

    public List<Map<String, Object>> list() {
        return jdbcTemplate.query("""
            SELECT id, customer_id, receiver_name, receiver_phone, province, city, district,
                   detail_address, is_default, create_time, update_time
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
            throwAddressAccessException(addressId);
        }
        return rows.getFirst();
    }

    @Transactional
    public Map<String, Object> create(String receiverName, String receiverPhone,
                                      String province, String city, String district,
                                      String detailAddress, Boolean isDefault,
                                      String idempotencyKey) {
        long customerId = requireCustomerIdForUpdate();
        AddressCreateCommand command = new AddressCreateCommand(
            receiverName, receiverPhone, province, city, district, detailAddress, isDefault);
        return idempotencyService.execute(
            "address:create", customerId, idempotencyKey, command, ADDRESS_RESPONSE,
            () -> createAddress(customerId, command));
    }

    private Map<String, Object> createAddress(long customerId, AddressCreateCommand command) {
        String receiverName = command.receiverName();
        String receiverPhone = command.receiverPhone();
        String province = command.province();
        String city = command.city();
        String district = command.district();
        String detailAddress = command.detailAddress();
        Boolean isDefault = command.isDefault();
        validatePhone(receiverPhone);
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
        return jdbcTemplate.queryForObject(
            "SELECT * FROM crm_address WHERE id=? AND customer_id=? AND deleted=0",
            this::addressRow, id, customerId);
    }

    @Transactional
    public Map<String, Object> update(Long addressId,
                                      String receiverName, String receiverPhone,
                                      String province, String city, String district,
                                      String detailAddress, Boolean isDefault,
                                      String idempotencyKey) {
        long customerId = requireCustomerIdForUpdate();
        AddressUpdateCommand command = new AddressUpdateCommand(
            addressId, receiverName, receiverPhone, province, city, district, detailAddress, isDefault);
        return idempotencyService.execute(
            "address:update", customerId, idempotencyKey, command, ADDRESS_RESPONSE,
            () -> updateAddress(customerId, command));
    }

    private Map<String, Object> updateAddress(long customerId, AddressUpdateCommand command) {
        Long addressId = command.addressId();
        String receiverName = command.receiverName();
        String receiverPhone = command.receiverPhone();
        String province = command.province();
        String city = command.city();
        String district = command.district();
        String detailAddress = command.detailAddress();
        Boolean isDefault = command.isDefault();
        validatePhone(receiverPhone);
        requireOwnedActiveAddressForUpdate(addressId, customerId);
        Integer referenced = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ord_order WHERE address_id=?",
            Integer.class, addressId);
        boolean snapshotted = referenced != null && referenced > 0;
        StringBuilder sql = new StringBuilder("UPDATE crm_address SET update_time=CURRENT_TIMESTAMP");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(receiverName)) {
            sql.append(", receiver_name=?");
            args.add(receiverName);
        }
        if (StringUtils.hasText(receiverPhone)) {
            sql.append(", receiver_phone=?");
            args.add(receiverPhone);
        }
        if (!snapshotted) {
            if (StringUtils.hasText(province)) {
                sql.append(", province=?");
                args.add(province);
            }
            if (StringUtils.hasText(city)) {
                sql.append(", city=?");
                args.add(city);
            }
            if (StringUtils.hasText(district)) {
                sql.append(", district=?");
                args.add(district);
            }
            if (StringUtils.hasText(detailAddress)) {
                sql.append(", detail_address=?");
                args.add(detailAddress);
            }
        } else if (StringUtils.hasText(province)
            || StringUtils.hasText(city)
            || StringUtils.hasText(district)
            || StringUtils.hasText(detailAddress)
            || isDefault != null) {
            throw new BusinessException(
                ApiErrorCode.BAD_REQUEST,
                "地址已被订单引用，仅允许修改收货人姓名和手机号");
        }
        if (isDefault != null) {
            if (Boolean.TRUE.equals(isDefault)) {
                jdbcTemplate.update(
                    "UPDATE crm_address SET is_default=FALSE, update_time=CURRENT_TIMESTAMP WHERE customer_id=? AND deleted=0 AND id<>?",
                    customerId, addressId);
            }
            sql.append(Boolean.TRUE.equals(isDefault)
                ? ", is_default=TRUE"
                : ", is_default=FALSE");
        }
        sql.append(" WHERE id=? AND customer_id=? AND deleted=0");
        args.add(addressId);
        args.add(customerId);
        jdbcTemplate.update(sql.toString(), args.toArray());
        return jdbcTemplate.queryForObject(
            "SELECT * FROM crm_address WHERE id=? AND customer_id=? AND deleted=0",
            this::addressRow, addressId, customerId);
    }

    @Transactional
    public void delete(Long addressId, String idempotencyKey) {
        long customerId = requireCustomerIdForUpdate();
        AddressResourceCommand command = new AddressResourceCommand(addressId);
        idempotencyService.execute(
            "address:delete", customerId, idempotencyKey, command, VOID_RESPONSE,
            () -> {
                deleteAddress(customerId, addressId);
                return null;
            });
    }

    private void deleteAddress(long customerId, Long addressId) {
        boolean isDefault = requireOwnedActiveAddressForUpdate(addressId, customerId);
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crm_address WHERE customer_id=? AND deleted=0",
            Integer.class, customerId);
        if (count != null && count <= 1) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "客户最后一条地址不允许删除");
        }
        jdbcTemplate.update("""
            UPDATE crm_address
            SET deleted=1, is_default=FALSE, update_time=CURRENT_TIMESTAMP
            WHERE id=? AND customer_id=? AND deleted=0
            """, addressId, customerId);
        if (isDefault) {
            Long fallback = jdbcTemplate.queryForObject("""
                SELECT id FROM crm_address WHERE customer_id=? AND deleted=0
                ORDER BY create_time ASC, id ASC LIMIT 1
                """, Long.class, customerId);
            jdbcTemplate.update(
                """
                UPDATE crm_address
                SET is_default=TRUE, update_time=CURRENT_TIMESTAMP
                WHERE id=? AND customer_id=? AND deleted=0
                """,
                fallback, customerId);
        }
    }

    @Transactional
    public Map<String, Object> setDefault(Long addressId, String idempotencyKey) {
        long customerId = requireCustomerIdForUpdate();
        AddressResourceCommand command = new AddressResourceCommand(addressId);
        return idempotencyService.execute(
            "address:set-default", customerId, idempotencyKey, command, ADDRESS_RESPONSE,
            () -> setDefaultAddress(customerId, addressId));
    }

    private Map<String, Object> setDefaultAddress(long customerId, Long addressId) {
        requireOwnedActiveAddressForUpdate(addressId, customerId);
        jdbcTemplate.update(
            "UPDATE crm_address SET is_default=FALSE, update_time=CURRENT_TIMESTAMP WHERE customer_id=? AND deleted=0 AND id<>?",
            customerId, addressId);
        jdbcTemplate.update(
            """
            UPDATE crm_address
            SET is_default=TRUE, update_time=CURRENT_TIMESTAMP
            WHERE id=? AND customer_id=? AND deleted=0
            """,
            addressId, customerId);
        return jdbcTemplate.queryForObject(
            "SELECT * FROM crm_address WHERE id=? AND customer_id=? AND deleted=0",
            this::addressRow, addressId, customerId);
    }

    private boolean requireOwnedActiveAddressForUpdate(Long addressId, long customerId) {
        List<Boolean> rows = jdbcTemplate.query(
            """
            SELECT is_default
            FROM crm_address
            WHERE id=? AND customer_id=? AND deleted=0
            FOR UPDATE
            """,
            (rs, rowNum) -> rs.getBoolean("is_default"), addressId, customerId);
        if (rows.isEmpty()) {
            throwAddressAccessException(addressId);
        }
        return rows.getFirst();
    }

    private void throwAddressAccessException(Long addressId) {
        Integer active = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crm_address WHERE id=? AND deleted=0",
            Integer.class, addressId);
        if (active != null && active > 0) {
            throw new BusinessException(ApiErrorCode.RESOURCE_FORBIDDEN, "地址不属于当前客户");
        }
        throw new BusinessException(ApiErrorCode.NOT_FOUND, "地址不存在");
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

    private record AddressCreateCommand(
        String receiverName,
        String receiverPhone,
        String province,
        String city,
        String district,
        String detailAddress,
        Boolean isDefault) {
    }

    private record AddressUpdateCommand(
        Long addressId,
        String receiverName,
        String receiverPhone,
        String province,
        String city,
        String district,
        String detailAddress,
        Boolean isDefault) {
    }

    private record AddressResourceCommand(Long addressId) {
    }
}
