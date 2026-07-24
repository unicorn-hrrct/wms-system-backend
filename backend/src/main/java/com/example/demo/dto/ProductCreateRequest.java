package com.example.demo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public class ProductCreateRequest {

    @NotBlank(message = "不能为空")
    @Size(max = 50, message = "长度不能超过 50")
    private String productCode;

    @NotBlank(message = "不能为空")
    @Size(max = 150, message = "长度不能超过 150")
    private String productName;

    @NotNull(message = "不能为空")
    private Long categoryId;

    @Size(max = 255, message = "长度不能超过 255")
    private String mainImage;

    @NotBlank(message = "不能为空")
    @Size(max = 20, message = "长度不能超过 20")
    private String unit;

    @DecimalMin(value = "0.0", message = "不能小于 0")
    private BigDecimal weight;

    @NotNull(message = "不能为空")
    @DecimalMin(value = "0.0", message = "不能小于 0")
    private BigDecimal purchasePrice;

    @NotNull(message = "不能为空")
    @DecimalMin(value = "0.0", message = "不能小于 0")
    private BigDecimal salePrice;

    private String description;

    @Valid
    private List<SkuCreateRequest> skuList;

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getMainImage() {
        return mainImage;
    }

    public void setMainImage(String mainImage) {
        this.mainImage = mainImage;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public void setWeight(BigDecimal weight) {
        this.weight = weight;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public void setPurchasePrice(BigDecimal purchasePrice) {
        this.purchasePrice = purchasePrice;
    }

    public BigDecimal getSalePrice() {
        return salePrice;
    }

    public void setSalePrice(BigDecimal salePrice) {
        this.salePrice = salePrice;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<SkuCreateRequest> getSkuList() {
        return skuList;
    }

    public void setSkuList(List<SkuCreateRequest> skuList) {
        this.skuList = skuList;
    }
}
