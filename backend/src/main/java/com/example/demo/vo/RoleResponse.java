package com.example.demo.vo;

import com.example.demo.entity.Role;

public class RoleResponse {

    private Long roleId;
    private String roleName;
    private String roleKey;
    private Integer status;

    public static RoleResponse from(Role role) {
        RoleResponse response = new RoleResponse();
        response.setRoleId(role.getId());
        response.setRoleName(role.getRoleName());
        response.setRoleKey(role.getRoleKey());
        response.setStatus(role.getStatus());
        return response;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public String getRoleKey() {
        return roleKey;
    }

    public void setRoleKey(String roleKey) {
        this.roleKey = roleKey;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
