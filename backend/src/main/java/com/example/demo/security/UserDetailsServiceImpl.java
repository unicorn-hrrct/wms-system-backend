package com.example.demo.security;

import com.example.demo.entity.Role;
import com.example.demo.entity.User;
import com.example.demo.mapper.RoleMapper;
import com.example.demo.service.UserService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserService userService;
    private final RoleMapper roleMapper;

    public UserDetailsServiceImpl(UserService userService, RoleMapper roleMapper) {
        this.userService = userService;
        this.roleMapper = roleMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        User user = userService.getByUsername(username);
        if (user == null || user.getStatus() != null && user.getStatus() != 0) {
            throw new BadCredentialsException("用户名或密码错误");
        }
        return new UserPrincipal(
            user,
            roleMapper.selectByUserId(user.getId()).stream().map(Role::getRoleKey).toList()
        );
    }
}
