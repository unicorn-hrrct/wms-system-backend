package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.CustomerServiceTicketService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/customer-service")
public class CustomerServiceController {

    private final CustomerServiceTicketService service;

    public CustomerServiceController(CustomerServiceTicketService service) {
        this.service = service;
    }

    @PostMapping("/tickets")
    public Result<Map<String, Object>> create(@Valid @RequestBody TicketCreateRequest request) {
        return Result.success("ticket created",
            service.create(request.subject(), request.category(), request.content(), request.images()));
    }

    @GetMapping("/tickets/my")
    public Result<Map<String, Object>> mine(@RequestParam(required = false) Integer status,
                                            @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
                                            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success("query success", service.myTickets(status, pageNum, pageSize));
    }

    @GetMapping("/tickets")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<Map<String, Object>> all(@RequestParam(required = false) Integer status,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
                                           @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success("query success", service.allTickets(status, keyword, pageNum, pageSize));
    }

    @GetMapping("/tickets/{ticketId}")
    public Result<Map<String, Object>> detail(@PathVariable @Min(1) Long ticketId) {
        return Result.success("query success", service.detail(ticketId));
    }

    @PostMapping("/tickets/{ticketId}/messages")
    public Result<Map<String, Object>> reply(@PathVariable @Min(1) Long ticketId,
                                             @Valid @RequestBody TicketMessageRequest request) {
        return Result.success("message sent", service.reply(ticketId, request.content(), request.images()));
    }

    @PutMapping("/tickets/{ticketId}/close")
    public Result<Map<String, Object>> close(@PathVariable @Min(1) Long ticketId) {
        return Result.success("ticket closed", service.close(ticketId));
    }

    @PutMapping("/tickets/{ticketId}/assign")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<Map<String, Object>> assign(@PathVariable @Min(1) Long ticketId,
                                              @Valid @RequestBody TicketAssignRequest request) {
        return Result.success("ticket assigned", service.assign(ticketId, request.assigneeId()));
    }

    @PutMapping("/tickets/{ticketId}/status")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Result<Map<String, Object>> updateStatus(@PathVariable @Min(1) Long ticketId,
                                                    @Valid @RequestBody TicketStatusRequest request) {
        return Result.success("status updated", service.updateStatus(ticketId, request.status()));
    }

    public record TicketCreateRequest(@NotBlank @Size(max = 120) String subject,
                                      @Size(max = 40) String category,
                                      @NotBlank @Size(max = 1000) String content,
                                      List<@Size(max = 255) String> images) {
    }

    public record TicketMessageRequest(@NotBlank @Size(max = 1000) String content,
                                       List<@Size(max = 255) String> images) {
    }

    public record TicketAssignRequest(@NotNull @Min(1) Long assigneeId) {
    }

    public record TicketStatusRequest(@NotNull @Min(0) @Max(3) Integer status) {
    }
}
