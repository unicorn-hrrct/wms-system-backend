package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.ProductFavoriteService;
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
@RequestMapping("/api/v1/favorites")
public class ProductFavoriteController {

    private final ProductFavoriteService service;

    public ProductFavoriteController(ProductFavoriteService service) {
        this.service = service;
    }

    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
                                            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success("query success", service.list(pageNum, pageSize));
    }

    @PostMapping
    public Result<Map<String, Object>> add(@Valid @RequestBody FavoriteCreateRequest request) {
        return Result.success("favorite added", service.add(request.productId()));
    }

    @DeleteMapping("/{productId}")
    public Result<Void> delete(@PathVariable @Min(1) Long productId) {
        service.delete(productId);
        return Result.success("favorite removed", null);
    }

    @GetMapping("/check")
    public Result<Map<String, Object>> check(@RequestParam @Min(1) Long productId) {
        return Result.success("query success", service.check(productId));
    }

    public record FavoriteCreateRequest(@NotNull @Min(1) Long productId) {
    }
}
