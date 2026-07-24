package com.example.demo.controller;

import com.example.demo.common.PageResult;
import com.example.demo.common.Result;
import com.example.demo.service.InventoryQueryService;
import com.example.demo.service.ProductCatalogService;
import com.example.demo.vo.CategoryTreeResponse;
import com.example.demo.vo.LocationTreeResponse;
import com.example.demo.vo.ProductSummaryResponse;
import com.example.demo.vo.StockLogResponse;
import com.example.demo.vo.StockSummaryResponse;
import com.example.demo.vo.WarehouseResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/app")
public class AppDataController {

    private final ProductCatalogService productCatalogService;
    private final InventoryQueryService inventoryQueryService;

    public AppDataController(ProductCatalogService productCatalogService, InventoryQueryService inventoryQueryService) {
        this.productCatalogService = productCatalogService;
        this.inventoryQueryService = inventoryQueryService;
    }

    @GetMapping("/categories/tree")
    public Result<List<CategoryTreeResponse>> categoryTree() {
        return Result.success("查询成功", productCatalogService.categoryTree());
    }

    @GetMapping("/products")
    public Result<PageResult<ProductSummaryResponse>> products(@RequestParam(required = false) String keyword,
                                                               @RequestParam(required = false) Long categoryId,
                                                               @RequestParam(required = false) Integer status,
                                                               @RequestParam(defaultValue = "1") @Min(value = 1, message = "必须大于 0") Integer pageNum,
                                                               @RequestParam(defaultValue = "10") @Min(value = 1, message = "必须大于 0") @Max(value = 100, message = "不能大于 100") Integer pageSize) {
        return Result.success("查询成功", PageResult.of(productCatalogService.listProducts(keyword, categoryId, status, pageNum, pageSize)));
    }

    @GetMapping("/products/{productId}")
    public Result<ProductSummaryResponse> productDetail(@PathVariable @Min(value = 1, message = "必须大于 0") Long productId) {
        return Result.success("查询成功", productCatalogService.productDetail(productId));
    }

    @GetMapping("/warehouses")
    public Result<List<WarehouseResponse>> warehouses() {
        return Result.success("查询成功", inventoryQueryService.listWarehouses());
    }

    @GetMapping("/locations/tree")
    public Result<List<LocationTreeResponse>> locationTree(@RequestParam(required = false) Long warehouseId) {
        return Result.success("查询成功", inventoryQueryService.locationTree(warehouseId));
    }

    @GetMapping("/inventory")
    public Result<PageResult<StockSummaryResponse>> inventory(@RequestParam(required = false) Long skuId,
                                                              @RequestParam(required = false) Long productId,
                                                              @RequestParam(required = false) Long warehouseId,
                                                              @RequestParam(required = false) Long locationId,
                                                              @RequestParam(required = false) String keyword,
                                                              @RequestParam(defaultValue = "1") @Min(value = 1, message = "必须大于 0") Integer pageNum,
                                                              @RequestParam(defaultValue = "10") @Min(value = 1, message = "必须大于 0") @Max(value = 100, message = "不能大于 100") Integer pageSize) {
        return Result.success("查询成功", PageResult.of(inventoryQueryService.queryStock(skuId, productId, warehouseId, locationId, keyword, pageNum, pageSize)));
    }

    @GetMapping("/stock-logs")
    public Result<PageResult<StockLogResponse>> stockLogs(@RequestParam(required = false) Long skuId,
                                                          @RequestParam(required = false) Integer type,
                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                                          @RequestParam(defaultValue = "1") @Min(value = 1, message = "必须大于 0") Integer pageNum,
                                                          @RequestParam(defaultValue = "10") @Min(value = 1, message = "必须大于 0") @Max(value = 100, message = "不能大于 100") Integer pageSize) {
        return Result.success("查询成功", PageResult.of(inventoryQueryService.listStockLogs(skuId, type, startDate, endDate, pageNum, pageSize)));
    }
}
