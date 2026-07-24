package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.ProcurementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/purchase")
@PreAuthorize("hasAnyRole('ADMIN','BUYER')")
public class PurchaseController {

    private final ProcurementService service;

    public PurchaseController(ProcurementService service) {
        this.service = service;
    }

    @GetMapping("/request/list")
    public Result<Map<String, Object>> requests(@RequestParam(required = false) String requestNo,
                                                @RequestParam(required = false) Integer status,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.success("查询成功", service.listRequests(requestNo, status, startDate, endDate));
    }

    @PostMapping("/request")
    public Result<Map<String, Object>> createRequest(@Valid @RequestBody PurchaseRequestCreate request) {
        List<ProcurementService.RequestItem> items = request.items().stream()
            .map(item -> new ProcurementService.RequestItem(item.skuId(), item.skuCode(), item.quantity(), item.expectedPrice(), item.remark()))
            .toList();
        return Result.success("申请提交成功", service.createRequest(request.supplierId(), items, request.remark()));
    }

    @PutMapping("/request/{requestId}/audit")
    public Result<Void> audit(@PathVariable @Min(1) Long requestId, @Valid @RequestBody PurchaseAuditRequest request) {
        service.auditRequest(requestId, request.auditStatus(), request.auditRemark());
        return Result.success("审核成功", null);
    }

    @PostMapping("/order")
    public Result<Map<String, Object>> createOrder(@Valid @RequestBody PurchaseOrderCreate request) {
        return Result.success("采购订单生成成功", service.createOrder(request.requestId(), request.deliveryDate()));
    }

    @PutMapping("/order/{orderId}/force-close")
    public Result<Map<String, Object>> forceClose(@PathVariable @Min(1) Long orderId,
                                                  @Valid @RequestBody ForceCloseRequest request) {
        List<ProcurementService.DiscardedItem> items = request.discardedItems() == null ? List.of()
            : request.discardedItems().stream()
            .map(i -> new ProcurementService.DiscardedItem(i.orderItemId(), i.discardedQuantity()))
            .toList();
        return Result.success("强制完结成功",
            service.forceCloseOrder(orderId, request.closeReason(), request.closeType(), items));
    }

    @PostMapping("/inbound")
    @PreAuthorize("hasAnyRole('ADMIN','BUYER','KEEPER')")
    public Result<Map<String, Object>> inbound(@Valid @RequestBody PurchaseInboundRequest request) {
        List<ProcurementService.InboundItem> items = request.items().stream()
            .map(item -> new ProcurementService.InboundItem(item.orderItemId(), item.skuId(), item.actualQuantity(),
                item.locationId(), item.batchNo(), item.productionDate(), item.expireDate(), item.remark()))
            .toList();
        return Result.success("采购入库成功", service.inbound(request.orderId(), request.warehouseId(), items));
    }

    @PostMapping("/return")
    @PreAuthorize("hasAnyRole('ADMIN','BUYER','KEEPER')")
    public Result<Map<String, Object>> createReturn(@Valid @RequestBody PurchaseReturnRequest request) {
        List<ProcurementService.ReturnItem> items = request.items().stream()
            .map(item -> new ProcurementService.ReturnItem(item.skuId(), item.quantity()))
            .toList();
        return Result.success("采购退货申请成功", service.createReturn(request.orderId(), request.reason(), items));
    }

    @PutMapping("/return/{returnId}/confirm")
    @PreAuthorize("hasAnyRole('ADMIN','BUYER','KEEPER')")
    public Result<Map<String, Object>> confirmReturn(@PathVariable @Min(1) Long returnId,
                                                     @RequestBody(required = false) ReturnConfirmRequest request) {
        String remark = request == null ? null : request.confirmRemark();
        return Result.success("退货确认成功", service.confirmReturn(returnId, remark));
    }

    @PutMapping("/return/{returnId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','BUYER','KEEPER')")
    public Result<Map<String, Object>> rejectReturn(@PathVariable @Min(1) Long returnId,
                                                    @Valid @RequestBody ReturnRejectRequest request) {
        return Result.success("退货驳回成功", service.rejectReturn(returnId, request.rejectReason()));
    }

    public record PurchaseRequestCreate(@NotNull Long supplierId,
                                        @NotEmpty List<@Valid PurchaseRequestItem> items,
                                        String remark) {
    }

    public record PurchaseRequestItem(@NotNull Long skuId,
                                      String skuCode,
                                      @NotNull @Min(1) Integer quantity,
                                      @NotNull @DecimalMin("0.00") BigDecimal expectedPrice,
                                      String remark) {
    }

    public record PurchaseAuditRequest(@NotNull Integer auditStatus, String auditRemark) {
    }

    public record PurchaseOrderCreate(@NotNull Long requestId,
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deliveryDate) {
    }

    public record ForceCloseRequest(@NotBlank String closeReason,
                                    Integer closeType,
                                    List<@Valid DiscardedItemRequest> discardedItems) {
    }

    public record DiscardedItemRequest(@NotNull Long orderItemId, @NotNull @Min(1) Integer discardedQuantity) {
    }

    public record PurchaseInboundRequest(@NotNull Long orderId,
                                         @NotNull Long warehouseId,
                                         @NotEmpty List<@Valid PurchaseInboundItem> items) {
    }

    public record PurchaseInboundItem(@NotNull Long orderItemId,
                                      @NotNull Long skuId,
                                      @NotNull @Min(1) Integer actualQuantity,
                                      @NotNull Long locationId,
                                      @NotBlank String batchNo,
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate productionDate,
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expireDate,
                                      String remark) {
    }

    public record PurchaseReturnRequest(@NotNull Long orderId,
                                        @NotBlank String reason,
                                        @NotEmpty List<@Valid PurchaseReturnItem> items) {
    }

    public record PurchaseReturnItem(@NotNull Long skuId, @NotNull @Min(1) Integer quantity) {
    }

    public record ReturnConfirmRequest(String confirmRemark) {
    }

    public record ReturnRejectRequest(@NotBlank String rejectReason) {
    }
}
