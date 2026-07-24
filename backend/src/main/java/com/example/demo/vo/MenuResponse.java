package com.example.demo.vo;

import com.example.demo.entity.Menu;

import java.util.ArrayList;
import java.util.List;

public class MenuResponse {

    private Long menuId;
    private Long parentId;
    private String menuName;
    private String path;
    private String icon;
    private String permission;
    private List<MenuResponse> children = new ArrayList<>();

    public static MenuResponse from(Menu menu) {
        MenuResponse response = new MenuResponse();
        response.setMenuId(menu.getId());
        response.setParentId(menu.getParentId());
        response.setMenuName(menu.getMenuName());
        response.setPath(menu.getPath());
        response.setIcon(menu.getIcon());
        response.setPermission(menu.getPermission());
        return response;
    }

    public Long getMenuId() {
        return menuId;
    }

    public void setMenuId(Long menuId) {
        this.menuId = menuId;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getMenuName() {
        return menuName;
    }

    public void setMenuName(String menuName) {
        this.menuName = menuName;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public List<MenuResponse> getChildren() {
        return children;
    }

    public void setChildren(List<MenuResponse> children) {
        this.children = children;
    }
}
