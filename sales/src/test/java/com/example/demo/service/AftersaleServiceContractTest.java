package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.dto.AftersaleApplyRequest;
import com.example.demo.dto.AftersaleAuditRequest;
import com.example.demo.exception.BusinessException;
import com.example.demo.security.CurrentUserProvider;
import com.example.demo.security.CustomerIdProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AftersaleServiceContractTest {

    private static final long USER_ID = 2L;
    private static final long CUSTOMER_ID = 5002L;
    private static final long ORDER_ID = 9001L;
    private static final long ORDER_ITEM_ID = 10001L;
    private static final long AFTERSALE_ID = 12001L;
    private static final String IDEMPOTENCY_KEY =
        "d10947c9-ea6c-4bb5-893f-57a6b0019201";

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private CurrentUserProvider currentUser;
    @Mock
    private CustomerIdProvider customerIdProvider;
    @Mock
    private OutboxEventService outboxEventService;
    @Mock
    private SalesIdempotencyService idempotencyService;

    private AftersaleService service;

    @BeforeEach
    void setUp() {
        service = new AftersaleService(
            jdbcTemplate,
            currentUser,
            customerIdProvider,
            new ObjectMapper(),
            outboxEventService,
            idempotencyService);
        lenient().when(idempotencyService.execute(
            anyString(), anyLong(), anyString(), any(), any(), any()))
            .thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(5)).get());
        lenient().when(idempotencyService.executeForUser(
            anyString(), anyLong(), anyString(), any(), any(), any()))
            .thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(5)).get());
    }

    @Test
    void applyUsesMappedCustomerAsIdempotencySubject() {
        when(currentUser.requireUserId()).thenReturn(USER_ID);
        when(customerIdProvider.requireCurrentCustomerIdForUpdate())
            .thenReturn(CUSTOMER_ID);
        when(idempotencyService.execute(
            eq("aftersale:apply"),
            eq(CUSTOMER_ID),
            eq(IDEMPOTENCY_KEY),
            any(),
            any(),
            any()))
            .thenReturn(null);

        service.apply(applyRequest(), IDEMPOTENCY_KEY);

        InOrder order =
            inOrder(currentUser, customerIdProvider, idempotencyService);
        order.verify(currentUser).requireUserId();
        order.verify(customerIdProvider)
            .requireCurrentCustomerIdForUpdate();
        order.verify(idempotencyService).execute(
            eq("aftersale:apply"),
            eq(CUSTOMER_ID),
            eq(IDEMPOTENCY_KEY),
            any(),
            any(),
            any());
        verifyNoInteractions(jdbcTemplate, outboxEventService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyChecksUserAndCustomerAndRejectsStatusFive() {
        when(currentUser.requireUserId()).thenReturn(USER_ID);
        when(customerIdProvider.requireCurrentCustomerIdForUpdate())
            .thenReturn(CUSTOMER_ID);
        when(jdbcTemplate.query(
            contains("FROM ord_order o"),
            any(RowMapper.class),
            eq(ORDER_ID),
            eq(USER_ID),
            eq(CUSTOMER_ID),
            eq(ORDER_ITEM_ID)))
            .thenReturn(List.of(Map.of("status", 5)));

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.apply(applyRequest(), IDEMPOTENCY_KEY));

        assertEquals(ApiErrorCode.ORDER_STATE_INVALID, error.getErrorCode());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
            sql.capture(),
            any(RowMapper.class),
            eq(ORDER_ID),
            eq(USER_ID),
            eq(CUSTOMER_ID),
            eq(ORDER_ITEM_ID));
        assertTrue(sql.getValue().contains("o.user_id=?"));
        assertTrue(sql.getValue().contains("o.customer_id=?"));
        assertTrue(sql.getValue().contains("FOR UPDATE OF oi"));
        verifyNoInteractions(outboxEventService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void quotaExcludesAftersalesAlreadyRepresentedByRefunds() {
        when(currentUser.requireUserId()).thenReturn(USER_ID);
        when(customerIdProvider.requireCurrentCustomerIdForUpdate())
            .thenReturn(CUSTOMER_ID);
        when(jdbcTemplate.query(
            contains("FROM ord_order o"),
            any(RowMapper.class),
            eq(ORDER_ID),
            eq(USER_ID),
            eq(CUSTOMER_ID),
            eq(ORDER_ITEM_ID)))
            .thenReturn(List.of(Map.of(
                "status", 2,
                "price", new BigDecimal("199.00"),
                "quantity", 1,
                "subtotal", new BigDecimal("199.00"))));
        when(jdbcTemplate.queryForMap(
            contains("FROM ord_aftersale"),
            eq(ORDER_ITEM_ID),
            eq(ORDER_ITEM_ID),
            eq(ORDER_ITEM_ID),
            eq(ORDER_ITEM_ID)))
            .thenReturn(Map.of(
                "occupied_amount", BigDecimal.ZERO,
                "occupied_quantity", BigDecimal.ZERO));
        RuntimeException stop = new RuntimeException("stop after quota");
        when(jdbcTemplate.queryForObject(
            contains("INSERT INTO ord_aftersale"),
            eq(Long.class),
            any(Object[].class)))
            .thenThrow(stop);

        RuntimeException error = assertThrows(
            RuntimeException.class,
            () -> service.apply(applyRequest(), IDEMPOTENCY_KEY));

        assertSame(stop, error);
        ArgumentCaptor<String> quotaSql =
            ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForMap(
            quotaSql.capture(),
            eq(ORDER_ITEM_ID),
            eq(ORDER_ITEM_ID),
            eq(ORDER_ITEM_ID),
            eq(ORDER_ITEM_ID));
        assertTrue(quotaSql.getValue().contains("refund_id IS NULL"));
        assertTrue(quotaSql.getValue()
            .contains("status IN (0,1,3,4)"));
    }

    @Test
    void auditUsesAuthenticatedUserAsIdempotencySubject() {
        when(currentUser.requireUserId()).thenReturn(USER_ID);
        when(idempotencyService.executeForUser(
            eq("aftersale:audit"),
            eq(USER_ID),
            eq(IDEMPOTENCY_KEY),
            any(),
            any(),
            any()))
            .thenReturn(null);

        service.audit(
            AFTERSALE_ID,
            new AftersaleAuditRequest(2, null, null, "不符合条件"),
            IDEMPOTENCY_KEY);

        verify(idempotencyService).executeForUser(
            eq("aftersale:audit"),
            eq(USER_ID),
            eq(IDEMPOTENCY_KEY),
            any(),
            any(),
            any());
        verifyNoInteractions(
            jdbcTemplate, customerIdProvider, outboxEventService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exchangeApprovalDoesNotInventARefundFlow() {
        when(currentUser.requireUserId()).thenReturn(USER_ID);
        LinkedHashMap<String, Object> aftersale = new LinkedHashMap<>();
        aftersale.put("status", 0);
        aftersale.put("type", 3);
        aftersale.put("applyRefundAmount", BigDecimal.ZERO);
        aftersale.put("applyRefundQuantity", 0);
        aftersale.put("refundId", null);
        when(jdbcTemplate.query(
            contains("FROM ord_aftersale"),
            any(RowMapper.class),
            eq(AFTERSALE_ID)))
            .thenReturn(List.of(aftersale));
        when(jdbcTemplate.update(
            contains("UPDATE ord_aftersale"),
            any(Object[].class)))
            .thenReturn(1);
        RuntimeException stop = new RuntimeException("stop at outbox");
        doThrow(stop).when(outboxEventService).addOutbox(
            eq("sales.aftersale.audited"),
            eq("AFTERSALE_AUDITED"),
            eq(AFTERSALE_ID),
            any());

        RuntimeException error = assertThrows(
            RuntimeException.class,
            () -> service.audit(
                AFTERSALE_ID,
                new AftersaleAuditRequest(1, null, null, null),
                IDEMPOTENCY_KEY));

        assertSame(stop, error);
        verify(jdbcTemplate, never()).queryForObject(
            contains("INSERT INTO ref_refund"),
            eq(Long.class),
            any(Object[].class));
    }

    private AftersaleApplyRequest applyRequest() {
        return new AftersaleApplyRequest(
            ORDER_ID,
            ORDER_ITEM_ID,
            1,
            "商品有质量问题",
            List.of(),
            new BigDecimal("199.00"),
            null,
            null);
    }
}
