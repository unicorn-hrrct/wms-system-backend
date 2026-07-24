package com.example.demo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record AftersaleApplyRequest(
    @NotNull Long orderId,
    @NotNull Long orderItemId,
    @NotNull @Min(1) @Max(3) Integer type,
    @NotBlank String reason,
    List<String> images,
    BigDecimal applyRefundAmount,
    Integer applyRefundQuantity,
    String remark) {
}