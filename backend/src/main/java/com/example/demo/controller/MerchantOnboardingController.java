package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.dto.MerchantApplyRequest;
import com.example.demo.dto.MerchantAuditRequest;
import com.example.demo.service.MerchantOnboardingService;
import com.example.demo.vo.MerchantApplicationResponse;
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
public class MerchantOnboardingController {

    private final MerchantOnboardingService service;

    public MerchantOnboardingController(MerchantOnboardingService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/merchant/apply")
    public Result<MerchantApplicationResponse> apply(@Valid @RequestBody MerchantApplyRequest request,
                                                     @RequestHeader(value = "Idempotency-Key", required = false)
                                                     String idempotencyKey) {
        return Result.success("商家入驻申请提交成功", service.apply(request, idempotencyKey));
    }

    @GetMapping("/api/v1/merchant/application/my")
    public Result<MerchantApplicationResponse> myApplication() {
        return Result.success("查询成功", service.myApplication());
    }

    @GetMapping("/api/v1/web/merchant/applications")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> list(@RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) String merchantName,
                                            @RequestParam(required = false) String applicationNo,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                            LocalDateTime startDate,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                            LocalDateTime endDate,
                                            @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
                                            @RequestParam(defaultValue = "10") @Min(1) Integer pageSize) {
        return Result.success("查询成功", service.list(status, merchantName, applicationNo,
            startDate, endDate, pageNum, pageSize));
    }

    @GetMapping("/api/v1/web/merchant/applications/{applicationId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<MerchantApplicationResponse> detail(@PathVariable @Min(1) Long applicationId) {
        return Result.success("查询成功", service.detail(applicationId));
    }

    @PutMapping("/api/v1/web/merchant/applications/{applicationId}/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<MerchantApplicationResponse> audit(@PathVariable @Min(1) Long applicationId,
                                                     @Valid @RequestBody MerchantAuditRequest request) {
        return Result.success("审核完成", service.audit(applicationId, request));
    }
}
