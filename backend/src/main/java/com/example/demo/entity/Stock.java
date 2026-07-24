package com.example.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("sto_stock")
public class Stock {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long skuId;

    private Long warehouseId;

    private Long locationId;

    private Integer quantity;

    private Integer lockedQuantity;

    private Integer minStock;

    private Integer maxStock;

    private String batchNo;

    private LocalDate productionDate;

    private LocalDate expireDate;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}