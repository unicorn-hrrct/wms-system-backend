package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.SalesService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cart")
public class CartController {

    private final SalesService service;

    public CartController(SalesService service) {
        this.service = service;
    }

    @PostMapping("/add")
    public Result<Map<String, Object>> add(@Valid @RequestBody CartAddRequest request) {
        return Result.success("加入购物车成功", service.addCart(request.skuId(), request.quantity()));
    }

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list() {
        return Result.success("查询成功", service.cartList());
    }

    @PutMapping("/item/{cartItemId}")
    public Result<Map<String, Object>> update(@PathVariable @Min(1) Long cartItemId,
                                              @Valid @RequestBody CartUpdateRequest request) {
        return Result.success("修改成功", service.updateCart(cartItemId, request.quantity()));
    }

    @DeleteMapping("/item/{cartItemId}")
    public Result<Void> delete(@PathVariable @Min(1) Long cartItemId) {
        service.deleteCart(cartItemId);
        return Result.success("删除成功", null);
    }

    public record CartAddRequest(@NotNull Long skuId, @NotNull @Min(1) Integer quantity) {
    }

    public record CartUpdateRequest(@NotNull @Min(1) Integer quantity) {
    }
}
