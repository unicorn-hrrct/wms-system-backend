package com.example.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sto_warehouse")
public class Warehouse {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String warehouseCode;

    private String warehouseName;

    private Integer type;

    private String address;

    private String manager;

    private Integer capacity;

    private Integer usedCapacity;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
