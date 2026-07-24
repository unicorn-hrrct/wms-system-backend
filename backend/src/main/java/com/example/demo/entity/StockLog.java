package com.example.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sto_stock_log")
public class StockLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long skuId;

    private Long warehouseId;

    private Long locationId;

    private Integer type;

    private Integer quantityChange;

    private Integer beforeQty;

    private Integer afterQty;

    private String sourceNo;

    private String requestId;

    private String operator;

    private LocalDateTime operateTime;

    private String remark;
}