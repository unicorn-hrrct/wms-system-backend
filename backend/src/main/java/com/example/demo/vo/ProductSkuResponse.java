package com.example.demo.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
public class ProductSkuResponse {

    private Long skuId;
    private String skuCode;
    private Map<String, Object> specValues;
    private BigDecimal price;
    private String barcode;
    private Integer stockQuantity;
}
