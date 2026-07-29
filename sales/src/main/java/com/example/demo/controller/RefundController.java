package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.dto.RefundAuditRequest;
import com.example.demo.dto.RefundCompleteRequest;
import com.example.demo.json.RefundTimeJsonCodec;
import com.example.demo.service.RefundService;
import com.example.demo.vo.RefundApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;

@RestController
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /** 商家侧退款列表（v1.2 §7.11.1）。 */
    @GetMapping("/api/v1/web/refund")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<Map<String, Object>> merchantList(
        @RequestParam(required = false) @Min(0) @Max(5) Integer status,
        @RequestParam(required = false) String refundNo,
        @RequestParam(required = false) String orderNo,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startDate,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endDate,
        @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
        @RequestParam(defaultValue = "10") @Min(1) Integer pageSize) {
        return Result.success("查询成功", refundService.merchantList(
            status, refundNo, orderNo,
            toStorageDateTime(startDate), toStorageDateTime(endDate),
            pageNum, pageSize));
    }

    /** 退款详情（v1.2 §7.11.2）。 */
    @GetMapping("/api/v1/web/refund/{refundId}")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<RefundApiResponse> detail(
        @PathVariable @Min(1) Long refundId) {
        return Result.success(
            "查询成功", RefundApiResponse.from(refundService.getDetail(refundId)));
    }

    /** 我的退款列表（v1.2 §7.11.3）。 */
    @GetMapping("/api/v1/refund/my")
    public Result<Map<String, Object>> myList(
        @RequestParam(required = false) @Min(0) @Max(5) Integer status,
        @RequestParam(required = false) String refundNo,
        @RequestParam(required = false) String orderNo,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startDate,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endDate,
        @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
        @RequestParam(defaultValue = "10") @Min(1) Integer pageSize) {
        return Result.success("查询成功", refundService.myList(
            status, refundNo, orderNo,
            toStorageDateTime(startDate), toStorageDateTime(endDate),
            pageNum, pageSize));
    }

    /** 商家审核（v1.2 §7.11.4）。 */
    @PutMapping("/api/v1/web/refund/{refundId}/audit")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<RefundApiResponse> audit(
        @PathVariable @Min(1) Long refundId,
        @Valid @RequestBody RefundAuditRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Result.success(
            "审核完成",
            RefundApiResponse.from(
                refundService.audit(refundId, request, idempotencyKey)));
    }

    /** 执行退款（v1.2 §7.11.5）。 */
    @PutMapping("/api/v1/web/refund/{refundId}/complete")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<RefundApiResponse> complete(
        @PathVariable @Min(1) Long refundId,
        @Valid @RequestBody RefundCompleteRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Result.success(
            "退款完成",
            RefundApiResponse.from(
                refundService.complete(refundId, request, idempotencyKey)));
    }

    /** 用户取消退款（v1.2 §7.11.6）。 */
    @PutMapping("/api/v1/refund/{refundId}/cancel")
    public Result<Void> cancel(
        @PathVariable @Min(1) Long refundId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        refundService.cancel(refundId, idempotencyKey);
        return Result.success("退款已取消", null);
    }

    private LocalDateTime toStorageDateTime(OffsetDateTime value) {
        return RefundTimeJsonCodec.toStorageDateTime(value);
    }
}
