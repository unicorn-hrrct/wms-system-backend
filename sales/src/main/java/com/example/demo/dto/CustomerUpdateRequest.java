package com.example.demo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record CustomerUpdateRequest(
    @Size(max = 50, message = "昵称长度不能超过 50")
    String nickname,

    @Size(max = 20, message = "手机号长度不能超过 20")
    String phone,

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱长度不能超过 100")
    String email) {
}
