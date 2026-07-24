package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.dto.AddressUpdateRequest;
import com.example.demo.service.AddressService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/address")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list() {
        return Result.success("查询成功", addressService.list());
    }

    @GetMapping("/{addressId}")
    public Result<Map<String, Object>> get(@PathVariable @Min(1) Long addressId) {
        return Result.success("查询成功", addressService.get(addressId));
    }

    @PostMapping
    public Result<Map<String, Object>> create(@Valid @RequestBody AddressCreateRequest request) {
        return Result.success("新增地址成功", addressService.create(request.receiverName(), request.receiverPhone(),
            request.province(), request.city(), request.district(), request.detailAddress(), request.isDefault()));
    }

    @PutMapping("/{addressId}")
    public Result<Map<String, Object>> update(@PathVariable @Min(1) Long addressId,
                                              @Valid @RequestBody AddressUpdateRequest request) {
        return Result.success("地址更新成功", addressService.update(addressId,
            request.receiverName(), request.receiverPhone(),
            request.province(), request.city(), request.district(), request.detailAddress(),
            request.isDefault()));
    }

    @DeleteMapping("/{addressId}")
    public Result<Void> delete(@PathVariable @Min(1) Long addressId) {
        addressService.delete(addressId);
        return Result.success("地址已删除", null);
    }

    @PutMapping("/{addressId}/default")
    public Result<Map<String, Object>> setDefault(@PathVariable @Min(1) Long addressId) {
        return Result.success("已设为默认地址", addressService.setDefault(addressId));
    }

    public record AddressCreateRequest(@NotBlank String receiverName,
                                       @NotBlank String receiverPhone,
                                       @NotBlank String province,
                                       @NotBlank String city,
                                       @NotBlank String district,
                                       @NotBlank String detailAddress,
                                       Boolean isDefault) {
    }
}