package com.example.demo.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.demo.dto.CategoryCreateRequest;
import com.example.demo.dto.CategoryUpdateRequest;
import com.example.demo.dto.ProductCreateRequest;
import com.example.demo.dto.ProductUpdateRequest;
import com.example.demo.dto.SkuCreateRequest;
import com.example.demo.dto.SkuUpdateRequest;
import com.example.demo.vo.CategoryResponse;
import com.example.demo.vo.CategoryTreeResponse;
import com.example.demo.vo.ProductCreateResponse;
import com.example.demo.vo.ProductSkuResponse;
import com.example.demo.vo.ProductSummaryResponse;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface ProductCatalogService {

    // ==== 只读（App / 管理端共用） ====

    List<CategoryTreeResponse> categoryTree();

    IPage<ProductSummaryResponse> listProducts(String keyword, Long categoryId, Integer status, int pageNum, int pageSize);

    ProductSummaryResponse productDetail(Long productId);

    // ==== 管理端分类（写） ====

    List<CategoryTreeResponse> managementCategoryTree();

    CategoryResponse createCategory(CategoryCreateRequest request);

    CategoryResponse updateCategory(Long categoryId, CategoryUpdateRequest request);

    CategoryResponse updateCategoryStatus(Long categoryId, Integer status);

    void deleteCategory(Long categoryId);

    // ==== 管理端商品（写） ====

    ProductCreateResponse createProduct(ProductCreateRequest request);

    ProductSummaryResponse updateProduct(Long productId, ProductUpdateRequest request);

    ProductSummaryResponse updateProductStatus(Long productId, Integer status);

    void deleteProduct(Long productId);

    Map<String, Object> importProducts(MultipartFile file);

    // ==== 管理端 SKU（写） ====

    ProductSkuResponse addSku(Long productId, SkuCreateRequest request);

    ProductSkuResponse updateSku(Long skuId, SkuUpdateRequest request);

    ProductSkuResponse updateSkuStatus(Long skuId, Integer status);

    void deleteSku(Long skuId);
}
