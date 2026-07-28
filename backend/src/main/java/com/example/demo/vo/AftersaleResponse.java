package com.example.demo.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record AftersaleResponse(
    Long aftersaleId,
    String aftersaleNo,
    Long orderId,
    String orderNo,
    Long orderItemId,
    Long userId,
    Long customerId,
    String customerName,
    Integer type,
    String typeText,
    Integer status,
    String statusText,
    BigDecimal applyRefundAmount,
    Integer applyRefundQuantity,
    BigDecimal approvedAmount,
    Integer approvedQuantity,
    String reason,
    List<String> images,
    String remark,
    String auditRemark,
    LocalDateTime appliedAt,
    LocalDateTime auditedAt,
    LocalDateTime updateTime,
    Long refundId,
    String refundNo,
    Map<String, Object> orderItem) {
}
