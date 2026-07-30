package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.dto.RefundAuditRequest;
import com.example.demo.dto.RefundCompleteRequest;
import com.example.demo.exception.BusinessException;
import com.example.demo.security.CurrentUserProvider;
import com.example.demo.security.CustomerIdProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundServiceContractTest {

    private static final long USER_ID = 41L;
    private static final long CUSTOMER_ID = 5002L;
    private static final long REFUND_ID = 11001L;
    private static final String IDEMPOTENCY_KEY =
        "18f220e8-987e-4d8f-9e50-1dd834a42e70";

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

    private RefundService service;

    @BeforeEach
    void setUp() {
        service = new RefundService(
            jdbcTemplate,
            currentUser,
            customerIdProvider,
            outboxEventService,
            idempotencyService,
            new ObjectMapper());
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
    @SuppressWarnings("unchecked")
    void myListUsesMappedCustomerAndReturnsFullPagination() {
        when(customerIdProvider.requireCurrentCustomerId())
            .thenReturn(CUSTOMER_ID);
        when(jdbcTemplate.queryForObject(
            contains("SELECT COUNT(*)"),
            eq(Long.class),
            any(Object[].class)))
            .thenReturn(21L);
        when(jdbcTemplate.query(
            contains("SELECT r.*"),
            any(RowMapper.class),
            any(Object[].class)))
            .thenReturn(List.of());

        Map<String, Object> result = service.myList(
            null, null, null, null, null, 2, 10);

        assertEquals(21L, result.get("total"));
        assertEquals(2, result.get("pageNum"));
        assertEquals(10, result.get("pageSize"));
        assertEquals(3L, result.get("pages"));
        ArgumentCaptor<Object[]> args =
            ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(
            contains("WHERE r.customer_id=?"),
            eq(Long.class),
            args.capture());
        assertEquals(CUSTOMER_ID, args.getValue()[0]);
        verifyNoInteractions(currentUser);
    }

    @Test
    void merchantWritesUseAuthenticatedUserForIdempotency() {
        when(currentUser.requireUserId()).thenReturn(USER_ID);
        when(idempotencyService.executeForUser(
            anyString(),
            eq(USER_ID),
            eq(IDEMPOTENCY_KEY),
            any(),
            any(),
            any()))
            .thenReturn(null);

        service.audit(
            REFUND_ID,
            new RefundAuditRequest(
                1, new BigDecimal("100.00"), null, null),
            IDEMPOTENCY_KEY);
        service.complete(
            REFUND_ID,
            new RefundCompleteRequest(
                new BigDecimal("100.00"), "provider-1", false),
            IDEMPOTENCY_KEY);

        verify(idempotencyService).executeForUser(
            eq("refund:audit"),
            eq(USER_ID),
            eq(IDEMPOTENCY_KEY),
            any(),
            any(),
            any());
        verify(idempotencyService).executeForUser(
            eq("refund:complete"),
            eq(USER_ID),
            eq(IDEMPOTENCY_KEY),
            any(),
            any(),
            any());
        verifyNoInteractions(
            jdbcTemplate, customerIdProvider, outboxEventService);
    }

    @Test
    void completePublishesFullRestockEventOnlyThroughOutbox()
        throws Exception {
        when(currentUser.requireUserId()).thenReturn(USER_ID);
        stubRefundState(2, 1, new BigDecimal("100.00"), 1);
        Map<String, Object> completion = completionRow(1, 2);
        when(jdbcTemplate.queryForMap(
            contains("FOR UPDATE OF oi"), eq(REFUND_ID)))
            .thenReturn(completion);
        when(jdbcTemplate.queryForObject(
            contains("AND restock=TRUE"),
            eq(Long.class),
            eq(10001L),
            eq(REFUND_ID)))
            .thenReturn(0L);
        when(jdbcTemplate.update(
            contains("SET status=3"), any(Object[].class)))
            .thenReturn(1);
        when(jdbcTemplate.update(
            contains("SET status=4"), any(Object[].class)))
            .thenReturn(1);
        RuntimeException stop = new RuntimeException("stop at outbox");
        doThrow(stop).when(outboxEventService).addOutbox(
            eq("sales.refund.completed"),
            eq("REFUND_COMPLETED"),
            eq(REFUND_ID),
            any());

        RuntimeException error = assertThrows(
            RuntimeException.class,
            () -> service.complete(
                REFUND_ID,
                new RefundCompleteRequest(
                    new BigDecimal("100.00"), "provider-1", true),
                IDEMPOTENCY_KEY));

        assertSame(stop, error);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventService).addOutbox(
            eq("sales.refund.completed"),
            eq("REFUND_COMPLETED"),
            eq(REFUND_ID),
            payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> event =
            (Map<String, Object>) payload.getValue();
        assertEquals("RF20260729001", event.get("refundNo"));
        assertEquals("ORD20260729001", event.get("orderNo"));
        assertEquals(10001L, event.get("orderItemId"));
        assertEquals(2001L, event.get("skuId"));
        assertEquals(1L, event.get("warehouseId"));
        assertEquals(301L, event.get("locationId"));
        assertEquals(1, event.get("quantity"));
        assertEquals(true, event.get("restock"));
        assertEquals(
            new BigDecimal("100.00"),
            event.get("actualRefundAmount"));
        OffsetDateTime completedAt = assertInstanceOf(
            OffsetDateTime.class, event.get("completedAt"));
        assertEquals(ZoneOffset.ofHours(8), completedAt.getOffset());
    }

    @Test
    void completeRejectsCumulativeRestockAboveOrderItemQuantity()
        throws Exception {
        when(currentUser.requireUserId()).thenReturn(USER_ID);
        stubRefundState(2, 1, new BigDecimal("100.00"), 2);
        when(jdbcTemplate.queryForMap(
            contains("FOR UPDATE OF oi"), eq(REFUND_ID)))
            .thenReturn(completionRow(2, 2));
        when(jdbcTemplate.queryForObject(
            contains("AND restock=TRUE"),
            eq(Long.class),
            eq(10001L),
            eq(REFUND_ID)))
            .thenReturn(1L);

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.complete(
                REFUND_ID,
                new RefundCompleteRequest(
                    new BigDecimal("100.00"), "provider-1", true),
                IDEMPOTENCY_KEY));

        assertEquals(
            ApiErrorCode.REFUND_AMOUNT_EXCEEDED,
            error.getErrorCode());
        verify(jdbcTemplate, never()).update(
            contains("SET status=3"), any(Object[].class));
        verifyNoInteractions(outboxEventService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelPendingRefundUsesCustomerScopedConditionalUpdate() {
        when(customerIdProvider.requireCurrentCustomerIdForUpdate())
            .thenReturn(CUSTOMER_ID);
        when(jdbcTemplate.update(
            contains("WHERE id=? AND status=0 AND customer_id=?"),
            any(LocalDateTime.class),
            eq(REFUND_ID),
            eq(CUSTOMER_ID)))
            .thenReturn(1);

        service.cancel(REFUND_ID, IDEMPOTENCY_KEY);

        verify(jdbcTemplate).update(
            contains("WHERE id=? AND status=0 AND customer_id=?"),
            any(LocalDateTime.class),
            eq(REFUND_ID),
            eq(CUSTOMER_ID));
        verify(jdbcTemplate, never()).query(
            contains("SELECT customer_id FROM ref_refund"),
            any(RowMapper.class),
            eq(REFUND_ID));
        verify(idempotencyService).execute(
            eq("refund:cancel"),
            eq(CUSTOMER_ID),
            eq(IDEMPOTENCY_KEY),
            any(),
            any(),
            any());
        verifyNoInteractions(currentUser);
    }

    @Test
    void cancelReportsMissingRefund() {
        when(customerIdProvider.requireCurrentCustomerIdForUpdate())
            .thenReturn(CUSTOMER_ID);
        when(jdbcTemplate.update(
            anyString(),
            any(LocalDateTime.class),
            eq(REFUND_ID),
            eq(CUSTOMER_ID)))
            .thenReturn(0);
        when(jdbcTemplate.query(
            contains("SELECT customer_id FROM ref_refund"),
            any(RowMapper.class),
            eq(REFUND_ID)))
            .thenReturn(List.of());

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.cancel(REFUND_ID, IDEMPOTENCY_KEY));

        assertEquals(ApiErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelRejectsForeignRefund() {
        when(customerIdProvider.requireCurrentCustomerIdForUpdate())
            .thenReturn(CUSTOMER_ID);
        when(jdbcTemplate.update(
            anyString(),
            any(LocalDateTime.class),
            eq(REFUND_ID),
            eq(CUSTOMER_ID)))
            .thenReturn(0);
        when(jdbcTemplate.query(
            contains("SELECT customer_id FROM ref_refund"),
            any(RowMapper.class),
            eq(REFUND_ID)))
            .thenReturn(List.of(CUSTOMER_ID + 1));

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.cancel(REFUND_ID, IDEMPOTENCY_KEY));

        assertEquals(
            ApiErrorCode.RESOURCE_FORBIDDEN,
            error.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelRejectsOwnedNonPendingRefund() {
        when(customerIdProvider.requireCurrentCustomerIdForUpdate())
            .thenReturn(CUSTOMER_ID);
        when(jdbcTemplate.update(
            anyString(),
            any(LocalDateTime.class),
            eq(REFUND_ID),
            eq(CUSTOMER_ID)))
            .thenReturn(0);
        when(jdbcTemplate.query(
            contains("SELECT customer_id FROM ref_refund"),
            any(RowMapper.class),
            eq(REFUND_ID)))
            .thenReturn(List.of(CUSTOMER_ID));

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.cancel(REFUND_ID, IDEMPOTENCY_KEY));

        assertEquals(
            ApiErrorCode.ORDER_STATE_INVALID,
            error.getErrorCode());
    }

    @SuppressWarnings("unchecked")
    private void stubRefundState(
        int type,
        int status,
        BigDecimal approvedAmount,
        Integer approvedQuantity) throws Exception {
        ResultSet result = org.mockito.Mockito.mock(ResultSet.class);
        when(result.getLong("id")).thenReturn(REFUND_ID);
        when(result.getLong("order_id")).thenReturn(9001L);
        when(result.getLong("order_item_id")).thenReturn(10001L);
        when(result.getLong("customer_id")).thenReturn(CUSTOMER_ID);
        when(result.getInt("type")).thenReturn(type);
        when(result.getInt("status")).thenReturn(status);
        when(result.getBigDecimal("apply_refund_amount"))
            .thenReturn(new BigDecimal("100.00"));
        when(result.getInt("apply_refund_quantity"))
            .thenReturn(approvedQuantity == null ? 0 : approvedQuantity);
        when(result.getBigDecimal("approved_amount"))
            .thenReturn(approvedAmount);
        when(result.getObject("approved_quantity"))
            .thenReturn(approvedQuantity);
        when(result.getInt("approved_quantity"))
            .thenReturn(approvedQuantity == null ? 0 : approvedQuantity);
        when(jdbcTemplate.query(
            contains("SELECT id, order_id"),
            any(RowMapper.class),
            eq(REFUND_ID)))
            .thenAnswer(invocation -> {
                RowMapper<Object> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(result, 0));
            });
    }

    private Map<String, Object> completionRow(
        int refundQuantity, int orderItemQuantity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("refund_no", "RF20260729001");
        row.put("order_no", "ORD20260729001");
        row.put("order_item_id", 10001L);
        row.put("sku_id", 2001L);
        row.put("warehouse_id", 1L);
        row.put("location_id", 301L);
        row.put("refund_quantity", refundQuantity);
        row.put("order_item_quantity", orderItemQuantity);
        return row;
    }
}
