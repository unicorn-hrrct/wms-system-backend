package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;

public record AddressUpdateRequest(
    @NotBlank String receiverName,
    @NotBlank String receiverPhone,
    String province,
    String city,
    String district,
    String detailAddress,
    Boolean isDefault) {
}