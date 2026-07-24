package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.dto.StockReservationItem;
import com.example.demo.dto.StockReservationRequest;
import com.example.demo.dto.StockReleaseRequest;
import com.example.demo.entity.StockReservation;
import com.example.demo.exception.BusinessException;
import com.example.demo.mapper.StockReservationMapper;
import com.example.demo.vo.StockReservationResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * v1.2 §10.1 库存超卖控制（DB 行级锁 + sto_stock_reservation 持久化预占）。
 *
 * <p>PG 为库存事实来源；Redis 降为缓存层。本服务所有变更在 PG 事务内完成。
 * 任一 SKU 不足 → 整单回滚 + 50010。
 */
@Service
public class StockReservationService {

    private final JdbcTemplate jdbcTemplate;
    private final StockMutationService stockMutationService;
    private final StockReservationMapper reservationMapper;
    private final OutboxEventService outboxEventService;

    public StockReservationService(JdbcTemplate jdbcTemplate,
                                    StockMutationService stockMutationService,
                                    StockReservationMapper reservationMapper,
                                    OutboxEventService outboxEventService) {
        this.jdbcTemplate = jdbcTemplate;
        this.stockMutationService = stockMutationService;
        this.reservationMapper = reservationMapper;
        this.outboxEventService = outboxEventService;
    }

    @Transactional
    public StockReservationResponse reserveAll(StockReservationRequest request) {
        // 同 reservationRequestId 重复请求幂等返回首次结果
        List<StockReservation> existing = reservationMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StockReservation>()
                .eq("reservation_id", request.reservationRequestId()));
        if (!existing.isEmpty()) {
            return toResponse(existing, request.expireAt());
        }

