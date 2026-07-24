package com.example.demo.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class StockSummaryResponse {

    private Long skuId;
    private String skuCode;
    private Long productId;
    private String productName;
    private Map<String, Object> specValues;
    private String barcode;
    private String unit;
    private Integer totalStock;
    private Integer availableStock;
    private Integer lockedStock;
    private List<WarehouseStockResponse> warehouses = new ArrayList<>();
    private LocalDateTime lastInboundTime;
    private LocalDateTime lastOutboundTime;
}
