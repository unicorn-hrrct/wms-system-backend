package com.example.demo.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentResponse(
    String payNo,
    Long orderId,
    Integer payType,
    BigDecimal payAmount,
    LocalDateTime expireTime,
    String mockPayUrl) {
}