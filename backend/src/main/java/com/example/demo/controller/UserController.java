package com.example.demo.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.demo.common.ApiErrorCode;
import com.example.demo.common.PageResult;
import com.example.demo.common.Result;
import com.example.demo.dto.UserCreateRequest;
import com.example.demo.dto.UserUpdateRequest;
import com.example.demo.entity.User;
import com.example.demo.exception.BusinessException;
import com.example.demo.service.UserService;
import com.example.demo.vo.UserResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserService userService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public Result<List<UserResponse>> list() {
        List<UserResponse> users = userService.list()
            .stream()
            .map(UserResponse::from)
            .toList();
        return Result.success("查询成功", users);
    }

    @GetMapping("/{id}")
    public Result<UserResponse> get(@PathVariable @Min(value = 1, message = "必须大于 0") Long id) {
        return Result.success("查询成功", UserResponse.from(requireUser(id)));
    }

    @PostMapping
    public Result<UserResponse> create(@Valid @RequestBody UserCreateRequest request) {
        if (userService.existsByUsername(request.getUsername())) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "用户名已存在");
        }
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname() == null ? request.getUsername() : request.getNickname());
        user.setAvatar(request.getAvatar() == null ? "/avatar/default.png" : request.getAvatar());
        user.setPhone(request.getPhone());
        user.setEmail(request.getEmail());
        user.setAge(request.getAge());
        user.setStatus(0);
        userService.save(user);
        return Result.success("创建成功", UserResponse.from(user));
    }

    @PutMapping("/{id}")
    public Result<UserResponse> update(@PathVariable @Min(value = 1, message = "必须大于 0") Long id,
                                       @Valid @RequestBody UserUpdateRequest request) {
        User user = requireUser(id);
        if (request.getUsername() != null) {
            User sameNameUser = userService.getByUsername(request.getUsername());
            if (sameNameUser != null && !sameNameUser.getId().equals(id)) {
                throw new BusinessException(ApiErrorCode.BAD_REQUEST, "用户名已存在");
            }
            user.setUsername(request.getUsername());
        }
        if (request.getNickname() != null) {
            user.setNickname(request.getNickname());
        }
        if (request.getAvatar() != null) {
            user.setAvatar(request.getAvatar());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        if (request.getAge() != null) {
            user.setAge(request.getAge());
        }
        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }
        userService.updateById(user);
        return Result.success("更新成功", UserResponse.from(user));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable @Min(value = 1, message = "必须大于 0") Long id) {
        requireUser(id);
        userService.removeById(id);
        return Result.success();
    }

    @GetMapping("/page")
    public Result<PageResult<UserResponse>> page(@RequestParam(required = false) @Min(value = 1, message = "必须大于 0") Integer pageNo,
                                                 @RequestParam(required = false) @Min(value = 1, message = "必须大于 0") Integer pageNum,
                                                 @RequestParam(defaultValue = "10") @Min(value = 1, message = "必须大于 0") @Max(value = 100, message = "不能大于 100") Integer pageSize) {
        int current = pageNo != null ? pageNo : pageNum != null ? pageNum : 1;
        IPage<UserResponse> page = userService.pageList(current, pageSize).convert(UserResponse::from);
        return Result.success("查询成功", PageResult.of(page));
    }

    private User requireUser(Long id) {
        User user = userService.getById(id);
        if (user == null) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }
}