        LocalDateTime expireAt = request.expireAt() != null ? request.expireAt() : LocalDateTime.now().plusSeconds(1800);
        // 按 skuId 排序避免死锁
        List<StockReservationItem> sorted = request.items().stream()
            .sorted(Comparator.comparing(StockReservationItem::skuId))
            .toList();
        List<StockReservation> rows = new ArrayList<>();
        for (StockReservationItem item : sorted) {
            StockMutationService.StockAllocation allocation = stockMutationService.requireAllocation(
                item.skuId(), item.warehouseId(), item.quantity());
            StockReservation reservation = new StockReservation();
            reservation.setReservationId(request.reservationRequestId());
            reservation.setRequestId(request.reservationRequestId());
            reservation.setOrderNo(request.orderNo());
            reservation.setSkuId(item.skuId());
            reservation.setWarehouseId(allocation.warehouseId());
            reservation.setLocationId(allocation.locationId());
            reservation.setQuantity(item.quantity());
            reservation.setStatus((short) 0);
            reservation.setExpiresAt(expireAt);
            reservation.setPayloadHash(UUID.randomUUID().toString().replace("-", ""));
            try {
                reservationMapper.insert(reservation);
            } catch (DuplicateKeyException race) {
                throw new BusinessException(ApiErrorCode.RESERVATION_CONFLICT);
            }
            // 写流水 + 调整 locked_quantity
            stockMutationService.adjust(
                item.skuId(),
                allocation.warehouseId(),
                allocation.locationId(),
                0, item.quantity(), 2,
                request.orderNo(),
                "reserve:" + reservation.getReservationId() + ":" + item.skuId(),
                "system",
                null,
                "订单预占：" + request.reservationRequestId());
            rows.add(reservation);
        }
        outboxEventService.addOutbox("sales.stock.reserved", "RESERVATION", null,
            java.util.Map.of(
                "reservationId", request.reservationRequestId(),
                "orderNo", request.orderNo(),
                "itemsCount", request.items().size()));
        return toResponse(rows, expireAt);
    }

    @Transactional
    public void confirm(String reservationId) {
        List<StockReservation> rows = requireReservations(reservationId);
        for (StockReservation row : rows) {
            if (row.getStatus() != null && row.getStatus() == 1) {
                continue; // 已确认
            }
            int changed = jdbcTemplate.update(
                "UPDATE sto_stock_reservation SET status=1, updated_at=CURRENT_TIMESTAMP WHERE id=? AND status=0",
                row.getId());
            if (changed == 0) {
                throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID, "预占状态非法：" + reservationId);
            }
            stockMutationService.adjust(
                row.getSkuId(), row.getWarehouseId(), row.getLocationId(),
                -row.getQuantity(), -row.getQuantity(), 2,
                reservationId,
                "reserve-confirm:" + reservationId + ":" + row.getSkuId(),
                "system", null, "预占确认扣减");
        }
        outboxEventService.addOutbox("sales.stock.confirmed", "RESERVATION", null,
            java.util.Map.of("reservationId", reservationId));
    }

    @Transactional
    public void cancel(String reservationId) {
        List<StockReservation> rows = requireReservations(reservationId);
        for (StockReservation row : rows) {
            if (row.getStatus() != null && (row.getStatus() == 2 || row.getStatus() == 3)) {
                continue;
            }
            int changed = jdbcTemplate.update(
                "UPDATE sto_stock_reservation SET status=2, updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN (0,1)",
                row.getId());
            if (changed == 0) {
                throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID, "预占状态非法：" + reservationId);
            }
            stockMutationService.adjust(
                row.getSkuId(), row.getWarehouseId(), row.getLocationId(),
                0, -row.getQuantity(), 2,
                reservationId,
                "reserve-cancel:" + reservationId + ":" + row.getSkuId(),
                "system", null, "预占取消释放锁定");
        }
        outboxEventService.addOutbox("sales.stock.cancelled", "RESERVATION", null,
            java.util.Map.of("reservationId", reservationId));
    }

    @Transactional
    public void restock(String reservationId, String refundNo) {
        List<StockReservation> rows = requireReservations(reservationId);
        for (StockReservation row : rows) {
            if (row.getStatus() != null && row.getStatus() == 4) {
                continue;
            }
            int changed = jdbcTemplate.update(
                "UPDATE sto_stock_reservation SET status=4, updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN (0,1)",
                row.getId());
            if (changed == 0) {
                throw new BusinessException(ApiErrorCode.ORDER_STATE_INVALID, "预占状态非法：" + reservationId);
            }
            stockMutationService.adjust(
                row.getSkuId(), row.getWarehouseId(), row.getLocationId(),
                row.getQuantity(), -row.getQuantity(), 2,
                refundNo != null ? refundNo : reservationId,
                "reserve-restock:" + reservationId + ":" + row.getSkuId(),
                "system", null, "退款完成回补库存");
        }
        outboxEventService.addOutbox("inventory.stock.restock", "RESERVATION", null,
            java.util.Map.of("reservationId", reservationId, "refundNo", refundNo == null ? "" : refundNo));
    }

    public void release(StockReleaseRequest request) {
        switch (request.action()) {
            case "confirm" -> confirm(request.reservationId());
            case "cancel" -> cancel(request.reservationId());
            case "restock" -> {
                if (!StringUtils.hasText(request.refundNo())) {
                    throw new BusinessException(ApiErrorCode.BAD_REQUEST, "restock 必须传 refundNo");
                }
                restock(request.reservationId(), request.refundNo());
            }
            default -> throw new BusinessException(ApiErrorCode.BAD_REQUEST, "action 非法");
        }
    }

    @Scheduled(fixedDelayString = "${app.stock-reservation.expire-scan-delay-millis:60000}")
    @Transactional
    public void expireDueReservations() {
        List<Long> ids = jdbcTemplate.queryForList(
            "SELECT id FROM sto_stock_reservation WHERE status=0 AND expires_at < CURRENT_TIMESTAMP ORDER BY id LIMIT 100",
            Long.class);
        for (Long id : ids) {
            try {
                List<StockReservation> rows = reservationMapper.selectBatchIds(java.util.List.of(id));
                if (rows.isEmpty()) continue;
                StockReservation row = rows.getFirst();
                int changed = jdbcTemplate.update(
                    "UPDATE sto_stock_reservation SET status=3, updated_at=CURRENT_TIMESTAMP WHERE id=? AND status=0",
                    id);
                if (changed == 0) continue;
                stockMutationService.adjust(
                    row.getSkuId(), row.getWarehouseId(), row.getLocationId(),
                    0, -row.getQuantity(), 2,
                    row.getReservationId(),
                    "reserve-expire:" + row.getReservationId() + ":" + row.getSkuId(),
                    "system", null, "预占过期释放锁定");
            } catch (Exception ex) {
                // 单条失败不影响整批
            }
        }
    }

    private List<StockReservation> requireReservations(String reservationId) {
        List<StockReservation> rows = reservationMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StockReservation>()
                .eq("reservation_id", reservationId)
                .orderByAsc("id"));
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "预占记录不存在：" + reservationId);
        }
        return rows;
    }

    private StockReservationResponse toResponse(List<StockReservation> rows, LocalDateTime expireAt) {
        List<StockReservationResponse.Line> lines = rows.stream()
            .map(r -> new StockReservationResponse.Line(r.getSkuId(), r.getWarehouseId(), r.getLocationId(), r.getQuantity()))
            .toList();
        return new StockReservationResponse(
            rows.isEmpty() ? null : rows.getFirst().getReservationId(),
            expireAt,
            lines);
    }
}