package com.example.demo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.entity.Product;
import com.example.demo.entity.ProductSku;
import com.example.demo.entity.Stock;
import com.example.demo.entity.StockLocation;
import com.example.demo.entity.StockLog;
import com.example.demo.entity.Warehouse;
import com.example.demo.mapper.ProductMapper;
import com.example.demo.mapper.ProductSkuMapper;
import com.example.demo.mapper.StockLocationMapper;
import com.example.demo.mapper.StockLogMapper;
import com.example.demo.mapper.StockMapper;
import com.example.demo.mapper.WarehouseMapper;
import com.example.demo.service.InventoryQueryService;
import com.example.demo.vo.LocationTreeResponse;
import com.example.demo.vo.StockLogResponse;
import com.example.demo.vo.StockSummaryResponse;
import com.example.demo.vo.WarehouseResponse;
import com.example.demo.vo.WarehouseStockResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class InventoryQueryServiceImpl implements InventoryQueryService {

    private final WarehouseMapper warehouseMapper;
    private final StockLocationMapper locationMapper;
    private final StockMapper stockMapper;
    private final StockLogMapper stockLogMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductMapper productMapper;
    private final ObjectMapper objectMapper;

    public InventoryQueryServiceImpl(WarehouseMapper warehouseMapper,
                                     StockLocationMapper locationMapper,
                                     StockMapper stockMapper,
                                     StockLogMapper stockLogMapper,
                                     ProductSkuMapper skuMapper,
                                     ProductMapper productMapper,
                                     ObjectMapper objectMapper) {
        this.warehouseMapper = warehouseMapper;
        this.locationMapper = locationMapper;
        this.stockMapper = stockMapper;
        this.stockLogMapper = stockLogMapper;
        this.skuMapper = skuMapper;
        this.productMapper = productMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<WarehouseResponse> listWarehouses() {
        return warehouseMapper.selectList(new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getStatus, 0)
                .orderByAsc(Warehouse::getId))
            .stream()
            .map(WarehouseResponse::from)
            .toList();
    }

    @Override
    public List<LocationTreeResponse> locationTree(Long warehouseId) {
        List<StockLocation> locations = locationMapper.selectList(new LambdaQueryWrapper<StockLocation>()
            .eq(warehouseId != null, StockLocation::getWarehouseId, warehouseId)
            .eq(StockLocation::getStatus, 0)
            .and(w -> w.eq(StockLocation::getDeleted, 0).or().isNull(StockLocation::getDeleted))
            .orderByAsc(StockLocation::getWarehouseId)
            .orderByAsc(StockLocation::getParentId)
            .orderByAsc(StockLocation::getSortOrder)
            .orderByAsc(StockLocation::getId));
        Map<Long, List<StockLocation>> childrenByParent = locations.stream()
            .collect(Collectors.groupingBy(StockLocation::getParentId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<Stock>> stockByLocation = stockMapper.selectList(new LambdaQueryWrapper<Stock>()
                .eq(warehouseId != null, Stock::getWarehouseId, warehouseId))
            .stream()
            .collect(Collectors.groupingBy(Stock::getLocationId));

        List<LocationTreeResponse> areas = new ArrayList<>();
        for (StockLocation area : childrenByParent.getOrDefault(0L, List.of())) {
            if (area.getLocationType() != 1) {
                continue;
            }
            LocationTreeResponse areaResponse = new LocationTreeResponse();
            areaResponse.setAreaId(area.getId());
            areaResponse.setAreaName(area.getLocationName());
            areaResponse.setWarehouseId(area.getWarehouseId());

            for (StockLocation shelf : childrenByParent.getOrDefault(area.getId(), List.of())) {
                LocationTreeResponse.ShelfResponse shelfResponse = new LocationTreeResponse.ShelfResponse();
                shelfResponse.setShelfId(shelf.getId());
                shelfResponse.setShelfName(shelf.getLocationName());

                for (StockLocation position : childrenByParent.getOrDefault(shelf.getId(), List.of())) {
                    LocationTreeResponse.PositionResponse positionResponse = new LocationTreeResponse.PositionResponse();
                    positionResponse.setPositionId(position.getId());
                    positionResponse.setPositionName(position.getLocationName());
                    List<Stock> positionStock = stockByLocation.getOrDefault(position.getId(), List.of());
                    positionResponse.setSkuId(positionStock.stream().findFirst().map(Stock::getSkuId).orElse(null));
                    positionResponse.setQuantity(positionStock.stream().mapToInt(stock -> value(stock.getQuantity())).sum());
                    shelfResponse.getPositions().add(positionResponse);
                }
                areaResponse.getShelves().add(shelfResponse);
            }
            areas.add(areaResponse);
        }
        return areas;
    }

    @Override
    public IPage<StockSummaryResponse> queryStock(Long skuId,
                                                  Long productId,
                                                  Long warehouseId,
                                                  Long locationId,
                                                  String keyword,
                                                  int pageNum,
                                                  int pageSize) {
        Map<Long, ProductSku> skus = skuMapper.selectList(new LambdaQueryWrapper<ProductSku>())
            .stream()
            .collect(Collectors.toMap(ProductSku::getId, Function.identity()));
        Map<Long, Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>())
            .stream()
            .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, Warehouse> warehouses = warehouseMapper.selectList(new LambdaQueryWrapper<Warehouse>())
            .stream()
            .collect(Collectors.toMap(Warehouse::getId, Function.identity()));

        List<Stock> filtered = stockMapper.selectList(new LambdaQueryWrapper<Stock>()
                .eq(skuId != null, Stock::getSkuId, skuId)
                .eq(warehouseId != null, Stock::getWarehouseId, warehouseId)
                .eq(locationId != null, Stock::getLocationId, locationId))
            .stream()
            .filter(stock -> matchesProduct(stock, skus, productId))
            .filter(stock -> matchesKeyword(stock, skus, products, keyword))
            .toList();

        List<StockSummaryResponse> summaries = filtered.stream()
            .collect(Collectors.groupingBy(Stock::getSkuId, LinkedHashMap::new, Collectors.toList()))
            .entrySet()
            .stream()
            .map(entry -> toStockSummary(entry.getKey(), entry.getValue(), skus, products, warehouses))
            .sorted(Comparator.comparing(StockSummaryResponse::getSkuId))
            .toList();

        return toPage(summaries, pageNum, pageSize);
    }

    @Override
    public IPage<StockLogResponse> listStockLogs(Long skuId, Integer type, LocalDate startDate, LocalDate endDate, int pageNum, int pageSize) {
        Map<Long, ProductSku> skus = skuMapper.selectList(new LambdaQueryWrapper<ProductSku>())
            .stream()
            .collect(Collectors.toMap(ProductSku::getId, Function.identity()));
        List<StockLogResponse> logs = stockLogMapper.selectList(new LambdaQueryWrapper<StockLog>()
                .eq(skuId != null, StockLog::getSkuId, skuId)
                .eq(type != null, StockLog::getType, type)
                .ge(startDate != null, StockLog::getOperateTime, startDate == null ? null : startDate.atStartOfDay())
                .lt(endDate != null, StockLog::getOperateTime, endDate == null ? null : endDate.plusDays(1).atStartOfDay())
                .orderByDesc(StockLog::getOperateTime)
                .orderByDesc(StockLog::getId))
            .stream()
            .map(log -> toStockLog(log, skus))
            .toList();
        return toPage(logs, pageNum, pageSize);
    }

    private StockSummaryResponse toStockSummary(Long skuId,
                                                List<Stock> stocks,
                                                Map<Long, ProductSku> skus,
                                                Map<Long, Product> products,
                                                Map<Long, Warehouse> warehouses) {
        ProductSku sku = skus.get(skuId);
        Product product = sku == null ? null : products.get(sku.getProductId());
        StockSummaryResponse response = new StockSummaryResponse();
        response.setSkuId(skuId);
        response.setSkuCode(sku == null ? null : sku.getSkuCode());
        response.setProductId(product == null ? null : product.getId());
        response.setProductName(product == null ? null : product.getProductName());
        response.setSpecValues(sku == null ? Map.of() : parseSpecValues(sku.getSpecValues()));
        response.setBarcode(sku == null ? null : sku.getBarcode());
        response.setUnit(product == null ? null : product.getUnit());
        response.setTotalStock(stocks.stream().mapToInt(stock -> value(stock.getQuantity())).sum());
        response.setLockedStock(stocks.stream().mapToInt(stock -> value(stock.getLockedQuantity())).sum());
        response.setAvailableStock(response.getTotalStock() - response.getLockedStock());
        response.setWarehouses(stocks.stream()
            .collect(Collectors.groupingBy(Stock::getWarehouseId, LinkedHashMap::new, Collectors.summingInt(stock -> value(stock.getQuantity()))))
            .entrySet()
            .stream()
            .map(entry -> {
                Warehouse warehouse = warehouses.get(entry.getKey());
                WarehouseStockResponse warehouseStock = new WarehouseStockResponse();
                warehouseStock.setWarehouseId(entry.getKey());
                warehouseStock.setWarehouseName(warehouse == null ? null : warehouse.getWarehouseName());
                warehouseStock.setQuantity(entry.getValue());
                return warehouseStock;
            })
            .toList());
        response.setLastInboundTime(lastOperateTime(skuId, 1));
        response.setLastOutboundTime(lastOperateTime(skuId, 2));
        return response;
    }

    private StockLogResponse toStockLog(StockLog log, Map<Long, ProductSku> skus) {
        ProductSku sku = skus.get(log.getSkuId());
        StockLogResponse response = new StockLogResponse();
        response.setLogId(log.getId());
        response.setSkuId(log.getSkuId());
        response.setSkuCode(sku == null ? null : sku.getSkuCode());
        response.setType(log.getType());
        response.setTypeName(typeName(log.getType()));
        response.setQuantityChange(log.getQuantityChange());
        response.setBeforeQty(log.getBeforeQty());
        response.setAfterQty(log.getAfterQty());
        response.setSourceNo(log.getSourceNo());
        response.setOperator(log.getOperator());
        response.setOperateTime(log.getOperateTime());
        response.setRemark(log.getRemark());
        return response;
    }

    private boolean matchesProduct(Stock stock, Map<Long, ProductSku> skus, Long productId) {
        if (productId == null) {
            return true;
        }
        ProductSku sku = skus.get(stock.getSkuId());
        return sku != null && productId.equals(sku.getProductId());
    }

    private boolean matchesKeyword(Stock stock, Map<Long, ProductSku> skus, Map<Long, Product> products, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        String value = keyword.toLowerCase();
        ProductSku sku = skus.get(stock.getSkuId());
        Product product = sku == null ? null : products.get(sku.getProductId());
        return contains(sku == null ? null : sku.getSkuCode(), value)
            || contains(sku == null ? null : sku.getBarcode(), value)
            || contains(product == null ? null : product.getProductName(), value)
            || contains(product == null ? null : product.getProductCode(), value);
    }

    private boolean contains(String source, String value) {
        return source != null && source.toLowerCase().contains(value);
    }

    private LocalDateTime lastOperateTime(Long skuId, Integer type) {
        return stockLogMapper.selectList(new LambdaQueryWrapper<StockLog>()
                .eq(StockLog::getSkuId, skuId)
                .eq(StockLog::getType, type)
                .orderByDesc(StockLog::getOperateTime)
                .last("LIMIT 1"))
            .stream()
            .findFirst()
            .map(StockLog::getOperateTime)
            .orElse(null);
    }

    private String typeName(Integer type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case 1 -> "采购入库";
            case 2 -> "销售出库";
            case 3 -> "盘点调整";
            case 4 -> "库存调拨";
            default -> "其他";
        };
    }

    private Map<String, Object> parseSpecValues(String specValues) {
        if (!StringUtils.hasText(specValues)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(specValues, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of("raw", specValues);
        }
    }

    private <T> IPage<T> toPage(List<T> records, int pageNum, int pageSize) {
        int fromIndex = Math.min(Math.max(pageNum - 1, 0) * pageSize, records.size());
        int toIndex = Math.min(fromIndex + pageSize, records.size());
        Page<T> page = new Page<>(pageNum, pageSize, records.size());
        page.setRecords(records.subList(fromIndex, toIndex));
        return page;
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }
}
