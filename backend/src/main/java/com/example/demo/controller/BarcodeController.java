package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.BarcodeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 10.4 扫码出入库。
 */
@RestController
@RequestMapping("/api/v1")
public class BarcodeController {

    private final BarcodeService service;

    public BarcodeController(BarcodeService service) {
        this.service = service;
    }

    @GetMapping("/barcode/parse")
    public Result<Map<String, Object>> parse(@RequestParam @NotBlank String code) {
        return Result.success("查询成功", service.parse(code));
    }

    @PostMapping("/scan/inbound")
    public Result<Map<String, Object>> scanInbound(@Valid @RequestBody ScanInboundRequest request) {
        return Result.success("扫码入库成功", service.scanInbound(
            request.barcodes(), request.quantities(),
            request.warehouseId(), request.locationId(),
            request.sourceType(), request.sourceNo(),
            request.batchNo(), request.productionDate(), request.expireDate()));
    }

    @PostMapping("/scan/outbound")
    public Result<Map<String, Object>> scanOutbound(@Valid @RequestBody ScanOutboundRequest request) {
        return Result.success("扫码出库成功", service.scanOutbound(
            request.barcodes(), request.quantities(),
            request.warehouseId(), request.locationId(),
            request.destType(), request.destNo()));
    }

    public record ScanInboundRequest(@NotEmpty List<@NotBlank String> barcodes,
                                     @NotEmpty List<@NotNull @Min(1) Integer> quantities,
                                     @NotNull @Min(1) Long warehouseId,
                                     @NotNull @Min(1) Long locationId,
                                     @NotBlank String sourceType,
                                     @NotBlank String sourceNo,
                                     String batchNo,
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate productionDate,
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expireDate) {
    }

    public record ScanOutboundRequest(@NotEmpty List<@NotBlank String> barcodes,
                                      @NotEmpty List<@NotNull @Min(1) Integer> quantities,
                                      @NotNull @Min(1) Long warehouseId,
                                      @NotNull @Min(1) Long locationId,
                                      @NotBlank String destType,
                                      @NotBlank String destNo) {
    }
}