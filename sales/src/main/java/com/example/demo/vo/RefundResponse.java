package com.example.demo.vo;

import com.example.demo.json.RefundTimeJsonCodec;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

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
    @JsonSerialize(using = RefundTimeJsonCodec.Serializer.class)
    @JsonDeserialize(using = RefundTimeJsonCodec.Deserializer.class)
    LocalDateTime appliedAt,
    Boolean restock,
    String providerRefundNo,
    @JsonSerialize(using = RefundTimeJsonCodec.Serializer.class)
    @JsonDeserialize(using = RefundTimeJsonCodec.Deserializer.class)
    LocalDateTime completedAt,
    String auditRemark,
    List<RefundItemResponse> items) {
}
