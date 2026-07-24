package com.example.demo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PaymentPrepayRequest(
    @NotNull @Min(1) @Max(4) Integer payType,
    String payPassword) {
}