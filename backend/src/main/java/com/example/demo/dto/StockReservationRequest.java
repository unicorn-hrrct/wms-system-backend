package com.example.demo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDateTime;
import java.util.List;

public record StockReservationRequest(
    @NotBlank String reservationRequestId,
    @NotBlank String orderNo,
    LocalDateTime expireAt,
    @NotEmpty List<@Valid StockReservationItem> items) {
}