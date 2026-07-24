package com.example.demo.controller;

import com.example.demo.dto.CategoryCreateRequest;
import com.example.demo.dto.CategoryUpdateRequest;
import com.example.demo.dto.StatusUpdateRequest;
import com.example.demo.common.Result;
import com.example.demo.service.ProductCatalogService;
import com.example.demo.vo.CategoryResponse;
import com.example.demo.vo.CategoryTreeResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final ProductCatalogService productCatalogService;

    public CategoryController(ProductCatalogService productCatalogService) {
        this.productCatalogService = productCatalogService;
    }

    @GetMapping("/tree")
    public Result<List<CategoryTreeResponse>> tree() {
        return Result.success("查询成功", productCatalogService.managementCategoryTree());
    }

    @PostMapping
    public Result<CategoryResponse> create(@Valid @RequestBody CategoryCreateRequest request) {
        return Result.success("创建成功", productCatalogService.createCategory(request));
    }

    @PutMapping("/{id}")
    public Result<CategoryResponse> update(@PathVariable @Min(value = 1, message = "必须大于 0") Long id,
                                           @Valid @RequestBody CategoryUpdateRequest request) {
        return Result.success("更新成功", productCatalogService.updateCategory(id, request));
    }

    @PutMapping("/{id}/status")
    public Result<CategoryResponse> updateStatus(@PathVariable @Min(value = 1, message = "必须大于 0") Long id,
                                                 @Valid @RequestBody StatusUpdateRequest request) {
        return Result.success("更新成功", productCatalogService.updateCategoryStatus(id, request.getStatus()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable @Min(value = 1, message = "必须大于 0") Long id) {
        productCatalogService.deleteCategory(id);
        return Result.success();
    }
}
