package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.SupplierAddressService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/supplier")
public class SupplierController {

    private final SupplierAddressService service;

    public SupplierController(SupplierAddressService service) {
        this.service = service;
    }

    @GetMapping("/list")
    @PreAuthorize("hasAnyRole('ADMIN','BUYER')")
    public Result<Map<String, Object>> list(@RequestParam(required = false) String name,
                                            @RequestParam(required = false) String contactPerson,
                                            @RequestParam(required = false) Integer status) {
        return Result.success("查询成功", service.listSuppliers(name, contactPerson, status));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','BUYER')")
    public Result<Map<String, Object>> create(@Valid @RequestBody SupplierCreateRequest request) {
        return Result.success("新增供应商成功", service.createSupplier(request.supplierName(), request.contactPerson(),
            request.phone(), request.address(), request.email(), request.remark()));
    }

    public record SupplierCreateRequest(@NotBlank String supplierName,
                                        String contactPerson,
                                        String phone,
                                        String address,
                                        @Email String email,
                                        String remark) {
    }
}
