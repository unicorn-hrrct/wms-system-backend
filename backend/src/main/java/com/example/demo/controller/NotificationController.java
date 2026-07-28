package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(required = false) Boolean read,
                                            @RequestParam(required = false) String type,
                                            @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
                                            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success("query success", service.list(read, type, pageNum, pageSize));
    }

    @GetMapping("/unread-count")
    public Result<Map<String, Object>> unreadCount() {
        return Result.success("query success", service.unreadCount());
    }

    @PutMapping("/{notificationId}/read")
    public Result<Map<String, Object>> markRead(@PathVariable @Min(1) Long notificationId) {
        return Result.success("marked read", service.markRead(notificationId));
    }

    @PutMapping("/read-all")
    public Result<Map<String, Object>> markAllRead() {
        return Result.success("marked read", service.markAllRead());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> create(@Valid @RequestBody NotificationCreateRequest request) {
        return Result.success("notification created",
            service.create(request.title(), request.content(), request.type(), request.targetType(),
                request.targetUserId(), request.targetRoleKey(), request.bizType(), request.bizId(),
                request.expireTime()));
    }

    @PutMapping("/{notificationId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> updateStatus(@PathVariable @Min(1) Long notificationId,
                                                    @Valid @RequestBody NotificationStatusRequest request) {
        return Result.success("status updated", service.updateStatus(notificationId, request.status()));
    }

    @DeleteMapping("/{notificationId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable @Min(1) Long notificationId) {
        service.delete(notificationId);
        return Result.success("notification deleted", null);
    }

    public record NotificationCreateRequest(@NotBlank @Size(max = 120) String title,
                                            @NotBlank @Size(max = 1000) String content,
                                            @Size(max = 32) String type,
                                            @Size(max = 16) String targetType,
                                            @Min(1) Long targetUserId,
                                            @Size(max = 50) String targetRoleKey,
                                            @Size(max = 50) String bizType,
                                            @Size(max = 80) String bizId,
                                            LocalDateTime expireTime) {
    }

    public record NotificationStatusRequest(@NotNull @Min(0) @Max(1) Integer status) {
    }
}
