package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import com.example.demo.security.CurrentUserProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 10.4 扫码出入库：根据条码解析 SKU / 扫码入库 / 扫码出库。
 */
@Service
public class BarcodeService {

    private final JdbcTemplate jdbcTemplate;
    private final StockMutationService stockMutationService;
    private final CurrentUserProvider currentUser;

    public BarcodeService(JdbcTemplate jdbcTemplate,
                          StockMutationService stockMutationService,
                          CurrentUserProvider currentUser) {
        this.jdbcTemplate = jdbcTemplate;
        this.stockMutationService = stockMutationService;
        this.currentUser = currentUser;
    }

    /**
     * 10.4.1 条码解析
     */
    public Map<String, Object> parse(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "条码不能为空");
        }
        List<Map<String, Object>> rows = jdbcTemplate.query("""
            SELECT k.id sku_id, k.sku_code, p.product_name, k.spec_values, p.unit,
                   k.price, p.main_image, COALESCE(SUM(s.quantity-s.locked_quantity),0)::int current_stock
            FROM pro_sku k JOIN pro_product p ON p.id=k.product_id
            LEFT JOIN sto_stock s ON s.sku_id=k.id AND s.deleted=0
            WHERE k.barcode = ? AND k.deleted=0 AND p.deleted=0 AND k.status=0
            GROUP BY k.id, p.id
            """, this::mapRow, barcode);
        if (rows.isEmpty()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "条码未匹配到 SKU");
        }
        Map<String, Object> data = rows.getFirst();
        data.put("barcode", barcode);
        return data;
    }

    /**
     * 10.4.2 扫码入库
     */
    public Map<String, Object> scanInbound(List<String> barcodes, List<Integer> quantities,
                                           Long warehouseId, Long locationId,
                                           String sourceType, String sourceNo,
                                           String batchNo, java.time.LocalDate productionDate,
                                           java.time.LocalDate expireDate) {
        validate(barcodes, quantities, warehouseId, locationId, sourceNo);
        if (sourceType == null || (!sourceType.equals("PURCHASE") && !sourceType.equals("RETURN")
            && !sourceType.equals("TRANSFER"))) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "sourceType 仅支持 PURCHASE/RETURN/TRANSFER");
        }
        String remark = "扫码入库-" + sourceType + ":" + sourceNo;
        int totalQty = 0;
        for (int i = 0; i < barcodes.size(); i++) {
            Map<String, Object> sku = parse(barcodes.get(i));
            int qty = quantities.get(i);
            stockMutationService.adjust(
                ((Number) sku.get("skuId")).longValue(), warehouseId, locationId, qty, 0, 1,
                sourceNo, "scan-in:" + sourceNo + ":" + i,
                currentUser.requireUsername(), batchNo, productionDate, expireDate, remark);
            totalQty += qty;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sourceType", sourceType);
        response.put("sourceNo", sourceNo);
        response.put("totalQuantity", totalQty);
        response.put("itemCount", barcodes.size());
        return response;
    }

    /**
     * 10.4.3 扫码出库
     */
    public Map<String, Object> scanOutbound(List<String> barcodes, List<Integer> quantities,
                                            Long warehouseId, Long locationId,
                                            String destType, String destNo) {
        validate(barcodes, quantities, warehouseId, locationId, destNo);
        if (destType == null || (!destType.equals("SALE") && !destType.equals("RETURN")
            && !destType.equals("TRANSFER"))) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "destType 仅支持 SALE/RETURN/TRANSFER");
        }
        String remark = "扫码出库-" + destType + ":" + destNo;
        int totalQty = 0;
        for (int i = 0; i < barcodes.size(); i++) {
            Map<String, Object> sku = parse(barcodes.get(i));
            int qty = quantities.get(i);
            stockMutationService.adjust(
                ((Number) sku.get("skuId")).longValue(), warehouseId, locationId, -qty, 0, 2,
                destNo, "scan-out:" + destNo + ":" + i,
                currentUser.requireUsername(), null, remark);
            totalQty += qty;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("destType", destType);
        response.put("destNo", destNo);
        response.put("totalQuantity", totalQty);
        response.put("itemCount", barcodes.size());
        return response;
    }

    private void validate(List<String> barcodes, List<Integer> quantities,
                          Long warehouseId, Long locationId, String sourceNo) {
        if (barcodes == null || quantities == null || barcodes.isEmpty()) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "条形码与对应数量必填");
        }
        if (barcodes.size() != quantities.size()) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "条形码与数量数组长度不一致");
        }
        if (warehouseId == null || locationId == null) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "warehouseId / locationId 必填");
        }
        if (sourceNo == null || sourceNo.isBlank()) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "来源单号必填");
        }
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sto_location WHERE id=? AND warehouse_id=? AND status=0 AND deleted=0",
            Integer.class, locationId, warehouseId);
        if (count == null || count == 0) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "库位不属于指定仓库");
        }
    }

    private Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("skuId", rs.getLong("sku_id"));
        row.put("skuCode", rs.getString("sku_code"));
        row.put("productName", rs.getString("product_name"));
        row.put("specValues", rs.getString("spec_values"));
        row.put("unit", rs.getString("unit"));
        row.put("price", rs.getBigDecimal("price"));
        row.put("mainImage", rs.getString("main_image"));
        row.put("currentStock", rs.getInt("current_stock"));
        return row;
    }
}