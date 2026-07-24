package com.example.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.demo.entity.Menu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MenuMapper extends BaseMapper<Menu> {

    @Select("""
        SELECT DISTINCT m.id, m.parent_id, m.menu_name, m.path, m.icon, m.permission,
               m.menu_type, m.menu_sort, m.status, m.create_time, m.update_time
        FROM sys_menu m
        INNER JOIN sys_role_menu rm ON rm.menu_id = m.id
        INNER JOIN sys_user_role ur ON ur.role_id = rm.role_id
        WHERE ur.user_id = #{userId}
          AND m.status = 0
        ORDER BY m.parent_id, m.menu_sort, m.id
        """)
    List<Menu> selectByUserId(Long userId);

    @Select("""
        SELECT DISTINCT m.permission
        FROM sys_menu m
        INNER JOIN sys_role_menu rm ON rm.menu_id = m.id
        INNER JOIN sys_user_role ur ON ur.role_id = rm.role_id
        WHERE ur.user_id = #{userId}
          AND m.status = 0
          AND m.permission IS NOT NULL
          AND m.permission <> ''
        ORDER BY m.permission
        """)
    List<String> selectPermissionsByUserId(Long userId);
}
