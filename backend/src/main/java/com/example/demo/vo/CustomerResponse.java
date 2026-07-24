package com.example.demo.vo;

import java.time.LocalDateTime;

public record CustomerResponse(
    Long customerId,
    Long userId,
    String nickname,
    String phone,
    String email,
    String level,
    LocalDateTime registeredAt) {
}