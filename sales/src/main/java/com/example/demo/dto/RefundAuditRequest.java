package com.example.demo.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RefundAuditRequest(
    @NotNull @Min(1) @Max(2) Integer auditStatus,
    @Positive @Digits(integer = 17, fraction = 2)
    BigDecimal approvedAmount,
    @Positive
    Integer approvedQuantity,
    @Size(max = 500)
    String auditRemark) {
}
