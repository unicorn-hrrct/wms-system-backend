package com.example.demo.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record AftersaleApplyRequest(
    @NotNull @Positive Long orderId,
    @NotNull @Positive Long orderItemId,
    @NotNull @Min(1) @Max(3) Integer type,
    @NotBlank @Size(max = 500) String reason,
    List<String> images,
    @Positive @Digits(integer = 17, fraction = 2)
    BigDecimal applyRefundAmount,
    @Positive
    Integer applyRefundQuantity,
    @Size(max = 500)
    String remark) {
}
