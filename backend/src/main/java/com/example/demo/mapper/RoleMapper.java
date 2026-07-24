package com.example.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.demo.entity.Role;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RoleMapper extends BaseMapper<Role> {

    @Select("""
        SELECT r.id, r.role_name, r.role_key, r.role_sort, r.status, r.create_time, r.update_time
        FROM sys_role r
        INNER JOIN sys_user_role ur ON ur.role_id = r.id
        WHERE ur.user_id = #{userId}
          AND r.status = 0
        ORDER BY r.role_sort, r.id
        """)
    List<Role> selectByUserId(Long userId);

    @Select("""
        SELECT id, role_name, role_key, role_sort, status, create_time, update_time
        FROM sys_role
        WHERE role_key = #{roleKey}
          AND status = 0
        """)
    Role selectByRoleKey(String roleKey);

    @Select("""
        SELECT id, role_name, role_key, role_sort, status, create_time, update_time
        FROM sys_role
        WHERE status = 0
        ORDER BY role_sort, id
        """)
    List<Role> selectActiveRoles();
}
