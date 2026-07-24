package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SystemDataService {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public SystemDataService(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> dict(String dictType) {
        String key = "wms:dict:" + dictType;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            try { return objectMapper.readValue(cached, new TypeReference<List<Map<String, Object>>>() {}); }
            catch (Exception ignored) { redisTemplate.delete(key); }
        }
        Integer typeCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_dict_type WHERE dict_type=? AND status=0", Integer.class, dictType);
        if (typeCount == null || typeCount == 0) throw new BusinessException(ApiErrorCode.NOT_FOUND, "字典类型不存在");
        List<Map<String, Object>> list = jdbcTemplate.query("""
            SELECT dict_label,dict_value,css_class FROM sys_dict_data
            WHERE dict_type=? AND status=0 ORDER BY sort_order,id
            """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("dictLabel", rs.getString("dict_label"));
            row.put("dictValue", rs.getString("dict_value")); row.put("cssClass", rs.getString("css_class")); return row;
        }, dictType);
        try { redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(list), Duration.ofMinutes(30)); }
        catch (Exception ignored) { }
        return list;
    }

    public List<Map<String, Object>> configs() {
        String key = "wms:system:configs";
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            try { return objectMapper.readValue(cached, new TypeReference<List<Map<String, Object>>>() {}); }
            catch (Exception ignored) { redisTemplate.delete(key); }
        }
        List<Map<String, Object>> list = jdbcTemplate.query("""
            SELECT config_key,config_name,config_value FROM sys_config WHERE status=0 ORDER BY id
            """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("configKey", rs.getString("config_key"));
            row.put("configName", rs.getString("config_name")); row.put("configValue", rs.getString("config_value")); return row;
        });
        try { redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(list), Duration.ofMinutes(5)); }
        catch (Exception ignored) { }
        return list;
    }

    public Map<String, Object> operationLogs(String username, String module, String operation,
                                              LocalDate startDate, LocalDate endDate, int pageNum, int pageSize) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(username)) { where.append(" AND username ILIKE ?"); args.add("%" + username.trim() + "%"); }
        if (StringUtils.hasText(module)) { where.append(" AND module ILIKE ?"); args.add("%" + module.trim() + "%"); }
        if (StringUtils.hasText(operation)) { where.append(" AND operation ILIKE ?"); args.add("%" + operation.trim() + "%"); }
        if (startDate != null) { where.append(" AND operate_time>=?"); args.add(Date.valueOf(startDate)); }
        if (endDate != null) { where.append(" AND operate_time<?"); args.add(Date.valueOf(endDate.plusDays(1))); }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_op_log" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(pageSize); pageArgs.add((pageNum-1)*pageSize);
        List<Map<String, Object>> list = jdbcTemplate.query("SELECT * FROM sys_op_log" + where + " ORDER BY operate_time DESC,id DESC LIMIT ? OFFSET ?",
            (rs, rowNum) -> { Map<String,Object> row=new LinkedHashMap<>(); row.put("logId",rs.getLong("id"));
                row.put("username",rs.getString("username"));row.put("module",rs.getString("module"));row.put("operation",rs.getString("operation"));
                row.put("method",rs.getString("method"));row.put("requestUrl",rs.getString("request_url"));row.put("ip",rs.getString("ip"));
                row.put("operateTime",rs.getTimestamp("operate_time").toLocalDateTime());row.put("status",rs.getInt("status"));row.put("errorMsg",rs.getString("error_msg"));return row; }, pageArgs.toArray());
        Map<String,Object> response=new LinkedHashMap<>();response.put("total",total==null?0:total);response.put("pageNum",pageNum);response.put("pageSize",pageSize);
        response.put("pages",total==null?0:(total+pageSize-1)/pageSize);response.put("list",list);return response;
    }
}
