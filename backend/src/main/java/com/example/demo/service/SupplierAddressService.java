package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应商管理服务。
 * 地址相关方法已迁移到 {@link AddressService}（v1.2 §4.3）。
 */
@Service
public class SupplierAddressService {

    private final JdbcTemplate jdbcTemplate;

    public SupplierAddressService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> listSuppliers(String name, String contactPerson, Integer status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM pur_supplier WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(name)) {
            sql.append(" AND supplier_name ILIKE ?");
            args.add("%" + name.trim() + "%");
        }
        if (StringUtils.hasText(contactPerson)) {
            sql.append(" AND contact_person ILIKE ?");
            args.add("%" + contactPerson.trim() + "%");
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY create_time DESC, id DESC");
        List<Map<String, Object>> list = jdbcTemplate.query(sql.toString(), this::supplierRow, args.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", list.size());
        result.put("list", list);
        return result;
    }

    public Map<String, Object> createSupplier(String supplierName, String contactPerson, String phone,
                                               String address, String email, String remark) {
        try {
            Long id = jdbcTemplate.queryForObject("""
                INSERT INTO pur_supplier(supplier_name, contact_person, phone, address, email, remark)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, supplierName, contactPerson, phone, address, email, remark);
            return jdbcTemplate.queryForObject("SELECT * FROM pur_supplier WHERE id=?", this::supplierRow, id);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "供应商名称已存在");
        }
    }

    private Map<String, Object> supplierRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("supplierId", rs.getLong("id"));
        row.put("supplierName", rs.getString("supplier_name"));
        row.put("contactPerson", rs.getString("contact_person"));
        row.put("phone", rs.getString("phone"));
        row.put("address", rs.getString("address"));
        row.put("email", rs.getString("email"));
        row.put("status", rs.getInt("status"));
        row.put("createTime", rs.getTimestamp("create_time").toLocalDateTime());
        return row;
    }
}