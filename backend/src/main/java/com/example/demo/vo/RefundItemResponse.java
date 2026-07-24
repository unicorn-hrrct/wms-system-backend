package com.example.demo.vo;

public record RefundItemResponse(
    Long skuId,
    Long warehouseId,
    Long locationId,
    Integer quantity) {
}