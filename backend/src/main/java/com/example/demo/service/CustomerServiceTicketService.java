package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import com.example.demo.security.CurrentUserProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CustomerServiceTicketService {

    private static final Set<String> AGENT_ROLES = Set.of("admin", "seller");
    private static final DateTimeFormatter TICKET_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final JdbcTemplate jdbcTemplate;
    private final CustomerService customerService;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;

    public CustomerServiceTicketService(JdbcTemplate jdbcTemplate,
                                        CustomerService customerService,
                                        CurrentUserProvider currentUser,
                                        ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.customerService = customerService;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> create(String subject, String category, String content, List<String> images) {
        Long userId = currentUser.requireUserId();
        Long customerId = currentCustomerId();
        String normalizedCategory = StringUtils.hasText(category) ? category.trim().toUpperCase() : "GENERAL";
        String ticketNo = ticketNo();
        Long ticketId = jdbcTemplate.queryForObject("""
            INSERT INTO cs_ticket(ticket_no, customer_id, user_id, subject, category, status, priority,
                                  last_message, last_message_time)
            VALUES (?, ?, ?, ?, ?, 0, 1, ?, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, ticketNo, customerId, userId, subject.trim(), normalizedCategory, summary(content));
        jdbcTemplate.update("""
            INSERT INTO cs_ticket_message(ticket_id, sender_user_id, sender_type, content, images)
            VALUES (?, ?, 'CUSTOMER', ?, ?)
            """, ticketId, userId, content.trim(), imagesJson(images));
        return detail(ticketId);
    }

    public Map<String, Object> myTickets(Integer status, int pageNum, int pageSize) {
        Long customerId = currentCustomerId();
        StringBuilder where = new StringBuilder(" WHERE t.customer_id=?");
        List<Object> args = new ArrayList<>();
        args.add(customerId);
        if (status != null) {
            where.append(" AND t.status=?");
            args.add(status);
        }
        return ticketPage(where, args, pageNum, pageSize);
    }

    public Map<String, Object> allTickets(Integer status, String keyword, int pageNum, int pageSize) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            where.append(" AND t.status=?");
            args.add(status);
        }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (t.ticket_no ILIKE ? OR t.subject ILIKE ? OR c.nickname ILIKE ? OR c.phone ILIKE ?)");
            String value = "%" + keyword.trim() + "%";
            args.add(value);
            args.add(value);
            args.add(value);
            args.add(value);
        }
        return ticketPage(where, args, pageNum, pageSize);
    }

    public Map<String, Object> detail(Long ticketId) {
        Map<String, Object> ticket = requireVisibleTicket(ticketId);
        ticket.put("messages", messages(ticketId));
        return ticket;
    }

    @Transactional
    public Map<String, Object> reply(Long ticketId, String content, List<String> images) {
        Map<String, Object> ticket = requireVisibleTicket(ticketId);
        int currentStatus = ((Number) ticket.get("status")).intValue();
        if (currentStatus == 3) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "ticket is closed");
        }
        Long userId = currentUser.requireUserId();
        boolean agent = isAgent();
        String senderType = agent ? "AGENT" : "CUSTOMER";
        int nextStatus = agent ? 1 : (currentStatus == 2 ? 1 : currentStatus);
        jdbcTemplate.update("""
            INSERT INTO cs_ticket_message(ticket_id, sender_user_id, sender_type, content, images)
            VALUES (?, ?, ?, ?, ?)
            """, ticketId, userId, senderType, content.trim(), imagesJson(images));
        jdbcTemplate.update("""
            UPDATE cs_ticket
            SET status=?, last_message=?, last_message_time=CURRENT_TIMESTAMP, update_time=CURRENT_TIMESTAMP
            WHERE id=?
            """, nextStatus, summary(content), ticketId);
        return detail(ticketId);
    }

    @Transactional
    public Map<String, Object> close(Long ticketId) {
        Map<String, Object> ticket = requireVisibleTicket(ticketId);
        if (!isOwner(ticket)) {
            throw new BusinessException(ApiErrorCode.RESOURCE_FORBIDDEN);
        }
        jdbcTemplate.update("UPDATE cs_ticket SET status=3, update_time=CURRENT_TIMESTAMP WHERE id=?", ticketId);
        return detail(ticketId);
    }

    @Transactional
    public Map<String, Object> assign(Long ticketId, Long assigneeId) {
        requireAgentUser(assigneeId);
        int changed = jdbcTemplate.update("""
            UPDATE cs_ticket
            SET assigned_to=?, status=1, update_time=CURRENT_TIMESTAMP
            WHERE id=?
            """, assigneeId, ticketId);
        if (changed == 0) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "ticket not found");
        }
        return detail(ticketId);
    }

    @Transactional
    public Map<String, Object> updateStatus(Long ticketId, Integer status) {
        if (status == null || status < 0 || status > 3) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "status must be 0, 1, 2 or 3");
        }
        int changed = jdbcTemplate.update("UPDATE cs_ticket SET status=?, update_time=CURRENT_TIMESTAMP WHERE id=?", status, ticketId);
        if (changed == 0) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "ticket not found");
        }
        return detail(ticketId);
    }

    private Map<String, Object> ticketPage(StringBuilder where, List<Object> args, int pageNum, int pageSize) {
        Long total = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM cs_ticket t
            JOIN crm_customer c ON c.id=t.customer_id
            """ + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> list = jdbcTemplate.query(ticketSelect() + where
            + " ORDER BY t.update_time DESC, t.id DESC LIMIT ? OFFSET ?", this::ticketRow, pageArgs.toArray());
        return page(total == null ? 0 : total, pageNum, pageSize, list);
    }

    private Map<String, Object> requireVisibleTicket(Long ticketId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(ticketSelect() + " WHERE t.id=?", this::ticketRow, ticketId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "ticket not found");
        }
        Map<String, Object> ticket = rows.getFirst();
        if (!isAgent() && !isOwner(ticket)) {
            throw new BusinessException(ApiErrorCode.RESOURCE_FORBIDDEN);
        }
        return ticket;
    }

    private boolean isOwner(Map<String, Object> ticket) {
        Long customerId = currentCustomerId();
        return customerId.equals(((Number) ticket.get("customerId")).longValue());
    }

    private boolean isAgent() {
        return currentUser.requirePrincipal().getRoles().stream().anyMatch(AGENT_ROLES::contains);
    }

    private void requireAgentUser(Long userId) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM t_user u
            JOIN sys_user_role ur ON ur.user_id=u.id
            JOIN sys_role r ON r.id=ur.role_id
            WHERE u.id=? AND u.deleted=0 AND u.status=0 AND r.role_key IN ('admin', 'seller')
            """, Integer.class, userId);
        if (count == null || count == 0) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "assignee must be admin or seller");
        }
    }

    private List<Map<String, Object>> messages(Long ticketId) {
        return jdbcTemplate.query("""
            SELECT m.id message_id, m.ticket_id, m.sender_user_id, m.sender_type, u.nickname sender_name,
                   m.content, m.images, m.create_time
            FROM cs_ticket_message m
            JOIN t_user u ON u.id=m.sender_user_id
            WHERE m.ticket_id=?
            ORDER BY m.create_time ASC, m.id ASC
            """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("messageId", rs.getLong("message_id"));
            row.put("ticketId", rs.getLong("ticket_id"));
            row.put("senderUserId", rs.getLong("sender_user_id"));
            row.put("senderType", rs.getString("sender_type"));
            row.put("senderName", rs.getString("sender_name"));
            row.put("content", rs.getString("content"));
            row.put("images", images(rs.getString("images")));
            row.put("createTime", ts(rs, "create_time"));
            return row;
        }, ticketId);
    }

    private String ticketSelect() {
        return """
            SELECT t.id ticket_id, t.ticket_no, t.customer_id, t.user_id, c.nickname customer_name,
                   c.phone customer_phone, t.subject, t.category, t.status, t.priority, t.assigned_to,
                   a.nickname assignee_name, t.last_message, t.last_message_time, t.create_time, t.update_time
            FROM cs_ticket t
            JOIN crm_customer c ON c.id=t.customer_id
            LEFT JOIN t_user a ON a.id=t.assigned_to
            """;
    }

    private Map<String, Object> ticketRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ticketId", rs.getLong("ticket_id"));
        row.put("ticketNo", rs.getString("ticket_no"));
        row.put("customerId", rs.getLong("customer_id"));
        row.put("userId", rs.getLong("user_id"));
        row.put("customerName", rs.getString("customer_name"));
        row.put("customerPhone", rs.getString("customer_phone"));
        row.put("subject", rs.getString("subject"));
        row.put("category", rs.getString("category"));
        int status = rs.getInt("status");
        row.put("status", status);
        row.put("statusText", ticketStatusText(status));
        row.put("priority", rs.getInt("priority"));
        long assignedTo = rs.getLong("assigned_to");
        row.put("assignedTo", rs.wasNull() ? null : assignedTo);
        row.put("assigneeName", rs.getString("assignee_name"));
        row.put("lastMessage", rs.getString("last_message"));
        row.put("lastMessageTime", ts(rs, "last_message_time"));
        row.put("createTime", ts(rs, "create_time"));
        row.put("updateTime", ts(rs, "update_time"));
        return row;
    }

    private Long currentCustomerId() {
        return customerService.getOrCreateCurrent().customerId();
    }

    private String ticketNo() {
        return "CS" + LocalDateTime.now().format(TICKET_NO_TIME)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private String summary(String content) {
        String value = content == null ? "" : content.trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private String imagesJson(List<String> images) {
        try {
            return objectMapper.writeValueAsString(images == null ? List.of() : images);
        } catch (Exception ex) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "invalid images");
        }
    }

    private List<String> images(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Object ts(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private String ticketStatusText(int status) {
        return switch (status) {
            case 1 -> "PROCESSING";
            case 2 -> "RESOLVED";
            case 3 -> "CLOSED";
            default -> "OPEN";
        };
    }

    private Map<String, Object> page(long total, int pageNum, int pageSize, List<?> list) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("total", total);
        row.put("pageNum", pageNum);
        row.put("pageSize", pageSize);
        row.put("pages", (total + pageSize - 1) / pageSize);
        row.put("list", list);
        return row;
    }
}
