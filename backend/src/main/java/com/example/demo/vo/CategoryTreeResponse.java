package com.example.demo.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CategoryTreeResponse {

    private Long categoryId;
    private String categoryName;
    private Long parentId;
    private Integer sortOrder;
    private List<CategoryTreeResponse> children = new ArrayList<>();
}
