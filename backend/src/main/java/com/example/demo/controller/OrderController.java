package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.SalesService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/order")
public class OrderController {

    private final SalesService service;

    public OrderController(SalesService service) {
        this.service = service;
    }

    @PostMapping("/create")
    public Result<Map<String, Object>> create(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                              @Valid @RequestBody OrderCreateRequest request) {
        return Result.success("订单创建成功", service.createOrder(request.addressId(), request.cartItemIds(), request.remark(), idempotencyKey));
    }

    @PostMapping("/{orderId}/pay")
    public Result<Map<String, Object>> pay(@PathVariable @Min(1) Long orderId,
                                           @Valid @RequestBody OrderPayRequest request) {
        return Result.success("支付成功", service.pay(orderId, request.payType()));
    }

    @GetMapping("/{orderId}")
    public Result<Map<String, Object>> detail(@PathVariable @Min(1) Long orderId) {
        return Result.success("查询成功", service.detail(orderId));
    }

    @GetMapping("/my")
    public Result<Map<String, Object>> my(@RequestParam(required = false) Integer status,
                                          @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
                                          @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success("查询成功", service.myOrders(status, pageNum, pageSize));
    }

    @PutMapping("/{orderId}/cancel")
    public Result<Void> cancel(@PathVariable @Min(1) Long orderId, @Valid @RequestBody OrderCancelRequest request) {
        service.cancel(orderId, request.cancelReason());
        return Result.success("订单取消成功", null);
    }

    @PutMapping("/{orderId}/receive")
    public Result<Void> receive(@PathVariable @Min(1) Long orderId) {
        service.receive(orderId);
        return Result.success("确认收货成功", null);
    }

    @PostMapping("/aftersale")
    public Result<Map<String, Object>> aftersale(@Valid @RequestBody AftersaleRequest request,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new com.example.demo.exception.BusinessException(
                com.example.demo.common.ApiErrorCode.BAD_REQUEST, "缺少 Idempotency-Key 请求头");
        }
        return Result.success("售后申请提交成功", service.aftersale(request.orderId(), request.orderItemId(), request.type(),
            request.reason(), request.images(), request.remark(), idempotencyKey));
    }

    @PostMapping("/{orderId}/ship")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<Void> ship(@PathVariable @Min(1) Long orderId, @Valid @RequestBody OrderShipRequest request) {
        service.ship(orderId, request.logisticsCompany(), request.logisticsNo());
        return Result.success("发货成功", null);
    }

    public record OrderCreateRequest(@NotNull Long addressId,
                                     @NotEmpty List<@NotNull Long> cartItemIds,
                                     String remark,
                                     Long couponId) {
    }

    public record OrderPayRequest(@NotNull @Min(1) @Max(3) Integer payType, String payPassword) {
    }

    public record OrderCancelRequest(@NotBlank String cancelReason) {
    }

    public record AftersaleRequest(@NotNull Long orderId,
                                   @NotNull Long orderItemId,
                                   @NotNull @Min(1) @Max(2) Integer type,
                                   @NotBlank String reason,
                                   List<String> images,
                                   String remark) {
    }

    public record OrderShipRequest(@NotBlank String logisticsCompany, @NotBlank String logisticsNo) {
    }
}
