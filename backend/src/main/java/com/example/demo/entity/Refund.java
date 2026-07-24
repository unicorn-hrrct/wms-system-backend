package com.example.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ref_refund")
public class Refund {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String refundNo;

    private Long orderId;

    private Long orderItemId;

    private Long customerId;

    private Integer type;

    private Integer status;

    private BigDecimal applyRefundAmount;

    private Integer applyRefundQuantity;

    private BigDecimal approvedAmount;

    private Integer approvedQuantity;

    private BigDecimal actualRefundAmount;

    private String providerRefundNo;

    private Boolean restock;

    private String reason;

    private String images;

    private String auditRemark;

    private LocalDateTime appliedAt;

    private LocalDateTime auditedAt;

    private LocalDateTime completedAt;

    private String idempotencyKey;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}