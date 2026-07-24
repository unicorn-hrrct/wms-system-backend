package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class StockMutationService {

    private final JdbcTemplate jdbcTemplate;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final long lockWaitMillis;
    private final long lockLeaseMillis;

    public StockMutationService(JdbcTemplate jdbcTemplate,
                                RedissonClient redissonClient,
                                ObjectMapper objectMapper,
                                @Value("${app.stock.lock-wait-millis:3000}") long lockWaitMillis,
                                @Value("${app.stock.lock-lease-millis:10000}") long lockLeaseMillis) {
        this.jdbcTemplate = jdbcTemplate;
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.lockWaitMillis = lockWaitMillis;
        this.lockLeaseMillis = lockLeaseMillis;
    }

    @Transactional
    public StockChange adjust(Long skuId, Long warehouseId, Long locationId,
                              int quantityDelta, int lockedDelta, int type,
                              String sourceNo, String requestId, String operator,
                              String batchNo, String remark) {
        return adjust(skuId, warehouseId, locationId, quantityDelta, lockedDelta, type,
            sourceNo, requestId, operator, batchNo, null, null, remark);
    }

    @Transactional
    public StockChange adjust(Long skuId, Long warehouseId, Long locationId,
                              int quantityDelta, int lockedDelta, int type,
                              String sourceNo, String requestId, String operator,
                              String batchNo, java.time.LocalDate productionDate,
                              java.time.LocalDate expireDate, String remark) {
        List<StockChange> previous = jdbcTemplate.query("""
            SELECT before_qty, after_qty FROM sto_stock_log WHERE request_id = ?
            """, (rs, rowNum) -> new StockChange(rs.getInt("before_qty"), rs.getInt("after_qty")), requestId);
        if (!previous.isEmpty()) {
            return previous.getFirst();
        }

        String lockName = "stock:lock:" + warehouseId + ":" + locationId + ":" + skuId;
        RLock lock = redissonClient.getLock(lockName);
        boolean acquired;
        try {
            acquired = lock.tryLock(lockWaitMillis, lockLeaseMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ApiErrorCode.STOCK_DEDUCT_CONFLICT, "库存操作被中断");
        }
        if (!acquired) {
            throw new BusinessException(ApiErrorCode.STOCK_DEDUCT_CONFLICT, "库存正在被其他操作占用");
        }

        try {
            previous = jdbcTemplate.query("""
                SELECT before_qty, after_qty FROM sto_stock_log WHERE request_id = ?
                """, (rs, rowNum) -> new StockChange(rs.getInt("before_qty"), rs.getInt("after_qty")), requestId);
            if (!previous.isEmpty()) {
                return previous.getFirst();
            }
            List<StockRow> rows = jdbcTemplate.query("""
                SELECT id, quantity, locked_quantity FROM sto_stock
                WHERE sku_id = ? AND warehouse_id = ? AND location_id = ? AND deleted = 0
                FOR UPDATE
                """, (rs, rowNum) -> new StockRow(rs.getLong("id"), rs.getInt("quantity"), rs.getInt("locked_quantity")),
                skuId, warehouseId, locationId);

            StockRow stock;
            if (rows.isEmpty()) {
                if (quantityDelta < 0 || lockedDelta < 0) {
                    throw new BusinessException(ApiErrorCode.STOCK_NOT_ENOUGH);
                }
                if (quantityDelta == 0 && lockedDelta > 0) {
                    throw new BusinessException(ApiErrorCode.STOCK_NOT_ENOUGH);
                }
                Long id = jdbcTemplate.queryForObject("""
                    INSERT INTO sto_stock(sku_id, warehouse_id, location_id, quantity, locked_quantity, batch_no, production_date, expire_date)
                    VALUES (?, ?, ?, 0, 0, ?, ?, ?) RETURNING id
                    """, Long.class, skuId, warehouseId, locationId, batchNo, productionDate, expireDate);
                stock = new StockRow(id, 0, 0);
            } else {
                stock = rows.getFirst();
            }

            int afterQuantity = stock.quantity() + quantityDelta;
            int afterLocked = stock.lockedQuantity() + lockedDelta;
            if (afterQuantity < 0 || afterLocked < 0 || afterLocked > afterQuantity) {
                throw new BusinessException(ApiErrorCode.STOCK_NOT_ENOUGH, "库存不足，退货数量超过可用库存");
            }

            int changed = jdbcTemplate.update("""
                UPDATE sto_stock
                SET quantity = ?, locked_quantity = ?,
                    batch_no = COALESCE(?, batch_no),
                    production_date = COALESCE(?, production_date),
                    expire_date = COALESCE(?, expire_date),
                    update_time = CURRENT_TIMESTAMP
                WHERE id = ? AND quantity = ? AND locked_quantity = ?
                """, afterQuantity, afterLocked, batchNo, productionDate, expireDate,
                stock.id(), stock.quantity(), stock.lockedQuantity());
            if (changed != 1) {
                throw new BusinessException(ApiErrorCode.STOCK_DEDUCT_CONFLICT);
            }

            String logRemark = remark;
            if (lockedDelta != 0) {
                logRemark = (remark == null ? "" : remark + " ") + "[locked_change=" + lockedDelta + "]";
            }
            try {
                jdbcTemplate.update("""
                    INSERT INTO sto_stock_log(sku_id, warehouse_id, location_id, type, quantity_change,
                                              before_qty, after_qty, source_no, request_id, operator, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, skuId, warehouseId, locationId, type, quantityDelta, stock.quantity(), afterQuantity,
                    sourceNo, requestId, operator, logRemark);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(ApiErrorCode.STOCK_DEDUCT_CONFLICT, "库存请求号重复");
            }
            publishLowStockIfNeeded(skuId, afterQuantity - afterLocked, sourceNo);
            return new StockChange(stock.quantity(), afterQuantity);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public StockAllocation requireAllocation(Long skuId, int quantity) {
        return requireAllocation(skuId, null, quantity);
    }

    public StockAllocation requireAllocation(Long skuId, Long warehouseId, int quantity) {
        String sql;
        Object[] args;
        if (warehouseId == null) {
            sql = """
                SELECT id, warehouse_id, location_id, quantity, locked_quantity
                FROM sto_stock
                WHERE sku_id = ? AND deleted = 0 AND quantity - locked_quantity >= ?
                ORDER BY quantity - locked_quantity DESC, id LIMIT 1
                """;
            args = new Object[]{skuId, quantity};
        } else {
            sql = """
                SELECT id, warehouse_id, location_id, quantity, locked_quantity
                FROM sto_stock
                WHERE sku_id = ? AND warehouse_id = ? AND deleted = 0
                  AND quantity - locked_quantity >= ?
                ORDER BY quantity - locked_quantity DESC, id LIMIT 1
                """;
            args = new Object[]{skuId, warehouseId, quantity};
        }
        List<StockAllocation> rows = jdbcTemplate.query(sql,
            (rs, rowNum) -> new StockAllocation(rs.getLong("id"), rs.getLong("warehouse_id"),
                rs.getLong("location_id"), rs.getInt("quantity"), rs.getInt("locked_quantity")), args);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.STOCK_NOT_ENOUGH);
        }
        return rows.getFirst();
    }

    private void publishLowStockIfNeeded(Long skuId, int available, String sourceNo) {
        Integer min = jdbcTemplate.query("""
            SELECT min_alert FROM sto_stock_alert_rule WHERE sku_id = ? AND enabled = TRUE
            """, rs -> rs.next() ? rs.getInt(1) : null, skuId);
        if (min == null || available >= min) {
            return;
        }
        try {
            String eventId = UUID.randomUUID().toString();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventId", eventId);
            payload.put("skuId", skuId);
            payload.put("availableStock", available);
            payload.put("minAlert", min);
            payload.put("sourceNo", sourceNo);
            payload.put("occurredAt", LocalDateTime.now().toString());
            jdbcTemplate.update("""
                INSERT INTO sys_outbox_event(event_id, routing_key, aggregate_type, aggregate_id, payload)
                VALUES (?, 'inventory.low-stock', 'STOCK', ?, CAST(? AS jsonb))
                """, eventId, String.valueOf(skuId), objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR, "库存预警事件写入失败");
        }
    }

    private record StockRow(Long id, int quantity, int lockedQuantity) {
    }

    public record StockChange(int beforeQuantity, int afterQuantity) {
    }

    public record StockAllocation(Long stockId, Long warehouseId, Long locationId,
                                  int quantity, int lockedQuantity) {
    }
}
