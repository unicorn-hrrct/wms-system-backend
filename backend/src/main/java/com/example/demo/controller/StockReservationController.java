package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.dto.StockReservationRequest;
import com.example.demo.security.ServicePrincipal;
import com.example.demo.service.StockReservationService;
import com.example.demo.vo.StockReservationResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock")
public class StockReservationController {

    private final StockReservationService reservationService;

    public StockReservationController(StockReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /** v1.2 §10.1.0 整单批量预占：仅 Service Token 可调用 */
    @PostMapping("/reservations")
    @PreAuthorize("hasRole('SERVICE')")
    public Result<StockReservationResponse> reserve(@Valid @RequestBody StockReservationRequest request) {
        assertServicePrincipal();
        return Result.success("预占成功", reservationService.reserveAll(request));
    }

    private void assertServicePrincipal() {
        var principal = SecurityContextHolder.getContext().getAuthentication();
        if (principal == null || !(principal.getPrincipal() instanceof ServicePrincipal)) {
            throw new AccessDeniedException("仅 Service Token 可调用");
        }
    }
}