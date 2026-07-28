package com.example.demo.vo;

import java.time.LocalDateTime;
import java.util.List;

public record MerchantApplicationResponse(
    Long applicationId,
    String applicationNo,
    Long userId,
    String username,
    String merchantName,
    String merchantCode,
    String contactName,
    String contactPhone,
    String contactEmail,
    String licenseNo,
    String licenseImage,
    String businessScope,
    String address,
    Integer status,
    String statusText,
    String auditRemark,
    LocalDateTime appliedAt,
    LocalDateTime auditedAt,
    LocalDateTime updateTime,
    Long ownerId,
    String ownerCode,
    List<Long> warehouseIds) {
}
