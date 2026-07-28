package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.dto.RefundAuditRequest;
import com.example.demo.dto.RefundCompleteRequest;
import com.example.demo.service.RefundService;
import com.example.demo.vo.RefundResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /** 商家侧退款列表（v1.2 §7.11.1） */
    @GetMapping("/api/v1/web/refund")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<Map<String, Object>> merchantList(@RequestParam(required = false) Integer status,
                                                    @RequestParam(required = false) String refundNo,
                                                    @RequestParam(required = false) String orderNo,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
                                                    @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
                                                    @RequestParam(defaultValue = "10") @Min(1) Integer pageSize) {
        return Result.success("查询成功", refundService.merchantList(status, refundNo, orderNo,
            startDate, endDate, pageNum, pageSize));
    }

    /** 退款详情 */
    @GetMapping("/api/v1/web/refund/{refundId}")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<RefundResponse> detail(@PathVariable @Min(1) Long refundId) {
        return Result.success("查询成功", refundService.getDetail(refundId));
    }

    /** 我的退款列表（v1.2 §7.11.3） */
    @GetMapping("/api/v1/refund/my")
    public Result<Map<String, Object>> myList(@RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) String refundNo,
                                              @RequestParam(required = false) String orderNo,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
                                              @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
                                              @RequestParam(defaultValue = "10") @Min(1) Integer pageSize) {
        return Result.success("查询成功", refundService.myList(status, refundNo, orderNo,
            startDate, endDate, pageNum, pageSize));
    }

    /** 商家审核（v1.2 §7.11.4） */
    @PutMapping("/api/v1/web/refund/{refundId}/audit")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<RefundResponse> audit(@PathVariable @Min(1) Long refundId,
                                        @Valid @RequestBody RefundAuditRequest request) {
        return Result.success("审核完成", refundService.audit(refundId, request));
    }

    /** 执行退款（v1.2 §7.11.5） */
    @PutMapping("/api/v1/web/refund/{refundId}/complete")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<RefundResponse> complete(@PathVariable @Min(1) Long refundId,
                                          @Valid @RequestBody RefundCompleteRequest request) {
        return Result.success("退款完成", refundService.complete(refundId, request));
    }

    /** 用户取消退款（v1.2 §7.11.6） */
    @PutMapping("/api/v1/refund/{refundId}/cancel")
    public Result<Void> cancel(@PathVariable @Min(1) Long refundId) {
        refundService.cancel(refundId);
        return Result.success("退款已取消", null);
    }
}
