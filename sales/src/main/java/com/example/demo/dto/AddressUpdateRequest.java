package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddressUpdateRequest(
    @NotBlank @Size(max = 50) String receiverName,
    @NotBlank @Size(max = 30) String receiverPhone,
    @Size(max = 50) String province,
    @Size(max = 50) String city,
    @Size(max = 50) String district,
    @Size(max = 255) String detailAddress,
    Boolean isDefault) {
}
