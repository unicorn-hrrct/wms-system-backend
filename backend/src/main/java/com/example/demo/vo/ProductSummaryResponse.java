package com.example.demo.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class ProductSummaryResponse {

    private Long productId;
    private String productCode;
    private String productName;
    private Long categoryId;
    private String categoryName;
    private String mainImage;
    private String unit;
    private BigDecimal weight;
    private BigDecimal purchasePrice;
    private BigDecimal salePrice;
    private Integer stockQuantity;
    private Integer status;
    private LocalDateTime createTime;
    private String description;
    private List<ProductSkuResponse> skuList = new ArrayList<>();
}
