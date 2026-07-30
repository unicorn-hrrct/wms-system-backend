package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.entity.Customer;
import com.example.demo.exception.BusinessException;
import com.example.demo.mapper.CustomerMapper;
import com.example.demo.security.CurrentUserProvider;
import com.example.demo.security.CustomerIdProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerAddressServiceContractTest {

    private static final long USER_ID = 2L;
    private static final long CUSTOMER_ID = 5002L;
    private static final long ADDRESS_ID = 201L;
    private static final String IDEMPOTENCY_KEY =
        "7f340162-7cb7-4dcf-9333-b3a9f4af304c";

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private CustomerMapper customerMapper;
    @Mock
    private CurrentUserProvider currentUser;
    @Mock
    private CustomerIdProvider customerIdProvider;
    @Mock
    private SalesIdempotencyService idempotencyService;

    private CustomerService customerService;
    private AddressService addressService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerService(
            jdbcTemplate,
            customerMapper,
            currentUser,
            customerIdProvider,
            idempotencyService);
        addressService = new AddressService(
            jdbcTemplate,
            customerIdProvider,
            idempotencyService);
        lenient().when(idempotencyService.execute(
            anyString(), anyLong(), anyString(), any(), any(), any()))
            .thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(5)).get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void customerUpdateReturnsTheDatabaseUpdatedRow() {
        when(currentUser.requireUserId()).thenReturn(USER_ID);
        when(customerMapper.selectList(any()))
            .thenReturn(List.of(existingCustomer()));
        when(customerIdProvider.requireCurrentCustomerIdForUpdate())
            .thenReturn(CUSTOMER_ID);
        RuntimeException stop = new RuntimeException("stop at returning");
        when(jdbcTemplate.queryForObject(
            anyString(), any(RowMapper.class), any(Object[].class)))
            .thenThrow(stop);

        RuntimeException error = assertThrows(
            RuntimeException.class,
            () -> customerService.updateCurrent(
                "新昵称", null, "new@example.com", IDEMPOTENCY_KEY));

        assertSame(stop, error);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(
            sql.capture(), any(RowMapper.class), any(Object[].class));
        assertTrue(sql.getValue().contains("WHERE id=? AND deleted=0"));
        assertTrue(sql.getValue().contains(
            "RETURNING id, user_id, nickname, phone, email, level, registered_at"));
    }

    @Test
    void addressCreateUsesMappedCustomerIdempotencySubject() {
        when(customerIdProvider.requireCurrentCustomerIdForUpdate())
            .thenReturn(CUSTOMER_ID);
        when(idempotencyService.execute(
            eq("address:create"),
            eq(CUSTOMER_ID),
            eq(IDEMPOTENCY_KEY),
            any(),
            any(),
            any()))
            .thenReturn(null);

        addressService.create(
            "李明",
            "13900139000",
            "广东省",
            "深圳市",
            "南山区",
            "科技园南路1号",
            true,
            IDEMPOTENCY_KEY);

        verify(idempotencyService).execute(
            eq("address:create"),
            eq(CUSTOMER_ID),
            eq(IDEMPOTENCY_KEY),
            any(),
            any(),
            any());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void foreignAddressUsesResourceForbiddenInsteadOfNotFound() {
        when(customerIdProvider.requireCurrentCustomerId())
            .thenReturn(CUSTOMER_ID);
        when(jdbcTemplate.query(
            contains("WHERE id=? AND customer_id=?"),
            any(RowMapper.class),
            eq(ADDRESS_ID),
            eq(CUSTOMER_ID)))
            .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(
            contains("WHERE id=? AND deleted=0"),
            eq(Integer.class),
            eq(ADDRESS_ID)))
            .thenReturn(1);

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> addressService.get(ADDRESS_ID));

        assertEquals(
            ApiErrorCode.RESOURCE_FORBIDDEN,
            error.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void foreignAddressWritesUseResourceForbiddenWithoutMutation() {
        when(customerIdProvider.requireCurrentCustomerIdForUpdate())
            .thenReturn(CUSTOMER_ID);
        when(jdbcTemplate.query(
            contains("SELECT is_default"),
            any(RowMapper.class),
            eq(ADDRESS_ID),
            eq(CUSTOMER_ID)))
            .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(
            contains("WHERE id=? AND deleted=0"),
            eq(Integer.class),
            eq(ADDRESS_ID)))
            .thenReturn(1);

        BusinessException updateError = assertThrows(
            BusinessException.class,
            () -> addressService.update(
                ADDRESS_ID,
                "李明",
                "13900139000",
                null,
                null,
                null,
                null,
                null,
                IDEMPOTENCY_KEY));
        BusinessException deleteError = assertThrows(
            BusinessException.class,
            () -> addressService.delete(ADDRESS_ID, IDEMPOTENCY_KEY));
        BusinessException defaultError = assertThrows(
            BusinessException.class,
            () -> addressService.setDefault(ADDRESS_ID, IDEMPOTENCY_KEY));

        assertEquals(ApiErrorCode.RESOURCE_FORBIDDEN, updateError.getErrorCode());
        assertEquals(ApiErrorCode.RESOURCE_FORBIDDEN, deleteError.getErrorCode());
        assertEquals(ApiErrorCode.RESOURCE_FORBIDDEN, defaultError.getErrorCode());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletingTheLastAddressIsRejectedWithoutMutation() {
        when(customerIdProvider.requireCurrentCustomerIdForUpdate())
            .thenReturn(CUSTOMER_ID);
        when(jdbcTemplate.query(
            contains("SELECT is_default"),
            any(RowMapper.class),
            eq(ADDRESS_ID),
            eq(CUSTOMER_ID)))
            .thenReturn(List.of(true));
        when(jdbcTemplate.queryForObject(
            contains("SELECT COUNT(*) FROM crm_address"),
            eq(Integer.class),
            eq(CUSTOMER_ID)))
            .thenReturn(1);

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> addressService.delete(ADDRESS_ID, IDEMPOTENCY_KEY));

        assertEquals(ApiErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("最后一条地址不允许删除"));
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void referencedAddressAllowsOnlyReceiverFieldUpdates() {
        when(customerIdProvider.requireCurrentCustomerIdForUpdate())
            .thenReturn(CUSTOMER_ID);
        when(jdbcTemplate.query(
            contains("SELECT is_default"),
            any(RowMapper.class),
            eq(ADDRESS_ID),
            eq(CUSTOMER_ID)))
            .thenReturn(List.of(false));
        when(jdbcTemplate.queryForObject(
            contains("FROM ord_order WHERE address_id=?"),
            eq(Integer.class),
            eq(ADDRESS_ID)))
            .thenReturn(1);
        when(jdbcTemplate.queryForObject(
            contains("SELECT * FROM crm_address WHERE id=? AND customer_id=?"),
            any(RowMapper.class),
            eq(ADDRESS_ID),
            eq(CUSTOMER_ID)))
            .thenReturn(Map.of("addressId", ADDRESS_ID));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Map<String, Object> result = addressService.update(
            ADDRESS_ID,
            "新收货人",
            "13900139000",
            null,
            null,
            null,
            null,
            null,
            IDEMPOTENCY_KEY);

        assertEquals(ADDRESS_ID, result.get("addressId"));
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("receiver_name=?"));
        assertTrue(sql.getValue().contains("receiver_phone=?"));
        assertFalse(sql.getValue().contains("province=?"));
        assertFalse(sql.getValue().contains("detail_address=?"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void referencedAddressRejectsGeographicAndDefaultChanges() {
        when(customerIdProvider.requireCurrentCustomerIdForUpdate())
            .thenReturn(CUSTOMER_ID);
        when(jdbcTemplate.query(
            contains("SELECT is_default"),
            any(RowMapper.class),
            eq(ADDRESS_ID),
            eq(CUSTOMER_ID)))
            .thenReturn(List.of(false));
        when(jdbcTemplate.queryForObject(
            contains("FROM ord_order WHERE address_id=?"),
            eq(Integer.class),
            eq(ADDRESS_ID)))
            .thenReturn(1);

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> addressService.update(
                ADDRESS_ID,
                "李明",
                "13900139000",
                "广东省",
                null,
                null,
                null,
                null,
                IDEMPOTENCY_KEY));

        assertEquals(ApiErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("仅允许修改收货人姓名和手机号"));
    }

    private Customer existingCustomer() {
        Customer customer = new Customer();
        customer.setId(CUSTOMER_ID);
        customer.setUserId(USER_ID);
        customer.setNickname("Alice");
        customer.setPhone("13800138001");
        customer.setEmail("alice@example.com");
        customer.setLevel("NORMAL");
        customer.setRegisteredAt(LocalDateTime.of(2026, 5, 12, 9, 30));
        return customer;
    }
}
