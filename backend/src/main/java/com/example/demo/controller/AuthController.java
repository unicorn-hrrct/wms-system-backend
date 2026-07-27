package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.dto.AuthLoginRequest;
import com.example.demo.dto.AuthRegisterRequest;
import com.example.demo.service.AuthService;
import com.example.demo.vo.AuthLoginResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result<AuthLoginResponse> login(@Valid @RequestBody AuthLoginRequest request) {
        return Result.success("登录成功", authService.login(request));
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody AuthRegisterRequest request) {
        authService.register(request);
        return Result.success("注册成功", null);
    }
}
