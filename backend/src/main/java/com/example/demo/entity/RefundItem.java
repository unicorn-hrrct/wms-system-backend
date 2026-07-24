package com.example.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ref_refund_item")
public class RefundItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long refundId;

    private Long skuId;

    private Long warehouseId;

    private Long locationId;

    private Integer quantity;
}