package com.example.demo.vo;

import lombok.Data;

@Data
public class ProductCreateResponse {

    private Long productId;
    private String productCode;
    private Integer skuCount;
}
