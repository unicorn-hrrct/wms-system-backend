package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record RefundCompleteRequest(
    @NotNull BigDecimal actualRefundAmount,
    @NotBlank String providerRefundNo,
    @NotNull Boolean restock) {
}