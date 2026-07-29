package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.dto.AddressUpdateRequest;
import com.example.demo.service.AddressService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    public Result<Map<String, Object>> create(
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AddressCreateRequest request) {
        return Result.success("新增地址成功", addressService.create(request.receiverName(), request.receiverPhone(),
            request.province(), request.city(), request.district(), request.detailAddress(), request.isDefault(),
            idempotencyKey));
    }

    @PutMapping("/{addressId}")
    public Result<Map<String, Object>> update(
        @PathVariable @Min(1) Long addressId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AddressUpdateRequest request) {
        return Result.success("地址更新成功", addressService.update(addressId,
            request.receiverName(), request.receiverPhone(),
            request.province(), request.city(), request.district(), request.detailAddress(),
            request.isDefault(), idempotencyKey));
    }

    @DeleteMapping("/{addressId}")
    public Result<Void> delete(
        @PathVariable @Min(1) Long addressId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        addressService.delete(addressId, idempotencyKey);
        return Result.success("地址已删除", null);
    }

    @PutMapping("/{addressId}/default")
    public Result<Map<String, Object>> setDefault(
        @PathVariable @Min(1) Long addressId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Result.success("已设为默认地址", addressService.setDefault(addressId, idempotencyKey));
    }

    public record AddressCreateRequest(
        @NotBlank @Size(max = 50) String receiverName,
        @NotBlank @Size(max = 30) String receiverPhone,
        @NotBlank @Size(max = 50) String province,
        @NotBlank @Size(max = 50) String city,
        @NotBlank @Size(max = 50) String district,
        @NotBlank @Size(max = 255) String detailAddress,
        Boolean isDefault) {
    }
}
