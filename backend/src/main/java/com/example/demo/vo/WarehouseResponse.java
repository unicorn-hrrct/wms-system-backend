package com.example.demo.vo;

import com.example.demo.entity.Warehouse;
import lombok.Data;

@Data
public class WarehouseResponse {

    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private Integer type;
    private String typeName;
    private String address;
    private String manager;
    private Integer capacity;
    private Integer usedCapacity;
    private Integer status;

    public static WarehouseResponse from(Warehouse warehouse) {
        WarehouseResponse response = new WarehouseResponse();
        response.setWarehouseId(warehouse.getId());
        response.setWarehouseCode(warehouse.getWarehouseCode());
        response.setWarehouseName(warehouse.getWarehouseName());
        response.setType(warehouse.getType());
        response.setTypeName(typeName(warehouse.getType()));
        response.setAddress(warehouse.getAddress());
        response.setManager(warehouse.getManager());
        response.setCapacity(warehouse.getCapacity());
        response.setUsedCapacity(warehouse.getUsedCapacity());
        response.setStatus(warehouse.getStatus());
        return response;
    }

    private static String typeName(Integer type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case 1 -> "主仓库";
            case 2 -> "分仓库";
            case 3 -> "退货仓";
            default -> "其他";
        };
    }
}
