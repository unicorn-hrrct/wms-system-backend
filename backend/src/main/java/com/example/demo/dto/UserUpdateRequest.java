package com.example.demo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public class UserUpdateRequest {

    @Size(max = 50, message = "长度不能超过 50")
    private String username;

    @Size(max = 50, message = "长度不能超过 50")
    private String nickname;

    @Size(max = 255, message = "长度不能超过 255")
    private String avatar;

    @Size(max = 20, message = "长度不能超过 20")
    private String phone;

    @Email(message = "格式不正确")
    @Size(max = 100, message = "长度不能超过 100")
    private String email;

    @Min(value = 0, message = "不能小于 0")
    @Max(value = 150, message = "不能大于 150")
    private Integer age;

    @Min(value = 0, message = "不能小于 0")
    @Max(value = 1, message = "不能大于 1")
    private Integer status;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
