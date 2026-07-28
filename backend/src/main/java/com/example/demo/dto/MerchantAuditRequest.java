package com.example.demo.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record MerchantAuditRequest(
    @NotNull Integer auditStatus,
    String merchantCode,
    List<Long> warehouseIds,
    String auditRemark) {
}
