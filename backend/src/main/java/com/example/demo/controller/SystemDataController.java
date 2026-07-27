package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.SystemDataService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class SystemDataController {

    private final SystemDataService service;

    public SystemDataController(SystemDataService service) { this.service = service; }

    @GetMapping("/log/operation")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> logs(@RequestParam(required = false) String username,
                                            @RequestParam(required = false) String module,
                                            @RequestParam(required = false) String operation,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                            @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
                                            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success("查询成功", service.operationLogs(username, module, operation, startDate, endDate, pageNum, pageSize));
    }

    @GetMapping("/dict/type/{dictType}")
    public Result<List<Map<String, Object>>> dict(@PathVariable String dictType) {
        return Result.success("查询成功", service.dict(dictType));
    }

    @GetMapping("/config/list")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<Map<String, Object>>> configs() {
        return Result.success("查询成功", service.configs());
    }
}
