package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.OwnerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 10.5 多仓库 / 多货主。
 */
@RestController
@RequestMapping("/api/v1")
public class OwnerController {

    private final OwnerService service;

    public OwnerController(OwnerService service) {
        this.service = service;
    }

    @GetMapping("/owner/list")
    @PreAuthorize("hasAnyRole('ADMIN','BUYER','KEEPER')")
    public Result<List<Map<String, Object>>> list() {
        return Result.success("查询成功", service.list());
    }

    @GetMapping("/stock/by-owner")
    @PreAuthorize("hasAnyRole('ADMIN','BUYER','KEEPER')")
    public Result<Map<String, Object>> stockByOwner(@RequestParam @NotNull @Min(1) Long ownerId,
                                                    @RequestParam(required = false) @Min(1) Long warehouseId) {
        return Result.success("查询成功", service.stockByOwner(ownerId, warehouseId));
    }

    @PostMapping("/stock/isolation-check")
    @PreAuthorize("hasAnyRole('ADMIN','BUYER','KEEPER')")
    public Result<Map<String, Object>> isolationCheck(@Valid @RequestBody IsolationRequest request) {
        return Result.success("校验完成",
            service.isolationCheck(request.ownerId(), request.warehouseId(), request.skuId(), request.action()));
    }

    public record IsolationRequest(@NotNull @Min(1) Long ownerId,
                                   @NotNull @Min(1) Long warehouseId,
                                   @NotNull @Min(1) Long skuId,
                                   @NotBlank String action) {
    }
}