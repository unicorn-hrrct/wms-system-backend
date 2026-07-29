package com.example.demo.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AftersaleRefundRequestValidationTest {

    private static jakarta.validation.ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void applyRejectsInvalidIdsPrecisionAndColumnLengths() {
        AftersaleApplyRequest request = new AftersaleApplyRequest(
            0L,
            -1L,
            1,
            "r".repeat(501),
            List.of(),
            new BigDecimal("123456789012345678.001"),
            null,
            "m".repeat(501));

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void validRefundRequestsRespectNumeric19Scale2Boundary() {
        AftersaleApplyRequest apply = new AftersaleApplyRequest(
            1L,
            1L,
            2,
            "退货退款",
            List.of("/proof/a.jpg"),
            new BigDecimal("99999999999999999.99"),
            1,
            "包装完整");
        AftersaleAuditRequest aftersaleAudit =
            new AftersaleAuditRequest(
                1,
                new BigDecimal("99999999999999999.99"),
                1,
                "同意");
        RefundCompleteRequest complete =
            new RefundCompleteRequest(
                new BigDecimal("99999999999999999.99"),
                "P".repeat(80),
                true);

        assertTrue(validator.validate(apply).isEmpty());
        assertTrue(validator.validate(aftersaleAudit).isEmpty());
        assertTrue(validator.validate(complete).isEmpty());
    }

    @Test
    void auditAndCompleteRejectInvalidStatusAmountAndProviderLength() {
        RefundAuditRequest audit = new RefundAuditRequest(
            3,
            BigDecimal.ZERO,
            0,
            "a".repeat(501));
        RefundCompleteRequest complete = new RefundCompleteRequest(
            new BigDecimal("0.001"),
            "P".repeat(81),
            false);

        assertFalse(validator.validate(audit).isEmpty());
        assertFalse(validator.validate(complete).isEmpty());
    }
}
