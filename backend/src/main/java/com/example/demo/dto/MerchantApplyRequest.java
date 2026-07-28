package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;

public record MerchantApplyRequest(
    @NotBlank String merchantName,
    String merchantCode,
    @NotBlank String contactName,
    @NotBlank String contactPhone,
    String contactEmail,
    @NotBlank String licenseNo,
    String licenseImage,
    String businessScope,
    String address) {
}
