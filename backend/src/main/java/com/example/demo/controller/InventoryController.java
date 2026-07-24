package com.example.demo.controller;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.common.PageResult;
import com.example.demo.common.Result;
import com.example.demo.service.InventoryCommandService;
import com.example.demo.service.InventoryQueryService;
import com.example.demo.vo.LocationTreeResponse;
import com.example.demo.vo.StockLogResponse;
import com.example.demo.vo.StockSummaryResponse;
import com.example.demo.vo.WarehouseResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1")
public class InventoryController {

    private final InventoryQueryService queryService;
    private final InventoryCommandService commandService;

    public InventoryController(InventoryQueryService queryService, InventoryCommandService commandService) {
        this.queryService = queryService;
        this.commandService = commandService;
    }

    @GetMapping("/warehouse/list")
    public Result<List<WarehouseResponse>> warehouses() {
        return Result.success("查询成功", queryService.listWarehouses());
    }

    @GetMapping("/location/tree")
    public Result<List<LocationTreeResponse>> locations(@RequestParam(required = false) Long warehouseId) {
        return Result.success("查询成功", queryService.locationTree(warehouseId));
    }

    @DeleteMapping("/location/{locationId}")
    @PreAuthorize("hasAnyRole('ADMIN','KEEPER')")
    public Result<Map<String, Object>> deleteLocation(@PathVariable @Min(1) Long locationId,
                                                      @RequestParam(defaultValue = "false") Boolean force,
                                                      @RequestParam(defaultValue = "false") Boolean cascade) {
        Map<String, Object> data = commandService.deleteLocation(locationId, force, cascade);
        if (data.get("tipCode") != null
            && ApiErrorCode.FORCE_DELETE_LOCATION_OK.getCode() == (Integer) data.get("tipCode")) {
            return Result.of(ApiErrorCode.FORCE_DELETE_LOCATION_OK.getCode(),
                ApiErrorCode.FORCE_DELETE_LOCATION_OK.getMessage(), data);
        }
        return Result.success("删除成功", data);
    }

    @GetMapping("/stock/query")
    public Result<PageResult<StockSummaryResponse>> stock(@RequestParam(required = false) Long skuId,
                                                          @RequestParam(required = false) Long productId,
                                                          @RequestParam(required = false) Long warehouseId,
                                                          @RequestParam(required = false) Long locationId,
                                                          @RequestParam(required = false) String keyword,
                                                          @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
                                                          @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success("查询成功", PageResult.of(queryService.queryStock(
            skuId, productId, warehouseId, locationId, keyword, pageNum, pageSize)));
    }

    @GetMapping("/stock/log/list")
    public Result<PageResult<StockLogResponse>> logs(@RequestParam(required = false) Long skuId,
                                                      @RequestParam(required = false) Integer type,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                                      @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
                                                      @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success("查询成功", PageResult.of(queryService.listStockLogs(skuId, type, startDate, endDate, pageNum, pageSize)));
    }

    @GetMapping("/stock/alert/list")
    @PreAuthorize("hasAnyRole('ADMIN','KEEPER','BUYER')")
    public Result<List<Map<String, Object>>> alerts(@RequestParam(required = false) Long warehouseId,
                                                    @RequestParam(required = false) String alertType) {
        return Result.success("查询成功", commandService.alerts(warehouseId, alertType));
    }

    @PutMapping("/stock/safety-stock")
    @PreAuthorize("hasAnyRole('ADMIN','KEEPER','BUYER')")
    public Result<Map<String, Object>> safetyStock(@Valid @RequestBody SafetyStockRequest request) {
        Map<String, Object> data = commandService.updateSafetyStock(
            request.skuId(), request.warehouseId(), request.locationId(),
            request.minStock(), request.maxStock(), request.force());
        if (data.get("tipCode") != null
            && ApiErrorCode.SAFETY_STOCK_EXCEEDED.getCode() == (Integer) data.get("tipCode")) {
            return Result.of(ApiErrorCode.SAFETY_STOCK_EXCEEDED.getCode(),
                ApiErrorCode.SAFETY_STOCK_EXCEEDED.getMessage(), data);
        }
        return Result.success("安全水位配置成功", data);
    }

    @PostMapping("/stock/check")
    @PreAuthorize("hasAnyRole('ADMIN','KEEPER')")
    public Result<Map<String, Object>> createCheck(@Valid @RequestBody StockCheckRequest request) {
        List<InventoryCommandService.CheckItem> items = request.checkItems() == null ? List.of() : request.checkItems().stream()
            .map(item -> new InventoryCommandService.CheckItem(item.skuId(), item.systemQty()))
            .toList();
        return Result.success("盘点单创建成功", commandService.createCheck(request.warehouseId(), request.type(), items));
    }

    @PutMapping("/stock/check/{checkId}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','KEEPER')")
    public Result<Map<String, Object>> submitCheck(@PathVariable @Min(1) Long checkId,
                                                   @Valid @RequestBody StockCheckSubmitRequest request) {
        List<InventoryCommandService.CheckResult> results = request.results().stream()
            .map(item -> new InventoryCommandService.CheckResult(item.skuId(), item.actualQty(), item.diffQty(), item.reason()))
            .toList();
        return Result.success("盘点结果提交成功", commandService.submitCheck(checkId, results));
    }

    @PostMapping("/stock/transfer")
    @PreAuthorize("hasAnyRole('ADMIN','KEEPER')")
    public Result<Map<String, Object>> transfer(@Valid @RequestBody StockTransferRequest request) {
        List<InventoryCommandService.TransferItem> items = request.items().stream()
            .map(item -> new InventoryCommandService.TransferItem(item.skuId(), item.quantity(), item.fromLocationId(), item.toLocationId()))
            .toList();
        return Result.success("库存调拨成功", commandService.transfer(
            request.fromWarehouseId(), request.toWarehouseId(), items, request.remark()));
    }

    public record SafetyStockRequest(@NotNull Long skuId,
                                     @NotNull Long warehouseId,
                                     Long locationId,
                                     @NotNull @Min(0) Integer minStock,
                                     Integer maxStock,
                                     Boolean force) {
    }

    public record StockCheckRequest(@NotNull Long warehouseId,
                                    @NotNull Integer type,
                                    List<@Valid StockCheckItemRequest> checkItems) {
    }

    public record StockCheckItemRequest(@NotNull Long skuId, Integer systemQty) {
    }

    public record StockCheckSubmitRequest(@NotEmpty List<@Valid StockCheckResultRequest> results) {
    }

    public record StockCheckResultRequest(@NotNull Long skuId,
                                          @NotNull @Min(0) Integer actualQty,
                                          Integer diffQty,
                                          String reason) {
    }

    public record StockTransferRequest(@NotNull Long fromWarehouseId,
                                       @NotNull Long toWarehouseId,
                                       @NotEmpty List<@Valid StockTransferItemRequest> items,
                                       String remark) {
    }

    public record StockTransferItemRequest(@NotNull Long skuId,
                                           @NotNull @Min(1) Integer quantity,
                                           @NotNull Long fromLocationId,
                                           @NotNull Long toLocationId) {
    }
}
