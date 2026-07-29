package com.example.demo.vo;

import com.example.demo.json.RefundTimeJsonCodec;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** v1.2 Refund HTTP representation with explicit offsets on every timestamp. */
public record RefundApiResponse(
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
    OffsetDateTime appliedAt,
    Boolean restock,
    String providerRefundNo,
    OffsetDateTime completedAt,
    String auditRemark,
    List<RefundItemResponse> items) {

    public static RefundApiResponse from(RefundResponse source) {
        return new RefundApiResponse(
            source.refundId(),
            source.refundNo(),
            source.orderId(),
            source.orderItemId(),
            source.type(),
            source.typeText(),
            source.applyRefundAmount(),
            source.applyRefundQuantity(),
            source.approvedAmount(),
            source.actualRefundAmount(),
            source.status(),
            source.statusText(),
            source.reason(),
            source.images(),
            RefundTimeJsonCodec.toOffsetDateTime(source.appliedAt()),
            source.restock(),
            source.providerRefundNo(),
            RefundTimeJsonCodec.toOffsetDateTime(source.completedAt()),
            source.auditRemark(),
            source.items());
    }
}
