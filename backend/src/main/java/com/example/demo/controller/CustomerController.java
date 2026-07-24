package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.dto.CustomerUpdateRequest;
import com.example.demo.service.CustomerService;
import com.example.demo.vo.CustomerResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping("/me")
    public Result<CustomerResponse> me() {
        return Result.success("查询成功", customerService.getOrCreateCurrent());
    }

    @PutMapping("/me")
    public Result<CustomerResponse> update(@Valid @RequestBody CustomerUpdateRequest request) {
        return Result.success("更新成功", customerService.updateCurrent(request.nickname(), request.phone(), request.email()));
    }
}