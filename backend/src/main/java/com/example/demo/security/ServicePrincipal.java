package com.example.demo.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * 内部服务调用方身份：用于 Sales / IVP / Notification 等服务之间的 RPC 调用。
 * 仅承担"已认证"职责，不绑定具体用户。
 */
public class ServicePrincipal implements UserDetails {

    public static final String ROLE = "SERVICE";
    public static final String USERNAME = "__service__";

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + ROLE));
    }

    @Override public String getPassword() { return ""; }
    @Override public String getUsername() { return USERNAME; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}