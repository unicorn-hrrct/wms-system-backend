package com.example.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sto_stock_reservation")
public class StockReservation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String reservationId;

    private String requestId;

    private String orderNo;

    private Long skuId;

    private Long warehouseId;

    private Long locationId;

    private Integer quantity;

    private Short status;

    private LocalDateTime expiresAt;

    private String payloadHash;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}