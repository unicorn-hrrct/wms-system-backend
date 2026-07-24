package com.example.demo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.common.ApiErrorCode;
import com.example.demo.dto.CategoryCreateRequest;
import com.example.demo.dto.CategoryUpdateRequest;
import com.example.demo.dto.ProductCreateRequest;
import com.example.demo.dto.ProductUpdateRequest;
import com.example.demo.dto.SkuCreateRequest;
import com.example.demo.dto.SkuUpdateRequest;
import com.example.demo.entity.Product;
import com.example.demo.entity.ProductCategory;
import com.example.demo.entity.ProductSku;
import com.example.demo.entity.Stock;
import com.example.demo.exception.BusinessException;
import com.example.demo.mapper.ProductCategoryMapper;
import com.example.demo.mapper.ProductMapper;
import com.example.demo.mapper.ProductSkuMapper;
import com.example.demo.mapper.StockMapper;
import com.example.demo.service.ProductCatalogService;
import com.example.demo.vo.CategoryResponse;
import com.example.demo.vo.CategoryTreeResponse;
import com.example.demo.vo.ProductCreateResponse;
import com.example.demo.vo.ProductSkuResponse;
import com.example.demo.vo.ProductSummaryResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProductCatalogServiceImpl implements ProductCatalogService {

    private final ProductCategoryMapper categoryMapper;
    private final ProductMapper productMapper;
    private final ProductSkuMapper skuMapper;
    private final StockMapper stockMapper;
    private final ObjectMapper objectMapper;

    public ProductCatalogServiceImpl(ProductCategoryMapper categoryMapper,
                                     ProductMapper productMapper,
                                     ProductSkuMapper skuMapper,
                                     StockMapper stockMapper,
                                     ObjectMapper objectMapper) {
        this.categoryMapper = categoryMapper;
        this.productMapper = productMapper;
        this.skuMapper = skuMapper;
        this.stockMapper = stockMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<CategoryTreeResponse> categoryTree() {
        return buildTree(categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
            .eq(ProductCategory::getStatus, 0)
            .orderByAsc(ProductCategory::getParentId)
            .orderByAsc(ProductCategory::getSortOrder)
            .orderByAsc(ProductCategory::getId)));
    }

    @Override
    public List<CategoryTreeResponse> managementCategoryTree() {
        return buildTree(categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
            .orderByAsc(ProductCategory::getParentId)
            .orderByAsc(ProductCategory::getSortOrder)
            .orderByAsc(ProductCategory::getId)));
    }

    private List<CategoryTreeResponse> buildTree(List<ProductCategory> categories) {
        Map<Long, CategoryTreeResponse> byId = new LinkedHashMap<>();
        for (ProductCategory category : categories) {
            CategoryTreeResponse response = new CategoryTreeResponse();
            response.setCategoryId(category.getId());
            response.setCategoryName(category.getCategoryName());
            response.setParentId(category.getParentId());
            response.setSortOrder(category.getSortOrder());
            byId.put(category.getId(), response);
        }

        List<CategoryTreeResponse> roots = new ArrayList<>();
        for (CategoryTreeResponse category : byId.values()) {
            if (category.getParentId() == null || category.getParentId() == 0 || !byId.containsKey(category.getParentId())) {
                roots.add(category);
            } else {
                byId.get(category.getParentId()).getChildren().add(category);
            }
        }
        return roots;
    }

    @Override
    public IPage<ProductSummaryResponse> listProducts(String keyword, Long categoryId, Integer status, int pageNum, int pageSize) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
            .eq(categoryId != null, Product::getCategoryId, categoryId)
            .eq(status != null, Product::getStatus, status)
            .and(StringUtils.hasText(keyword), query -> query
                .like(Product::getProductName, keyword)
                .or()
                .like(Product::getProductCode, keyword))
            .orderByDesc(Product::getCreateTime)
            .orderByAsc(Product::getId);

        Page<Product> page = productMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        Map<Long, String> categoryNames = categoryNameMap();
        Map<Long, Integer> productStock = productStockMap();
        return page.convert(product -> toProductSummary(product, categoryNames, productStock, false));
    }

    @Override
    public ProductSummaryResponse productDetail(Long productId) {
        Product product = requireProduct(productId);
        ProductSummaryResponse response = toProductSummary(product, categoryNameMap(), productStockMap(), true);
        response.setSkuList(skusByProduct(product.getId()).stream().map(this::toSkuResponse).toList());
        return response;
    }

    // ==================== 管理端分类（写） ====================

    @Override
    public CategoryResponse createCategory(CategoryCreateRequest request) {
        Long parentId = request.getParentId() == null ? 0L : request.getParentId();
        if (parentId != 0L) {
            requireCategory(parentId);
        }
        ProductCategory category = new ProductCategory();
        category.setCategoryName(request.getCategoryName());
        category.setParentId(parentId);
        category.setIcon(request.getIcon());
        category.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        category.setStatus(0);
        categoryMapper.insert(category);
        return toCategoryResponse(categoryMapper.selectById(category.getId()));
    }

    @Override
    public CategoryResponse updateCategory(Long categoryId, CategoryUpdateRequest request) {
        ProductCategory category = requireCategory(categoryId);
        if (request.getCategoryName() != null) {
            category.setCategoryName(request.getCategoryName());
        }
        if (request.getParentId() != null) {
            if (request.getParentId().equals(categoryId)) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "父分类不能为自身");
            }
            if (request.getParentId() != 0L) {
                requireCategory(request.getParentId());
            }
            category.setParentId(request.getParentId());
        }
        if (request.getIcon() != null) {
            category.setIcon(request.getIcon());
        }
        if (request.getSortOrder() != null) {
            category.setSortOrder(request.getSortOrder());
        }
        categoryMapper.updateById(category);
        return toCategoryResponse(categoryMapper.selectById(categoryId));
    }

    @Override
    public CategoryResponse updateCategoryStatus(Long categoryId, Integer status) {
        ProductCategory category = requireCategory(categoryId);
        category.setStatus(status);
        categoryMapper.updateById(category);
        return toCategoryResponse(categoryMapper.selectById(categoryId));
    }

    @Override
    public void deleteCategory(Long categoryId) {
        requireCategory(categoryId);
        long children = categoryMapper.selectCount(new LambdaQueryWrapper<ProductCategory>()
            .eq(ProductCategory::getParentId, categoryId));
        if (children > 0) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "存在子分类，无法删除");
        }
        long products = productMapper.selectCount(new LambdaQueryWrapper<Product>()
            .eq(Product::getCategoryId, categoryId));
        if (products > 0) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "分类下存在商品，无法删除");
        }
        categoryMapper.deleteById(categoryId);
    }

    // ==================== 管理端商品（写） ====================

    @Override
    @Transactional
    public ProductCreateResponse createProduct(ProductCreateRequest request) {
        requireCategory(request.getCategoryId());
        if (existsProductCode(request.getProductCode(), null)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "商品编码已存在");
        }

        Product product = new Product();
        product.setProductCode(request.getProductCode());
        product.setProductName(request.getProductName());
        product.setCategoryId(request.getCategoryId());
        product.setMainImage(request.getMainImage());
        product.setUnit(request.getUnit());
        product.setWeight(request.getWeight());
        product.setPurchasePrice(request.getPurchasePrice());
        product.setSalePrice(request.getSalePrice());
        product.setDescription(request.getDescription());
        product.setStatus(0);
        productMapper.insert(product);

        int skuCount = 0;
        List<SkuCreateRequest> skuList = request.getSkuList();
        if (skuList != null && !skuList.isEmpty()) {
            for (SkuCreateRequest skuRequest : skuList) {
                insertSku(product.getId(), skuRequest);
                skuCount++;
            }
        }

        ProductCreateResponse response = new ProductCreateResponse();
        response.setProductId(product.getId());
        response.setProductCode(product.getProductCode());
        response.setSkuCount(skuCount);
        return response;
    }

    @Override
    public ProductSummaryResponse updateProduct(Long productId, ProductUpdateRequest request) {
        Product product = requireProduct(productId);
        if (request.getProductCode() != null) {
            if (existsProductCode(request.getProductCode(), productId)) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "商品编码已存在");
            }
            product.setProductCode(request.getProductCode());
        }
        if (request.getProductName() != null) {
            product.setProductName(request.getProductName());
        }
        if (request.getCategoryId() != null) {
            requireCategory(request.getCategoryId());
            product.setCategoryId(request.getCategoryId());
        }
        if (request.getMainImage() != null) {
            product.setMainImage(request.getMainImage());
        }
        if (request.getUnit() != null) {
            product.setUnit(request.getUnit());
        }
        if (request.getWeight() != null) {
            product.setWeight(request.getWeight());
        }
        if (request.getPurchasePrice() != null) {
            product.setPurchasePrice(request.getPurchasePrice());
        }
        if (request.getSalePrice() != null) {
            product.setSalePrice(request.getSalePrice());
        }
        if (request.getDescription() != null) {
            product.setDescription(request.getDescription());
        }
        productMapper.updateById(product);
        return productDetail(productId);
    }

    @Override
    public ProductSummaryResponse updateProductStatus(Long productId, Integer status) {
        Product product = requireProduct(productId);
        product.setStatus(status);
        productMapper.updateById(product);
        return productDetail(productId);
    }

    @Override
    @Transactional
    public void deleteProduct(Long productId) {
        requireProduct(productId);
        skuMapper.delete(new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getProductId, productId));
        productMapper.deleteById(productId);
    }

    @Override
    @Transactional
    public Map<String, Object> importProducts(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "导入文件不能为空");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!(name.endsWith(".csv") || name.endsWith(".txt") || name.endsWith(".xlsx") || name.endsWith(".xls"))) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "仅支持 .csv/.txt/.xlsx/.xls 文件");
        }
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "当前版本请先将 Excel 另存为 CSV 后导入（UTF-8）");
        }

        int success = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "文件内容为空");
            }
            header = stripBom(header);
            String line;
            int rowNum = 1;
            while ((line = reader.readLine()) != null) {
                rowNum++;
                if (!StringUtils.hasText(line.trim())) {
                    continue;
                }
                try {
                    String[] cols = splitCsv(line);
                    if (cols.length < 7) {
                        throw new BusinessException(ApiErrorCode.BAD_REQUEST,
                            "列不足，需要: productCode,productName,categoryId,unit,purchasePrice,salePrice,skuCode[,price,barcode,specValues]");
                    }
                    ProductCreateRequest request = new ProductCreateRequest();
                    request.setProductCode(cols[0].trim());
                    request.setProductName(cols[1].trim());
                    request.setCategoryId(Long.parseLong(cols[2].trim()));
                    request.setUnit(cols[3].trim());
                    request.setPurchasePrice(new BigDecimal(cols[4].trim()));
                    request.setSalePrice(new BigDecimal(cols[5].trim()));
                    if (cols.length > 9 && StringUtils.hasText(cols[9])) {
                        request.setDescription(cols[9].trim());
                    }
                    SkuCreateRequest sku = new SkuCreateRequest();
                    sku.setSkuCode(cols[6].trim());
                    sku.setPrice(cols.length > 7 && StringUtils.hasText(cols[7])
                        ? new BigDecimal(cols[7].trim()) : request.getSalePrice());
                    if (cols.length > 8 && StringUtils.hasText(cols[8])) {
                        sku.setBarcode(cols[8].trim());
                    }
                    if (cols.length > 10 && StringUtils.hasText(cols[10])) {
                        try {
                            sku.setSpecValues(objectMapper.readValue(cols[10].trim(),
                                new TypeReference<Map<String, Object>>() {}));
                        } catch (Exception ignore) {
                            sku.setSpecValues(Map.of("raw", cols[10].trim()));
                        }
                    }
                    request.setSkuList(List.of(sku));
                    createProduct(request);
                    success++;
                } catch (Exception ex) {
                    failed++;
                    errors.add("第" + rowNum + "行: " + ex.getMessage());
                }
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "文件解析失败: " + ex.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("successCount", success);
        result.put("failedCount", failed);
        result.put("errors", errors);
        return result;
    }

    private String stripBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private String[] splitCsv(String line) {
        List<String> cols = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if ((c == ',' || c == '\t') && !inQuote) {
                cols.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cols.add(current.toString());
        return cols.toArray(String[]::new);
    }

    // ==================== 管理端 SKU（写） ====================

    @Override
    public ProductSkuResponse addSku(Long productId, SkuCreateRequest request) {
        requireProduct(productId);
        Long skuId = insertSku(productId, request);
        return toSkuResponse(skuMapper.selectById(skuId));
    }

    @Override
    public ProductSkuResponse updateSku(Long skuId, SkuUpdateRequest request) {
        ProductSku sku = requireSku(skuId);
        if (request.getSkuCode() != null) {
            if (existsSkuCode(request.getSkuCode(), skuId)) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "SKU 编码已存在");
            }
            sku.setSkuCode(request.getSkuCode());
        }
        if (request.getSpecValues() != null) {
            sku.setSpecValues(writeSpecValues(request.getSpecValues()));
        }
        if (request.getPrice() != null) {
            sku.setPrice(request.getPrice());
        }
        if (request.getBarcode() != null) {
            sku.setBarcode(request.getBarcode());
        }
        skuMapper.updateById(sku);
        return toSkuResponse(skuMapper.selectById(skuId));
    }

    @Override
    public ProductSkuResponse updateSkuStatus(Long skuId, Integer status) {
        ProductSku sku = requireSku(skuId);
        sku.setStatus(status);
        skuMapper.updateById(sku);
        return toSkuResponse(skuMapper.selectById(skuId));
    }

    @Override
    public void deleteSku(Long skuId) {
        requireSku(skuId);
        skuMapper.deleteById(skuId);
    }

    // ==================== 私有辅助 ====================

    private Long insertSku(Long productId, SkuCreateRequest request) {
        if (existsSkuCode(request.getSkuCode(), null)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "SKU 编码已存在：" + request.getSkuCode());
        }
        ProductSku sku = new ProductSku();
        sku.setProductId(productId);
        sku.setSkuCode(request.getSkuCode());
        sku.setSpecValues(writeSpecValues(request.getSpecValues()));
        sku.setPrice(request.getPrice());
        sku.setBarcode(request.getBarcode());
        sku.setStatus(0);
        skuMapper.insert(sku);
        return sku.getId();
    }

    private boolean existsProductCode(String productCode, Long excludeId) {
        return productMapper.selectCount(new LambdaQueryWrapper<Product>()
            .eq(Product::getProductCode, productCode)
            .ne(excludeId != null, Product::getId, excludeId)) > 0;
    }

    private boolean existsSkuCode(String skuCode, Long excludeId) {
        return skuMapper.selectCount(new LambdaQueryWrapper<ProductSku>()
            .eq(ProductSku::getSkuCode, skuCode)
            .ne(excludeId != null, ProductSku::getId, excludeId)) > 0;
    }

    private String writeSpecValues(Map<String, Object> specValues) {
        if (specValues == null || specValues.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(specValues);
        } catch (Exception ex) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "规格参数格式错误");
        }
    }

    private Product requireProduct(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "商品不存在");
        }
        return product;
    }

    private ProductCategory requireCategory(Long categoryId) {
        ProductCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "分类不存在");
        }
        return category;
    }

    private ProductSku requireSku(Long skuId) {
        ProductSku sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "SKU 不存在");
        }
        return sku;
    }

    private CategoryResponse toCategoryResponse(ProductCategory category) {
        CategoryResponse response = new CategoryResponse();
        response.setCategoryId(category.getId());
        response.setCategoryName(category.getCategoryName());
        response.setParentId(category.getParentId());
        response.setIcon(category.getIcon());
        response.setSortOrder(category.getSortOrder());
        response.setStatus(category.getStatus());
        response.setCreateTime(category.getCreateTime());
        response.setUpdateTime(category.getUpdateTime());
        return response;
    }

    private ProductSummaryResponse toProductSummary(Product product,
                                                    Map<Long, String> categoryNames,
                                                    Map<Long, Integer> productStock,
                                                    boolean includeDescription) {
        ProductSummaryResponse response = new ProductSummaryResponse();
        response.setProductId(product.getId());
        response.setProductCode(product.getProductCode());
        response.setProductName(product.getProductName());
        response.setCategoryId(product.getCategoryId());
        response.setCategoryName(categoryNames.get(product.getCategoryId()));
        response.setMainImage(product.getMainImage());
        response.setUnit(product.getUnit());
        response.setWeight(product.getWeight());
        response.setPurchasePrice(product.getPurchasePrice());
        response.setSalePrice(product.getSalePrice());
        response.setStockQuantity(productStock.getOrDefault(product.getId(), 0));
        response.setStatus(product.getStatus());
        response.setCreateTime(product.getCreateTime());
        if (includeDescription) {
            response.setDescription(product.getDescription());
        }
        return response;
    }

    private ProductSkuResponse toSkuResponse(ProductSku sku) {
        ProductSkuResponse response = new ProductSkuResponse();
        response.setSkuId(sku.getId());
        response.setSkuCode(sku.getSkuCode());
        response.setSpecValues(parseSpecValues(sku.getSpecValues()));
        response.setPrice(sku.getPrice());
        response.setBarcode(sku.getBarcode());
        response.setStockQuantity(stockMapper.selectList(new LambdaQueryWrapper<Stock>()
                .eq(Stock::getSkuId, sku.getId()))
            .stream()
            .mapToInt(stock -> value(stock.getQuantity()))
            .sum());
        return response;
    }

    private List<ProductSku> skusByProduct(Long productId) {
        return skuMapper.selectList(new LambdaQueryWrapper<ProductSku>()
            .eq(ProductSku::getProductId, productId)
            .orderByAsc(ProductSku::getId));
    }

    private Map<Long, String> categoryNameMap() {
        return categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>())
            .stream()
            .collect(Collectors.toMap(ProductCategory::getId, ProductCategory::getCategoryName));
    }

    private Map<Long, Integer> productStockMap() {
        Map<Long, Long> skuProduct = skuMapper.selectList(new LambdaQueryWrapper<ProductSku>())
            .stream()
            .collect(Collectors.toMap(ProductSku::getId, ProductSku::getProductId));
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (Stock stock : stockMapper.selectList(new LambdaQueryWrapper<Stock>())) {
            Long productId = skuProduct.get(stock.getSkuId());
            if (productId != null) {
                result.merge(productId, value(stock.getQuantity()), Integer::sum);
            }
        }
        return result;
    }

    private Map<String, Object> parseSpecValues(String specValues) {
        if (!StringUtils.hasText(specValues)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(specValues, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of("raw", specValues);
        }
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }
}
