package com.example.demo.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

public class SkuUpdateRequest {

    @Size(max = 80, message = "长度不能超过 80")
    private String skuCode;

    private Map<String, Object> specValues;

    @DecimalMin(value = "0.0", message = "不能小于 0")
    private BigDecimal price;

    @Size(max = 80, message = "长度不能超过 80")
    private String barcode;

    public String getSkuCode() {
        return skuCode;
    }

    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    public Map<String, Object> getSpecValues() {
        return specValues;
    }

    public void setSpecValues(Map<String, Object> specValues) {
        this.specValues = specValues;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }
}
