package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.dto.MerchantApplyRequest;
import com.example.demo.dto.MerchantAuditRequest;
import com.example.demo.exception.BusinessException;
import com.example.demo.security.CurrentUserProvider;
import com.example.demo.vo.MerchantApplicationResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class MerchantOnboardingService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserProvider currentUser;

    public MerchantOnboardingService(JdbcTemplate jdbcTemplate, CurrentUserProvider currentUser) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
    }

    @Transactional
    public MerchantApplicationResponse apply(MerchantApplyRequest request, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "缺少 Idempotency-Key 请求头");
        }
        Long userId = currentUser.requireUserId();
        List<MerchantApplicationResponse> existingByKey = jdbcTemplate.query(baseSelect()
                + " WHERE a.user_id=? AND a.idempotency_key=?",
            this::applicationRow, userId, idempotencyKey);
        if (!existingByKey.isEmpty()) {
            return existingByKey.getFirst();
        }
        Integer activeCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM mer_merchant_application
            WHERE user_id=? AND status IN (0,1)
            """, Integer.class, userId);
        if (activeCount != null && activeCount > 0) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "当前用户已存在待审核或已通过的商家入驻申请");
        }
        String merchantCode = normalizeCode(request.merchantCode());
        if (StringUtils.hasText(merchantCode)) {
            ensureMerchantCodeAvailable(merchantCode, null);
        }
        String applicationNo = businessNo("MA");
        Long applicationId;
        try {
            applicationId = jdbcTemplate.queryForObject("""
                INSERT INTO mer_merchant_application(application_no, user_id, merchant_name, merchant_code,
                                                     contact_name, contact_phone, contact_email, license_no,
                                                     license_image, business_scope, address, status,
                                                     idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?) RETURNING id
                """, Long.class, applicationNo, userId, request.merchantName(), merchantCode,
                request.contactName(), request.contactPhone(), request.contactEmail(), request.licenseNo(),
                request.licenseImage(), request.businessScope(), request.address(), idempotencyKey);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ApiErrorCode.IDEMPOTENCY_CONFLICT, "商家入驻申请重复提交");
        }
        return detail(applicationId);
    }

    public MerchantApplicationResponse myApplication() {
        Long userId = currentUser.requireUserId();
        List<MerchantApplicationResponse> rows = jdbcTemplate.query(baseSelect()
                + " WHERE a.user_id=? ORDER BY a.create_time DESC, a.id DESC LIMIT 1",
            this::applicationRow, userId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "商家入驻申请不存在");
        }
        return rows.getFirst();
    }

    public Map<String, Object> list(Integer status, String merchantName, String applicationNo,
                                    LocalDateTime startDate, LocalDateTime endDate,
                                    int pageNum, int pageSize) {
        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            where.append(" AND a.status=?");
            args.add(status);
        }
        if (StringUtils.hasText(merchantName)) {
            where.append(" AND a.merchant_name ILIKE ?");
            args.add("%" + merchantName.trim() + "%");
        }
        if (StringUtils.hasText(applicationNo)) {
            where.append(" AND a.application_no ILIKE ?");
            args.add("%" + applicationNo.trim() + "%");
        }
        if (startDate != null) {
            where.append(" AND a.create_time >= ?");
            args.add(startDate);
        }
        if (endDate != null) {
            where.append(" AND a.create_time < ?");
            args.add(endDate);
        }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM mer_merchant_application a " + where,
            Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) (pageNum - 1) * pageSize);
        List<MerchantApplicationResponse> list = jdbcTemplate.query(baseSelect() + " " + where
                + " ORDER BY a.create_time DESC, a.id DESC LIMIT ? OFFSET ?",
            this::applicationRow, pageArgs.toArray());
        long safeTotal = total == null ? 0 : total;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", safeTotal);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        result.put("pages", (safeTotal + pageSize - 1) / pageSize);
        result.put("list", list);
        return result;
    }

    public MerchantApplicationResponse detail(Long applicationId) {
        List<MerchantApplicationResponse> rows = jdbcTemplate.query(baseSelect() + " WHERE a.id=?",
            this::applicationRow, applicationId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "商家入驻申请不存在");
        }
        return rows.getFirst();
    }

    @Transactional
    public MerchantApplicationResponse audit(Long applicationId, MerchantAuditRequest request) {
        if (request.auditStatus() == null || (request.auditStatus() != 1 && request.auditStatus() != 2)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "auditStatus 只能是 1/2");
        }
        Map<String, Object> application = requireApplicationForUpdate(applicationId);
        int status = ((Number) application.get("status")).intValue();
        if (status != 0) {
            throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID, "只有待审核入驻申请可审核");
        }
        if (request.auditStatus() == 2 && !StringUtils.hasText(request.auditRemark())) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "驳回必须填写 auditRemark");
        }
        if (request.auditStatus() == 2) {
            jdbcTemplate.update("""
                UPDATE mer_merchant_application
                SET status=2, audit_remark=?, audited_at=CURRENT_TIMESTAMP, update_time=CURRENT_TIMESTAMP
                WHERE id=? AND status=0
                """, request.auditRemark(), applicationId);
            return detail(applicationId);
        }

        String ownerCode = normalizeCode(StringUtils.hasText(request.merchantCode())
            ? request.merchantCode() : (String) application.get("merchantCode"));
        if (!StringUtils.hasText(ownerCode)) {
            ownerCode = "MER" + applicationId;
        }
        ensureMerchantCodeAvailable(ownerCode, applicationId);
        Long ownerId = jdbcTemplate.queryForObject("""
            INSERT INTO own_owner(owner_code, owner_name, contact, phone, status)
            VALUES (?, ?, ?, ?, 0) RETURNING id
            """, Long.class, ownerCode, application.get("merchantName"),
            application.get("contactName"), application.get("contactPhone"));
        jdbcTemplate.update("""
            UPDATE mer_merchant_application
            SET status=1, merchant_code=?, owner_id=?, audit_remark=?,
                audited_at=CURRENT_TIMESTAMP, update_time=CURRENT_TIMESTAMP
            WHERE id=? AND status=0
            """, ownerCode, ownerId, request.auditRemark(), applicationId);
        bindWarehouses(ownerId, request.warehouseIds());
        grantSellerRole(((Number) application.get("userId")).longValue());
        return detail(applicationId);
    }

    private void bindWarehouses(Long ownerId, List<Long> warehouseIds) {
        if (warehouseIds == null || warehouseIds.isEmpty()) {
            return;
        }
        for (Long warehouseId : warehouseIds.stream().distinct().toList()) {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sto_warehouse WHERE id=? AND status=0",
                Integer.class, warehouseId);
            if (count == null || count == 0) {
                throw new BusinessException(ApiErrorCode.NOT_FOUND, "仓库不存在或已停用: " + warehouseId);
            }
            jdbcTemplate.update("""
                INSERT INTO sto_owner_warehouse(owner_id, warehouse_id)
                VALUES (?, ?) ON CONFLICT(owner_id, warehouse_id) DO NOTHING
                """, ownerId, warehouseId);
        }
    }

    private void grantSellerRole(Long userId) {
        Long sellerRoleId = jdbcTemplate.queryForObject(
            "SELECT id FROM sys_role WHERE role_key='seller' AND status=0", Long.class);
        jdbcTemplate.update("""
            INSERT INTO sys_user_role(user_id, role_id)
            VALUES (?, ?) ON CONFLICT(user_id, role_id) DO NOTHING
            """, userId, sellerRoleId);
    }

    private void ensureMerchantCodeAvailable(String merchantCode, Long currentApplicationId) {
        Integer ownerCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM own_owner WHERE owner_code=?", Integer.class, merchantCode);
        if (ownerCount != null && ownerCount > 0) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "商家编码已存在");
        }
        List<Object> args = new ArrayList<>();
        args.add(merchantCode);
        String extra = "";
        if (currentApplicationId != null) {
            extra = " AND id<>?";
            args.add(currentApplicationId);
        }
        Integer applicationCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM mer_merchant_application WHERE merchant_code=? AND status IN (0,1)" + extra,
            Integer.class, args.toArray());
        if (applicationCount != null && applicationCount > 0) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "商家编码已被申请");
        }
    }

    private Map<String, Object> requireApplicationForUpdate(Long applicationId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
            SELECT id, application_no, user_id, merchant_name, merchant_code, contact_name, contact_phone,
                   status
            FROM mer_merchant_application
            WHERE id=? FOR UPDATE
            """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("applicationId", rs.getLong("id"));
            row.put("applicationNo", rs.getString("application_no"));
            row.put("userId", rs.getLong("user_id"));
            row.put("merchantName", rs.getString("merchant_name"));
            row.put("merchantCode", rs.getString("merchant_code"));
            row.put("contactName", rs.getString("contact_name"));
            row.put("contactPhone", rs.getString("contact_phone"));
            row.put("status", rs.getInt("status"));
            return row;
        }, applicationId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "商家入驻申请不存在");
        }
        return rows.getFirst();
    }

    private String baseSelect() {
        return """
            SELECT a.id application_id, a.application_no, a.user_id, u.username,
                   a.merchant_name, a.merchant_code, a.contact_name, a.contact_phone,
                   a.contact_email, a.license_no, a.license_image, a.business_scope,
                   a.address, a.status, a.audit_remark, a.create_time, a.audited_at,
                   a.update_time, a.owner_id, o.owner_code
            FROM mer_merchant_application a
            JOIN t_user u ON u.id=a.user_id
            LEFT JOIN own_owner o ON o.id=a.owner_id
            """;
    }

    private MerchantApplicationResponse applicationRow(ResultSet rs, int rowNum) throws SQLException {
        Long ownerId = rs.getObject("owner_id") == null ? null : rs.getLong("owner_id");
        List<Long> warehouseIds = ownerId == null ? List.of() : jdbcTemplate.query(
            "SELECT warehouse_id FROM sto_owner_warehouse WHERE owner_id=? ORDER BY warehouse_id",
            (warehouseRs, warehouseRow) -> warehouseRs.getLong(1), ownerId);
        Integer status = rs.getInt("status");
        return new MerchantApplicationResponse(
            rs.getLong("application_id"),
            rs.getString("application_no"),
            rs.getLong("user_id"),
            rs.getString("username"),
            rs.getString("merchant_name"),
            rs.getString("merchant_code"),
            rs.getString("contact_name"),
            rs.getString("contact_phone"),
            rs.getString("contact_email"),
            rs.getString("license_no"),
            rs.getString("license_image"),
            rs.getString("business_scope"),
            rs.getString("address"),
            status,
            statusText(status),
            rs.getString("audit_remark"),
            toLocalDateTime(rs.getTimestamp("create_time")),
            toLocalDateTime(rs.getTimestamp("audited_at")),
            toLocalDateTime(rs.getTimestamp("update_time")),
            ownerId,
            rs.getString("owner_code"),
            warehouseIds);
    }

    private String normalizeCode(String code) {
        return StringUtils.hasText(code) ? code.trim().toUpperCase(Locale.ROOT) : null;
    }

    private LocalDateTime toLocalDateTime(java.sql.Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private String statusText(Integer status) {
        return switch (status == null ? -1 : status) {
            case 0 -> "待审核";
            case 1 -> "已通过";
            case 2 -> "已驳回";
            default -> "未知";
        };
    }

    private String businessNo(String prefix) {
        return prefix + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
