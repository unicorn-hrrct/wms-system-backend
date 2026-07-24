package com.example.demo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StockReservationItem(
    @NotNull Long skuId,
    @NotNull @Min(1) Integer quantity,
    Long warehouseId) {
}