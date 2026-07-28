package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import com.example.demo.security.CurrentUserProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NotificationService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserProvider currentUser;

    public NotificationService(JdbcTemplate jdbcTemplate, CurrentUserProvider currentUser) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
    }

    public Map<String, Object> list(Boolean read, String type, int pageNum, int pageSize) {
        VisibleQuery query = visibleQuery(read, type);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + query.fromWhere(), Long.class, query.args().toArray());
        List<Object> pageArgs = new ArrayList<>(query.args());
        pageArgs.add(pageSize);
        pageArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> list = jdbcTemplate.query("""
            SELECT n.id notification_id, n.title, n.content, n.type, n.target_type, n.target_user_id,
                   n.target_role_key, n.biz_type, n.biz_id, n.publish_time, n.expire_time,
                   (r.id IS NOT NULL) is_read, r.read_time
            """ + " " + query.fromWhere() + " ORDER BY n.publish_time DESC, n.id DESC LIMIT ? OFFSET ?",
            this::visibleNotificationRow, pageArgs.toArray());
        return page(total == null ? 0 : total, pageNum, pageSize, list);
    }

    public Map<String, Object> unreadCount() {
        VisibleQuery query = visibleQuery(false, null);
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) " + query.fromWhere(), Long.class, query.args().toArray());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("unreadCount", count == null ? 0 : count);
        return row;
    }

    @Transactional
    public Map<String, Object> markRead(Long notificationId) {
        requireVisible(notificationId);
        jdbcTemplate.update("""
            INSERT INTO msg_notification_read(notification_id, user_id)
            VALUES (?, ?)
            ON CONFLICT(notification_id, user_id) DO UPDATE SET read_time=CURRENT_TIMESTAMP
            """, notificationId, currentUser.requireUserId());
        return readState(notificationId);
    }

    @Transactional
    public Map<String, Object> markAllRead() {
        VisibleQuery query = visibleQuery(false, null);
        List<Long> ids = jdbcTemplate.query("SELECT n.id " + query.fromWhere(),
            (rs, rowNum) -> rs.getLong("id"), query.args().toArray());
        Long userId = currentUser.requireUserId();
        for (Long id : ids) {
            jdbcTemplate.update("""
                INSERT INTO msg_notification_read(notification_id, user_id)
                VALUES (?, ?)
                ON CONFLICT(notification_id, user_id) DO UPDATE SET read_time=CURRENT_TIMESTAMP
                """, id, userId);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("readCount", ids.size());
        return row;
    }

    @Transactional
    public Map<String, Object> create(String title, String content, String type, String targetType,
                                      Long targetUserId, String targetRoleKey, String bizType,
                                      String bizId, LocalDateTime expireTime) {
        Target target = normalizeTarget(targetType, targetUserId, targetRoleKey);
        String notificationType = StringUtils.hasText(type) ? type.trim().toUpperCase() : "SYSTEM";
        Long id = jdbcTemplate.queryForObject("""
            INSERT INTO msg_notification(title, content, type, target_type, target_user_id, target_role_key,
                                         sender_id, biz_type, biz_id, status, publish_time, expire_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP, ?)
            RETURNING id
            """, Long.class, title.trim(), content.trim(), notificationType, target.targetType(),
            target.targetUserId(), target.targetRoleKey(), currentUser.requireUserId(), bizType, bizId, expireTime);
        return adminDetail(id);
    }

    @Transactional
    public Map<String, Object> updateStatus(Long notificationId, Integer status) {
        if (status == null || status < 0 || status > 1) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "status must be 0 or 1");
        }
        int changed = jdbcTemplate.update("UPDATE msg_notification SET status=? WHERE id=?", status, notificationId);
        if (changed == 0) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "notification not found");
        }
        return adminDetail(notificationId);
    }

    public void delete(Long notificationId) {
        int changed = jdbcTemplate.update("UPDATE msg_notification SET status=0 WHERE id=?", notificationId);
        if (changed == 0) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "notification not found");
        }
    }

    private void requireVisible(Long notificationId) {
        VisibleQuery query = visibleQuery(null, null);
        List<Object> args = new ArrayList<>(query.args());
        args.add(notificationId);
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) " + query.fromWhere() + " AND n.id=?",
            Long.class, args.toArray());
        if (count == null || count == 0) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "notification not found");
        }
    }

    private Map<String, Object> readState(Long notificationId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
            SELECT n.id notification_id, n.title, n.content, n.type, n.target_type, n.target_user_id,
                   n.target_role_key, n.biz_type, n.biz_id, n.publish_time, n.expire_time,
                   (r.id IS NOT NULL) is_read, r.read_time
            FROM msg_notification n
            LEFT JOIN msg_notification_read r ON r.notification_id=n.id AND r.user_id=?
            WHERE n.id=?
            """, this::visibleNotificationRow, currentUser.requireUserId(), notificationId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "notification not found");
        }
        return rows.getFirst();
    }

    private Map<String, Object> adminDetail(Long notificationId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
            SELECT n.id notification_id, n.title, n.content, n.type, n.target_type, n.target_user_id,
                   n.target_role_key, n.sender_id, u.username sender_username, n.biz_type, n.biz_id,
                   n.status, n.publish_time, n.expire_time, n.create_time
            FROM msg_notification n
            LEFT JOIN t_user u ON u.id=n.sender_id
            WHERE n.id=?
            """, this::adminNotificationRow, notificationId);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "notification not found");
        }
        return rows.getFirst();
    }

    private VisibleQuery visibleQuery(Boolean read, String type) {
        Long userId = currentUser.requireUserId();
        List<String> roles = currentUser.requirePrincipal().getRoles();
        StringBuilder fromWhere = new StringBuilder("""
            FROM msg_notification n
            LEFT JOIN msg_notification_read r ON r.notification_id=n.id AND r.user_id=?
            WHERE n.status=1
              AND (n.expire_time IS NULL OR n.expire_time>CURRENT_TIMESTAMP)
              AND (
                    n.target_type='ALL'
                    OR (n.target_type='USER' AND n.target_user_id=?)
                    OR (n.target_type='ROLE' AND
            """);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        if (roles.isEmpty()) {
            fromWhere.append(" FALSE");
        } else {
            fromWhere.append(" n.target_role_key IN (");
            fromWhere.append(String.join(",", roles.stream().map(role -> "?").toList()));
            fromWhere.append(")");
            args.addAll(roles);
        }
        fromWhere.append("))");
        if (read != null) {
            fromWhere.append(read ? " AND r.id IS NOT NULL" : " AND r.id IS NULL");
        }
        if (StringUtils.hasText(type)) {
            fromWhere.append(" AND n.type=?");
            args.add(type.trim().toUpperCase());
        }
        return new VisibleQuery(fromWhere.toString(), args);
    }

    private Target normalizeTarget(String targetType, Long targetUserId, String targetRoleKey) {
        String value = StringUtils.hasText(targetType) ? targetType.trim().toUpperCase() : "ALL";
        return switch (value) {
            case "ALL" -> new Target("ALL", null, null);
            case "USER" -> {
                if (targetUserId == null || !userExists(targetUserId)) {
                    throw new BusinessException(ApiErrorCode.BAD_REQUEST, "target user not found");
                }
                yield new Target("USER", targetUserId, null);
            }
            case "ROLE" -> {
                String roleKey = StringUtils.hasText(targetRoleKey) ? targetRoleKey.trim().toLowerCase() : null;
                if (!StringUtils.hasText(roleKey) || !roleExists(roleKey)) {
                    throw new BusinessException(ApiErrorCode.BAD_REQUEST, "target role not found");
                }
                yield new Target("ROLE", null, roleKey);
            }
            default -> throw new BusinessException(ApiErrorCode.BAD_REQUEST, "targetType must be ALL, USER or ROLE");
        };
    }

    private boolean userExists(Long userId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM t_user WHERE id=? AND deleted=0 AND status=0", Integer.class, userId);
        return count != null && count > 0;
    }

    private boolean roleExists(String roleKey) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sys_role WHERE role_key=? AND status=0", Integer.class, roleKey);
        return count != null && count > 0;
    }

    private Map<String, Object> visibleNotificationRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("notificationId", rs.getLong("notification_id"));
        row.put("title", rs.getString("title"));
        row.put("content", rs.getString("content"));
        row.put("type", rs.getString("type"));
        row.put("targetType", rs.getString("target_type"));
        long targetUserId = rs.getLong("target_user_id");
        row.put("targetUserId", rs.wasNull() ? null : targetUserId);
        row.put("targetRoleKey", rs.getString("target_role_key"));
        row.put("bizType", rs.getString("biz_type"));
        row.put("bizId", rs.getString("biz_id"));
        row.put("publishTime", ts(rs, "publish_time"));
        row.put("expireTime", ts(rs, "expire_time"));
        row.put("read", rs.getBoolean("is_read"));
        row.put("readTime", ts(rs, "read_time"));
        return row;
    }

    private Map<String, Object> adminNotificationRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("notificationId", rs.getLong("notification_id"));
        row.put("title", rs.getString("title"));
        row.put("content", rs.getString("content"));
        row.put("type", rs.getString("type"));
        row.put("targetType", rs.getString("target_type"));
        long targetUserId = rs.getLong("target_user_id");
        row.put("targetUserId", rs.wasNull() ? null : targetUserId);
        row.put("targetRoleKey", rs.getString("target_role_key"));
        long senderId = rs.getLong("sender_id");
        row.put("senderId", rs.wasNull() ? null : senderId);
        row.put("senderUsername", rs.getString("sender_username"));
        row.put("bizType", rs.getString("biz_type"));
        row.put("bizId", rs.getString("biz_id"));
        row.put("status", rs.getInt("status"));
        row.put("publishTime", ts(rs, "publish_time"));
        row.put("expireTime", ts(rs, "expire_time"));
        row.put("createTime", ts(rs, "create_time"));
        return row;
    }

    private Object ts(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
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

    private record VisibleQuery(String fromWhere, List<Object> args) {
    }

    private record Target(String targetType, Long targetUserId, String targetRoleKey) {
    }
}
