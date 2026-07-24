package com.example.demo.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record RefundAuditRequest(
    @NotNull Integer auditStatus,
    BigDecimal approvedAmount,
    Integer approvedQuantity,
    String auditRemark) {
}