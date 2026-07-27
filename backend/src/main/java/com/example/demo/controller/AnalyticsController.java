package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.AnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) { this.service = service; }

    @GetMapping("/dashboard/stock")
    @PreAuthorize("hasAnyRole('ADMIN','BUYER','KEEPER','SELLER')")
    public Result<Map<String, Object>> dashboard() {
        return Result.success("查询成功", service.stockDashboard());
    }

    @GetMapping("/report/sales")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<Map<String, Object>> sales(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
        @RequestParam(defaultValue = "day") String granularity) {
        return Result.success("查询成功", service.salesReport(startDate, endDate, granularity));
    }

    @GetMapping("/report/purchase")
    @PreAuthorize("hasAnyRole('ADMIN','BUYER')")
    public Result<Map<String, Object>> purchase(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.success("查询成功", service.purchaseReport(startDate, endDate));
    }
}
