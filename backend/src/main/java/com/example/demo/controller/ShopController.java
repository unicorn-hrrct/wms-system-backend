package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.SalesService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/shop/product")
public class ShopController {

    private final SalesService service;

    public ShopController(SalesService service) {
        this.service = service;
    }

    @GetMapping("/search")
    public Result<Map<String, Object>> search(@RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Long categoryId,
                                              @RequestParam(required = false) BigDecimal minPrice,
                                              @RequestParam(required = false) BigDecimal maxPrice,
                                              @RequestParam(required = false) String sortBy,
                                              @RequestParam(required = false) String sortOrder,
                                              @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
                                              @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success("查询成功", service.search(keyword, categoryId, minPrice, maxPrice,
            sortBy, sortOrder, pageNum, pageSize));
    }
}
