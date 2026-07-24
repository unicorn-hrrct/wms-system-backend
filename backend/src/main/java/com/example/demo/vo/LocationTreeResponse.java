package com.example.demo.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class LocationTreeResponse {

    private Long areaId;
    private String areaName;
    private Long warehouseId;
    private List<ShelfResponse> shelves = new ArrayList<>();

    @Data
    public static class ShelfResponse {
        private Long shelfId;
        private String shelfName;
        private List<PositionResponse> positions = new ArrayList<>();
    }

    @Data
    public static class PositionResponse {
        private Long positionId;
        private String positionName;
        private Long skuId;
        private Integer quantity;
    }
}
