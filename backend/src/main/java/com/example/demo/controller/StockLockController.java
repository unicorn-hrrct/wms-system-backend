package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.StockLockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 10.1 库存超卖控制：Redis 分布式锁 + Lua 脚本原子扣减。
 */
@RestController
@RequestMapping("/api/v1/stock/lock")
public class StockLockController {

    private final StockLockService service;

    public StockLockController(StockLockService service) {
        this.service = service;
    }

    @PostMapping("/decrease")
    public Result<StockLockService.LockResult> decrease(@Valid @RequestBody DecreaseRequest request) {
        StockLockService.LockResult result = service.decrease(
            request.skuId(), request.quantity(), request.orderNo(), request.expireSeconds());
        return Result.success("库存锁定成功", result);
    }

    @PostMapping("/release")
    public Result<Void> release(@Valid @RequestBody ReleaseRequest request) {
        service.release(request.lockId(), request.action());
        return Result.success("库存锁已处理", null);
    }

    public record DecreaseRequest(@NotNull @Min(1) Long skuId,
                                  @NotNull @Min(1) Integer quantity,
                                  @NotBlank String orderNo,
                                  Integer expireSeconds) {
    }

    public record ReleaseRequest(@NotBlank String lockId,
                                 @NotBlank String action) {
    }

    // 让 Result.of 推断不报 raw-type 警告的空占位
    @SuppressWarnings("unused")
    private static Map<String, Object> emptyMap() { return Map.of(); }
}