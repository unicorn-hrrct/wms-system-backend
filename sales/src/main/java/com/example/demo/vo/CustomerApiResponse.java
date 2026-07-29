package com.example.demo.vo;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/** v1.2 Customer HTTP representation with an explicit offset on registeredAt. */
public record CustomerApiResponse(
    Long customerId,
    Long userId,
    String nickname,
    String phone,
    String email,
    String level,
    OffsetDateTime registeredAt) {

    private static final ZoneId CUSTOMER_ZONE = ZoneId.of("Asia/Shanghai");

    public static CustomerApiResponse from(CustomerResponse source) {
        return new CustomerApiResponse(
            source.customerId(),
            source.userId(),
            source.nickname(),
            source.phone(),
            source.email(),
            source.level(),
            source.registeredAt() == null
                ? null
                : source.registeredAt().atZone(CUSTOMER_ZONE).toOffsetDateTime());
    }
}
