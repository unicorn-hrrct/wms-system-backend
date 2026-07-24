package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.DashboardScreenService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 10.3.1 数据大屏总览：返回 ECharts 所需的所有聚合数据。
 *
 * <p>10.3.2 WebSocket 推送：本项目未引入 spring-boot-starter-websocket，
 * 这里以 {@link DashboardPusher#broadcast} 提供定时广播占位实现，方便后续接入 STOMP/WebSocket 时直接替换。
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardScreenController {

    private final DashboardScreenService service;
    private final DashboardPusher pusher;

    public DashboardScreenController(DashboardScreenService service, DashboardPusher pusher) {
        this.service = service;
        this.pusher = pusher;
    }

    @GetMapping("/screen")
    @PreAuthorize("hasAnyRole('ADMIN','BUYER','KEEPER','SELLER')")
    public Result<Map<String, Object>> screen() {
        Map<String, Object> data = service.aggregate();
        pusher.publish(data);
        return Result.success("查询成功", data);
    }

    /**
     * 简单的内存广播总线 —— 供未来 WebSocket 接入复用。
     */
    @Component
    public static class DashboardPusher {
        private final CopyOnWriteArrayList<Consumer<Map<String, Object>>> subscribers = new CopyOnWriteArrayList<>();

        public void subscribe(Consumer<Map<String, Object>> consumer) {
            subscribers.add(consumer);
        }

        public void publish(Map<String, Object> payload) {
            for (Consumer<Map<String, Object>> consumer : subscribers) {
                try {
                    consumer.accept(payload);
                } catch (Exception ignored) {
                    // 单个订阅者失败不影响其他订阅者
                }
            }
        }
    }

    /**
     * 预留：每 10 秒聚合一次最新数据并广播。实际 WebSocket 接入时调用 pusher.publish 即可。
     */
    @Scheduled(fixedDelayString = "${app.dashboard.screen-refresh-millis:10000}")
    public void refresh() {
        pusher.publish(service.aggregate());
    }
}