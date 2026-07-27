package com.example.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_menu")
public class Menu {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;

    private String menuName;

    private String path;

    private String icon;

    private String permission;

    private String menuType;

    private Integer menuSort;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
