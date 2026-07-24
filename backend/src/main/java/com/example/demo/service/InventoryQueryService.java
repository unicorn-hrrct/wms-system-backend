package com.example.demo.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.demo.vo.LocationTreeResponse;
import com.example.demo.vo.StockLogResponse;
import com.example.demo.vo.StockSummaryResponse;
import com.example.demo.vo.WarehouseResponse;

import java.time.LocalDate;
import java.util.List;

public interface InventoryQueryService {

    List<WarehouseResponse> listWarehouses();

    List<LocationTreeResponse> locationTree(Long warehouseId);

    IPage<StockSummaryResponse> queryStock(Long skuId, Long productId, Long warehouseId, Long locationId, String keyword, int pageNum, int pageSize);

    IPage<StockLogResponse> listStockLogs(Long skuId, Integer type, LocalDate startDate, LocalDate endDate, int pageNum, int pageSize);
}
