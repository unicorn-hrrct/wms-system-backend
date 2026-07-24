package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 库存安全扣减（10.1）：基于 Redis Lua 脚本的原子扣减 + 释放/确认。
 *
 * <p>键设计：
 * <ul>
 *   <li>{@code stock:available:{skuId}} —— 可用库存计数器</li>
 *   <li>{@code stock:lock:{skuId}}     —— (orderNo -> lockedQuantity) 的哈希</li>
 * </ul>
 * 可用库存计数器在首次扣减时按 DB 中的可用库存初始化。
 */
@Service
public class StockLockService {

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    @SuppressWarnings("rawtypes")
    private RedisScript<List> decreaseScript;
    @SuppressWarnings("rawtypes")
    private RedisScript<List> releaseScript;

    @Value("${app.stock.lock-ttl-seconds:900}")
    private long defaultLockTtlSeconds;

    public StockLockService(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate) {
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void initScripts() {
        decreaseScript = new DefaultRedisScript<>(readScript("scripts/stock_decrease.lua"), List.class);
        releaseScript = new DefaultRedisScript<>(readScript("scripts/stock_release.lua"), List.class);
    }

    /**
     * 原子扣减可用库存并写入锁定哈希。
     */
    public LockResult decrease(Long skuId, int quantity, String orderNo, Integer expireSeconds) {
        if (skuId == null || quantity <= 0 || orderNo == null || orderNo.isBlank()) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "参数不合法");
        }
        ensureAvailableStock(skuId);
        long ttl = expireSeconds == null ? defaultLockTtlSeconds : expireSeconds;
        String stockKey = availableKey(skuId);
        String lockKey = lockHashKey(skuId);

        @SuppressWarnings({"rawtypes", "unchecked"})
        List result = redisTemplate.execute(decreaseScript, List.of(stockKey, lockKey),
            String.valueOf(quantity), orderNo, String.valueOf(ttl));
        if (result == null || result.isEmpty()) {
            throw new BusinessException(ApiErrorCode.STOCK_DEDUCT_CONFLICT, "Redis 调用无返回");
        }
        long status = toLong(result.get(0));
        long remaining = result.size() > 1 ? toLong(result.get(1)) : 0;
        if (status == 0L) {
            throw new BusinessException(ApiErrorCode.STOCK_NOT_ENOUGH,
                "库存不足，当前可用库存：" + remaining + "件");
        }
        if (status == -1L) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "扣减数量必须大于 0");
        }
        LockResponse response = new LockResponse();
        response.setLockId("LOCK-" + orderNo + "-" + skuId);
        response.setSkuId(skuId);
        response.setLockedQuantity(quantity);
        response.setRemainingStock((int) remaining);
        response.setExpireAt(LocalDateTime.now(ZoneId.systemDefault()).plusSeconds(ttl));
        return response;
    }

    /**
     * 释放或确认库存锁。
     *
     * @param lockId 锁 ID（{@link LockResult#getLockId()}）
     * @param action confirm=支付成功（直接核销）/ cancel=取消（库存回填）
     */
    public void release(String lockId, String action) {
        if (lockId == null || lockId.isBlank()) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "lockId 不能为空");
        }
        LockRef ref = LockRef.parse(lockId);
        if (ref == null) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "lockId 格式不合法");
        }
        if (!"confirm".equalsIgnoreCase(action) && !"cancel".equalsIgnoreCase(action)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "action 只能是 confirm 或 cancel");
        }
        ensureAvailableStock(ref.skuId());
        String stockKey = availableKey(ref.skuId());
        String lockKey = lockHashKey(ref.skuId());
        Object lockedRaw = redisTemplate.opsForHash().get(lockKey, ref.orderNo());
        if (lockedRaw == null) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "库存锁不存在或已过期");
        }
        int locked = Integer.parseInt(lockedRaw.toString());
        @SuppressWarnings({"rawtypes", "unchecked"})
        List result = redisTemplate.execute(releaseScript, List.of(stockKey, lockKey),
            action.toLowerCase(), ref.orderNo(), String.valueOf(locked));
        if (result == null || result.isEmpty() || toLong(result.get(0)) != 1L) {
            throw new BusinessException(ApiErrorCode.STOCK_DEDUCT_CONFLICT, "库存锁释放失败");
        }
    }

    private void ensureAvailableStock(Long skuId) {
        String key = availableKey(skuId);
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            return;
        }
        Integer available = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(quantity - locked_quantity),0)::int FROM sto_stock WHERE sku_id = ? AND deleted = 0",
            Integer.class, skuId);
        int initial = available == null ? 0 : Math.max(available, 0);
        redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(initial), Duration.ofDays(7));
    }

    private static String availableKey(Long skuId) {
        return "stock:available:" + skuId;
    }

    private static String lockHashKey(Long skuId) {
        return "stock:lock:" + skuId;
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(value.toString());
    }

    private static String readScript(String path) {
        try {
            var resource = new ClassPathResource(path);
            try (var in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Lua script 加载失败: " + path, ex);
        }
    }

    /** 锁定结果响应。 */
    public static class LockResponse implements LockResult {
        private String lockId;
        private Long skuId;
        private Integer lockedQuantity;
        private Integer remainingStock;
        private LocalDateTime expireAt;

        @Override public String getLockId() { return lockId; }
        @Override public Long getSkuId() { return skuId; }
        @Override public Integer getLockedQuantity() { return lockedQuantity; }
        @Override public Integer getRemainingStock() { return remainingStock; }
        @Override public LocalDateTime getExpireAt() { return expireAt; }

        public void setLockId(String lockId) { this.lockId = lockId; }
        public void setSkuId(Long skuId) { this.skuId = skuId; }
        public void setLockedQuantity(Integer lockedQuantity) { this.lockedQuantity = lockedQuantity; }
        public void setRemainingStock(Integer remainingStock) { this.remainingStock = remainingStock; }
        public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
    }

    /** 提供给 Controller / Service 用的结果接口。 */
    public interface LockResult {
        String getLockId();
        Long getSkuId();
        Integer getLockedQuantity();
        Integer getRemainingStock();
        LocalDateTime getExpireAt();
    }

    private record LockRef(Long skuId, String orderNo) {
        static LockRef parse(String lockId) {
            if (!lockId.startsWith("LOCK-")) return null;
            String body = lockId.substring("LOCK-".length());
            int idx = body.lastIndexOf('-');
            if (idx <= 0 || idx == body.length() - 1) return null;
            try {
                return new LockRef(Long.parseLong(body.substring(idx + 1)), body.substring(0, idx));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }
}