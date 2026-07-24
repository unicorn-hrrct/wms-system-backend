package com.example.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sto_location")
public class StockLocation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long warehouseId;

    private Long parentId;

    private Integer locationType;

    private String locationCode;

    private String locationName;

    private Integer sortOrder;

    private Integer status;

    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
