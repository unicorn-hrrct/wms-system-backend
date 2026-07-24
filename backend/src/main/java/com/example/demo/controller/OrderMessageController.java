package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.OrderMessageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 10.2 订单异步处理：发送订单事件到 MQ + 查询消息发送状态。
 */
@RestController
@RequestMapping("/api/v1/order/message")
public class OrderMessageController {

    private final OrderMessageService service;

    public OrderMessageController(OrderMessageService service) {
        this.service = service;
    }

    @PostMapping("/send")
    public Result<Map<String, Object>> send(@Valid @RequestBody SendRequest request) {
        return Result.success("消息发送成功",
            service.send(request.orderId(), request.eventType(), request.payload()));
    }

    @GetMapping("/status/{messageId}")
    public Result<Map<String, Object>> status(@PathVariable @NotBlank String messageId) {
        return Result.success("查询成功", service.status(messageId));
    }

    public record SendRequest(@NotNull @Min(1) Long orderId,
                              @NotBlank String eventType,
                              Object payload) {
    }
}