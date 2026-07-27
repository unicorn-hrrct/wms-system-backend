package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.security.UserPrincipal;
import com.example.demo.service.AuthService;
import com.example.demo.vo.MenuResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/menu")
public class MenuController {

    private final AuthService authService;

    public MenuController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/tree")
    public Result<List<MenuResponse>> tree(@AuthenticationPrincipal UserPrincipal principal) {
        return Result.success("查询成功", authService.menuTree(principal));
    }
}
