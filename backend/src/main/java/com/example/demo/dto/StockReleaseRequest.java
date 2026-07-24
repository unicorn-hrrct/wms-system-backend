package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record StockReleaseRequest(
    @NotBlank String reservationId,
    @NotBlank @Pattern(regexp = "confirm|cancel|restock", message = "action 只能是 confirm/cancel/restock") String action,
    String refundNo) {
}