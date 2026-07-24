package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.dto.PasswordChangeRequest;
import com.example.demo.security.UserPrincipal;
import com.example.demo.service.AuthService;
import com.example.demo.vo.CurrentUserResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user")
public class UserAccountController {

    private final AuthService authService;

    public UserAccountController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/info")
    public Result<CurrentUserResponse> info(@AuthenticationPrincipal UserPrincipal principal) {
        return Result.success("查询成功", authService.currentUser(principal));
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@AuthenticationPrincipal UserPrincipal principal,
                                       @Valid @RequestBody PasswordChangeRequest request) {
        authService.changePassword(principal, request);
        return Result.success();
    }
}
