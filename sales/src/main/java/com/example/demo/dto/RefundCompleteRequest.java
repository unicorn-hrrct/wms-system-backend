package com.example.demo.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RefundCompleteRequest(
    @NotNull @Positive @Digits(integer = 17, fraction = 2)
    BigDecimal actualRefundAmount,
    @NotBlank @Size(max = 80)
    String providerRefundNo,
    @NotNull Boolean restock) {
}
