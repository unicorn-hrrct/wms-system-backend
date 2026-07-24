package com.example.demo.vo;

import java.time.LocalDateTime;
import java.util.List;

public record StockReservationResponse(
    String reservationId,
    LocalDateTime expireAt,
    List<Line> items) {

    public record Line(Long skuId, Long warehouseId, Long locationId, Integer quantity) {
    }
}