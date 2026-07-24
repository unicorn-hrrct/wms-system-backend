package com.example.demo.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record RefundResponse(
    Long refundId,
    String refundNo,
    Long orderId,
    Long orderItemId,
    Integer type,
    String typeText,
    BigDecimal applyRefundAmount,
    Integer applyRefundQuantity,
    BigDecimal approvedAmount,
    BigDecimal actualRefundAmount,
    Integer status,
    String statusText,
    String reason,
    List<String> images,
    LocalDateTime appliedAt,
    Boolean restock,
    String providerRefundNo,
    LocalDateTime completedAt,
    String auditRemark,
    List<RefundItemResponse> items) {
}