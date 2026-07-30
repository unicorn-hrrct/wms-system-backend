package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.SalesService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
public class OrderWebController {

    private final SalesService service;

    public OrderWebController(SalesService service) {
        this.service = service;
    }

    @GetMapping({"/api/v1/web/order", "/api/v1/web/order/list"})
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<Map<String, Object>> list(@RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) String orderNo,
                                            @RequestParam(required = false) String username,
                                            @RequestParam(required = false) String customerKeyword,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                            LocalDateTime startDate,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                            LocalDateTime endDate,
                                            @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
                                            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success("查询成功", service.merchantOrders(status, orderNo, username, customerKeyword,
            startDate, endDate, pageNum, pageSize));
    }
}
