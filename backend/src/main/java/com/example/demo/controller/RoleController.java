package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.AuthService;
import com.example.demo.vo.RoleResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/role")
public class RoleController {

    private final AuthService authService;

    public RoleController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/list")
    public Result<List<RoleResponse>> list() {
        return Result.success("查询成功", authService.listRoles());
    }
}
