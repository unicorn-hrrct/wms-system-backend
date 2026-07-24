package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.RestockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 10.6 智能补货提醒。
 */
@RestController
@RequestMapping("/api/v1/restock")
@PreAuthorize("hasAnyRole('ADMIN','BUYER')")
public class RestockController {

    private final RestockService service;

    public RestockController(RestockService service) {
        this.service = service;
    }

    @GetMapping("/suggestion")
    public Result<Map<String, Object>> suggestion(@RequestParam(defaultValue = "0") @Min(0) Integer salesHistoryDays) {
        return Result.success("查询成功", service.suggestions(salesHistoryDays));
    }

    @PostMapping("/generate-order")
    public Result<Map<String, Object>> generateOrder(@Valid @RequestBody GenerateOrderRequest request) {
        return Result.success("采购单生成成功",
            service.generateOrder(request.suggestionIds(), request.autoMerge()));
    }

    @PutMapping("/rule")
    public Result<Map<String, Object>> updateRule(@Valid @RequestBody RuleRequest request) {
        return Result.success("规则更新成功",
            service.updateRule(request.defaultSafetyStockDays(), request.defaultLeadTimeDays(),
                request.salesHistoryDays(), request.enableAutoNotify(), request.notifyChannels()));
    }

    public record GenerateOrderRequest(@NotEmpty List<@NotNull Long> suggestionIds,
                                       Boolean autoMerge) {
    }

    public record RuleRequest(@NotNull @Min(1) Integer defaultSafetyStockDays,
                              @NotNull @Min(1) Integer defaultLeadTimeDays,
                              @NotNull @Min(1) Integer salesHistoryDays,
                              @NotNull Boolean enableAutoNotify,
                              List<String> notifyChannels) {
    }
}