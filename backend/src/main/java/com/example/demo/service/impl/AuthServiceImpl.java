package com.example.demo.service.impl;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.dto.AuthLoginRequest;
import com.example.demo.dto.AuthRegisterRequest;
import com.example.demo.dto.PasswordChangeRequest;
import com.example.demo.entity.Menu;
import com.example.demo.entity.Role;
import com.example.demo.entity.User;
import com.example.demo.entity.UserRole;
import com.example.demo.exception.BusinessException;
import com.example.demo.mapper.MenuMapper;
import com.example.demo.mapper.RoleMapper;
import com.example.demo.mapper.UserRoleMapper;
import com.example.demo.security.JwtUtil;
import com.example.demo.security.UserPrincipal;
import com.example.demo.service.AuthService;
import com.example.demo.service.UserService;
import com.example.demo.vo.AuthLoginResponse;
import com.example.demo.vo.CurrentUserResponse;
import com.example.demo.vo.MenuResponse;
import com.example.demo.vo.RoleResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_AVATAR = "/avatar/default.png";
    private static final String DEFAULT_ROLE_KEY = "user";

    private final UserService userService;
    private final RoleMapper roleMapper;
    private final MenuMapper menuMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;

    public AuthServiceImpl(UserService userService,
                           RoleMapper roleMapper,
                           MenuMapper menuMapper,
                           UserRoleMapper userRoleMapper,
                           PasswordEncoder passwordEncoder,
                           AuthenticationManager authenticationManager,
                           JwtUtil jwtUtil) {
        this.userService = userService;
        this.roleMapper = roleMapper;
        this.menuMapper = menuMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public AuthLoginResponse login(AuthLoginRequest request) {
        try {
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );
        } catch (AuthenticationException ex) {
            throw new BusinessException(ApiErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        User user = userService.getByUsername(request.getUsername());
        if (user == null) {
            throw new BusinessException(ApiErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        List<String> roles = roleMapper.selectByUserId(user.getId()).stream()
            .map(Role::getRoleKey)
            .toList();
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), roles);

        AuthLoginResponse response = new AuthLoginResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setAvatar(user.getAvatar());
        response.setRoles(roles);
        response.setToken(token);
        response.setExpireTime(Instant.now().plusSeconds(jwtUtil.getExpireSeconds()).toEpochMilli());
        return response;
    }

    @Override
    @Transactional
    public void register(AuthRegisterRequest request) {
        if (userService.existsByUsername(request.getUsername())) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "用户名已存在");
        }
        Role defaultRole = roleMapper.selectByRoleKey(DEFAULT_ROLE_KEY);
        if (defaultRole == null) {
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR, "默认角色不存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(StringUtils.hasText(request.getNickname()) ? request.getNickname() : request.getUsername());
        user.setAvatar(DEFAULT_AVATAR);
        user.setPhone(request.getPhone());
        user.setEmail(request.getEmail());
        user.setStatus(0);
        userService.save(user);

        UserRole userRole = new UserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(defaultRole.getId());
        userRoleMapper.insert(userRole);
    }

    @Override
    public CurrentUserResponse currentUser(UserPrincipal principal) {
        User user = requireUser(principal.getUserId());
        List<String> roles = roleMapper.selectByUserId(user.getId()).stream()
            .map(Role::getRoleKey)
            .toList();

        CurrentUserResponse response = new CurrentUserResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setAvatar(user.getAvatar());
        response.setPhone(user.getPhone());
        response.setEmail(user.getEmail());
        response.setRoles(roles);
        response.setPermissions(resolvePermissions(user.getId(), roles));
        return response;
    }

    @Override
    @Transactional
    public void changePassword(UserPrincipal principal, PasswordChangeRequest request) {
        User user = requireUser(principal.getUserId());
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "原密码错误");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userService.updateById(user);
    }

    @Override
    public List<RoleResponse> listRoles() {
        return roleMapper.selectActiveRoles()
            .stream()
            .map(RoleResponse::from)
            .toList();
    }

    @Override
    public List<MenuResponse> menuTree(UserPrincipal principal) {
        return buildTree(menuMapper.selectByUserId(principal.getUserId()));
    }

    private User requireUser(Long userId) {
        User user = userService.getById(userId);
        if (user == null || user.getStatus() != null && user.getStatus() != 0) {
            throw new BusinessException(ApiErrorCode.UNAUTHORIZED, "用户不可用");
        }
        return user;
    }

    private List<String> resolvePermissions(Long userId, List<String> roles) {
        if (roles.contains("admin")) {
            return List.of("*:*:*");
        }
        return menuMapper.selectPermissionsByUserId(userId);
    }

    private List<MenuResponse> buildTree(List<Menu> menus) {
        Map<Long, MenuResponse> byId = new LinkedHashMap<>();
        for (Menu menu : menus) {
            byId.put(menu.getId(), MenuResponse.from(menu));
        }

        List<MenuResponse> roots = new ArrayList<>();
        for (MenuResponse menu : byId.values()) {
            if (menu.getParentId() == null || menu.getParentId() == 0 || !byId.containsKey(menu.getParentId())) {
                roots.add(menu);
            } else {
                byId.get(menu.getParentId()).getChildren().add(menu);
            }
        }
        return roots;
    }
}
