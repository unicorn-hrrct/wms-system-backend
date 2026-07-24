package com.example.demo.vo;

import lombok.Data;

@Data
public class WarehouseStockResponse {

    private Long warehouseId;
    private String warehouseName;
    private Integer quantity;
}
