package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.dto.AftersaleApplyRequest;
import com.example.demo.dto.AftersaleAuditRequest;
import com.example.demo.service.AftersaleService;
import com.example.demo.vo.AftersaleResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
public class AftersaleController {

    private final AftersaleService aftersaleService;

    public AftersaleController(AftersaleService aftersaleService) {
        this.aftersaleService = aftersaleService;
    }

    @PostMapping("/api/v1/order/aftersale")
    public Result<AftersaleResponse> apply(@Valid @RequestBody AftersaleApplyRequest request,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Result.success("售后申请提交成功", aftersaleService.apply(request, idempotencyKey));
    }

    @GetMapping("/api/v1/web/aftersale")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<Map<String, Object>> merchantList(@RequestParam(required = false) Integer status,
                                                    @RequestParam(required = false) String aftersaleNo,
                                                    @RequestParam(required = false) String orderNo,
                                                    @RequestParam(required = false)
                                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                    LocalDateTime startDate,
                                                    @RequestParam(required = false)
                                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                    LocalDateTime endDate,
                                                    @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
                                                    @RequestParam(defaultValue = "10") @Min(1) Integer pageSize) {
        return Result.success("查询成功", aftersaleService.merchantList(status, aftersaleNo, orderNo,
            startDate, endDate, pageNum, pageSize));
    }

    @GetMapping("/api/v1/web/aftersale/{aftersaleId}")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<AftersaleResponse> detail(@PathVariable @Min(1) Long aftersaleId) {
        return Result.success("查询成功", aftersaleService.getDetail(aftersaleId));
    }

    @PutMapping("/api/v1/web/aftersale/{aftersaleId}/audit")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<AftersaleResponse> audit(@PathVariable @Min(1) Long aftersaleId,
                                           @Valid @RequestBody AftersaleAuditRequest request) {
        return Result.success("审核完成", aftersaleService.audit(aftersaleId, request));
    }
}
