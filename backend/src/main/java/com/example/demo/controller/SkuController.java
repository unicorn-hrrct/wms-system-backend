package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.dto.SkuUpdateRequest;
import com.example.demo.dto.StatusUpdateRequest;
import com.example.demo.service.ProductCatalogService;
import com.example.demo.vo.ProductSkuResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/skus")
public class SkuController {

    private final ProductCatalogService productCatalogService;

    public SkuController(ProductCatalogService productCatalogService) {
        this.productCatalogService = productCatalogService;
    }

    @PutMapping("/{skuId}")
    public Result<ProductSkuResponse> update(@PathVariable @Min(value = 1, message = "必须大于 0") Long skuId,
                                             @Valid @RequestBody SkuUpdateRequest request) {
        return Result.success("更新成功", productCatalogService.updateSku(skuId, request));
    }

    @PutMapping("/{skuId}/status")
    public Result<ProductSkuResponse> updateStatus(@PathVariable @Min(value = 1, message = "必须大于 0") Long skuId,
                                                   @Valid @RequestBody StatusUpdateRequest request) {
        return Result.success("更新成功", productCatalogService.updateSkuStatus(skuId, request.getStatus()));
    }

    @DeleteMapping("/{skuId}")
    public Result<Void> delete(@PathVariable @Min(value = 1, message = "必须大于 0") Long skuId) {
        productCatalogService.deleteSku(skuId);
        return Result.success();
    }
}
