package com.example.demo.controller;

import com.example.demo.common.PageResult;
import com.example.demo.common.Result;
import com.example.demo.dto.ProductCreateRequest;
import com.example.demo.dto.ProductUpdateRequest;
import com.example.demo.dto.SkuCreateRequest;
import com.example.demo.dto.StatusUpdateRequest;
import com.example.demo.service.ProductCatalogService;
import com.example.demo.vo.ProductCreateResponse;
import com.example.demo.vo.ProductSkuResponse;
import com.example.demo.vo.ProductSummaryResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductCatalogService productCatalogService;

    public ProductController(ProductCatalogService productCatalogService) {
        this.productCatalogService = productCatalogService;
    }

    @GetMapping
    public Result<PageResult<ProductSummaryResponse>> list(@RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) Long categoryId,
                                                           @RequestParam(required = false) Integer status,
                                                           @RequestParam(defaultValue = "1") @Min(value = 1, message = "必须大于 0") Integer pageNum,
                                                           @RequestParam(defaultValue = "10") @Min(value = 1, message = "必须大于 0") @Max(value = 100, message = "不能大于 100") Integer pageSize) {
        return Result.success("查询成功", PageResult.of(productCatalogService.listProducts(keyword, categoryId, status, pageNum, pageSize)));
    }

    @GetMapping("/{id}")
    public Result<ProductSummaryResponse> detail(@PathVariable @Min(value = 1, message = "必须大于 0") Long id) {
        return Result.success("查询成功", productCatalogService.productDetail(id));
    }

    @PostMapping
    public Result<ProductCreateResponse> create(@Valid @RequestBody ProductCreateRequest request) {
        return Result.success("创建成功", productCatalogService.createProduct(request));
    }

    @PutMapping("/{id}")
    public Result<ProductSummaryResponse> update(@PathVariable @Min(value = 1, message = "必须大于 0") Long id,
                                                 @Valid @RequestBody ProductUpdateRequest request) {
        return Result.success("更新成功", productCatalogService.updateProduct(id, request));
    }

    @PutMapping("/{id}/status")
    public Result<ProductSummaryResponse> updateStatus(@PathVariable @Min(value = 1, message = "必须大于 0") Long id,
                                                       @Valid @RequestBody StatusUpdateRequest request) {
        return Result.success("更新成功", productCatalogService.updateProductStatus(id, request.getStatus()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable @Min(value = 1, message = "必须大于 0") Long id) {
        productCatalogService.deleteProduct(id);
        return Result.success();
    }

    @PostMapping("/import")
    public Result<Map<String, Object>> importProducts(@RequestParam("file") MultipartFile file) {
        return Result.success("导入完成", productCatalogService.importProducts(file));
    }

    @PostMapping("/{id}/skus")
    public Result<ProductSkuResponse> addSku(@PathVariable @Min(value = 1, message = "必须大于 0") Long id,
                                             @Valid @RequestBody SkuCreateRequest request) {
        return Result.success("创建成功", productCatalogService.addSku(id, request));
    }
}
