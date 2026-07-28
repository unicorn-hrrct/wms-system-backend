package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.BrowseHistoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/browse-history")
public class BrowseHistoryController {

    private final BrowseHistoryService service;

    public BrowseHistoryController(BrowseHistoryService service) {
        this.service = service;
    }

    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
                                            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success("query success", service.list(pageNum, pageSize));
    }

    @PostMapping
    public Result<Map<String, Object>> record(@Valid @RequestBody BrowseHistoryRecordRequest request) {
        return Result.success("recorded", service.record(request.productId(), request.skuId()));
    }

    @DeleteMapping("/{productId}")
    public Result<Void> delete(@PathVariable @Min(1) Long productId) {
        service.delete(productId);
        return Result.success("removed", null);
    }

    @DeleteMapping
    public Result<Void> clear() {
        service.clear();
        return Result.success("cleared", null);
    }

    public record BrowseHistoryRecordRequest(@NotNull @Min(1) Long productId,
                                             @Min(1) Long skuId) {
    }
}
