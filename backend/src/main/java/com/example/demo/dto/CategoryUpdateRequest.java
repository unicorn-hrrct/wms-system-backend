package com.example.demo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public class CategoryUpdateRequest {

    @Size(max = 100, message = "长度不能超过 100")
    private String categoryName;

    @Min(value = 0, message = "不能小于 0")
    private Long parentId;

    @Size(max = 50, message = "长度不能超过 50")
    private String icon;

    @Min(value = 0, message = "不能小于 0")
    private Integer sortOrder;

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
