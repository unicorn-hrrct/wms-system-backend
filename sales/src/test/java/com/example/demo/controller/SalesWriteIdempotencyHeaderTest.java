package com.example.demo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesWriteIdempotencyHeaderTest {

    @Test
    void everyCustomerAddressAftersaleRefundWriteAcceptsHeader() {
        assertIdempotencyHeader(CustomerController.class, "update");
        assertIdempotencyHeader(AddressController.class, "create");
        assertIdempotencyHeader(AddressController.class, "update");
        assertIdempotencyHeader(AddressController.class, "delete");
        assertIdempotencyHeader(AddressController.class, "setDefault");
        assertIdempotencyHeader(AftersaleController.class, "apply");
        assertIdempotencyHeader(AftersaleController.class, "audit");
        assertIdempotencyHeader(RefundController.class, "audit");
        assertIdempotencyHeader(RefundController.class, "complete");
        assertIdempotencyHeader(RefundController.class, "cancel");
    }

    private void assertIdempotencyHeader(
        Class<?> controller, String methodName) {
        Method method = Arrays.stream(controller.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .findFirst()
            .orElseThrow();
        boolean found = Arrays.stream(method.getParameters())
            .flatMap(parameter ->
                Arrays.stream(parameter.getAnnotations()))
            .filter(RequestHeader.class::isInstance)
            .map(RequestHeader.class::cast)
            .anyMatch(header ->
                "Idempotency-Key".equals(header.value()));
        assertTrue(
            found,
            () -> controller.getSimpleName()
                + "." + methodName
                + " must accept Idempotency-Key");
    }
}
