package com.example.demo.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationLogWriter {

    private final JdbcTemplate jdbcTemplate;

    public OperationLogWriter(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(Long userId, String username, String module, String operation, String method,
                      String requestUrl, String ip, long costMs, int status, String errorMsg) {
        jdbcTemplate.update("""
            INSERT INTO sys_op_log(user_id,username,module,operation,method,request_url,ip,cost_ms,status,error_msg)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """, userId, username, module, operation, method, requestUrl, ip, costMs, status,
            errorMsg == null ? null : errorMsg.substring(0, Math.min(errorMsg.length(), 1000)));
    }
}
