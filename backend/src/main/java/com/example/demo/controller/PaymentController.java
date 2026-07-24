package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.dto.MockCallbackRequest;
import com.example.demo.dto.PaymentPrepayRequest;
import com.example.demo.security.ServicePrincipal;
import com.example.demo.service.PaymentService;
import com.example.demo.vo.PaymentResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/payment")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/prepay")
    public Result<PaymentResponse> prepay(@Min(1) Long orderId, @Valid @RequestBody PaymentPrepayRequest request) {
        return Result.success("预支付创建成功", paymentService.prepay(orderId, request));
    }

    @PostMapping("/mock-callback")
    @PreAuthorize("hasRole('SERVICE')")
    public Result<Map<String, Object>> mockCallback(@RequestBody MockCallbackRequest request,
                                                    @RequestHeader("X-Mock-Timestamp") String timestamp,
                                                    @RequestHeader("X-Mock-Nonce") String nonce,
                                                    @RequestHeader("X-Mock-Signature") String signature) {
        assertServicePrincipal();
        return Result.success("回调处理完成", paymentService.mockCallback(request, timestamp, nonce, signature));
    }

    @GetMapping("/{payNo}/status")
    public Result<Map<String, Object>> status(@PathVariable String payNo) {
        return Result.success("查询成功", paymentService.status(payNo));
    }

    private void assertServicePrincipal() {
        var principal = SecurityContextHolder.getContext().getAuthentication();
        if (principal == null || !(principal.getPrincipal() instanceof ServicePrincipal)) {
            throw new org.springframework.security.access.AccessDeniedException("仅 Service Token 可调用");
        }
    }
}