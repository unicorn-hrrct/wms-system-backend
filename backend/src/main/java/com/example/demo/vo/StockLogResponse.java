package com.example.demo.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class StockLogResponse {

    private Long logId;
    private Long skuId;
    private String skuCode;
    private Integer type;
    private String typeName;
    private Integer quantityChange;
    private Integer beforeQty;
    private Integer afterQty;
    private String sourceNo;
    private String operator;
    private LocalDateTime operateTime;
    private String remark;
}
