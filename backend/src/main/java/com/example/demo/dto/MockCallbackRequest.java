package com.example.demo.dto;

import java.math.BigDecimal;

public record MockCallbackRequest(
    String payNo,
    String status,
    String providerTransactionNo,
    String paidAt,
    BigDecimal paidAmount) {
}