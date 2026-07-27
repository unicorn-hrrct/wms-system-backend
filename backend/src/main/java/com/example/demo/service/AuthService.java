package com.example.demo.service;

import com.example.demo.dto.AuthLoginRequest;
import com.example.demo.dto.AuthRegisterRequest;
import com.example.demo.dto.PasswordChangeRequest;
import com.example.demo.security.UserPrincipal;
import com.example.demo.vo.AuthLoginResponse;
import com.example.demo.vo.CurrentUserResponse;
import com.example.demo.vo.MenuResponse;
import com.example.demo.vo.RoleResponse;

import java.util.List;

public interface AuthService {

    AuthLoginResponse login(AuthLoginRequest request);

    void register(AuthRegisterRequest request);

    CurrentUserResponse currentUser(UserPrincipal principal);

    void changePassword(UserPrincipal principal, PasswordChangeRequest request);

    List<RoleResponse> listRoles();

    List<MenuResponse> menuTree(UserPrincipal principal);
}
