package com.example.demo.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CategoryResponse {

    private Long categoryId;
    private String categoryName;
    private Long parentId;
    private String icon;
    private Integer sortOrder;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
