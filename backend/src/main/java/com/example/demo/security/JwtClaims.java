package com.example.demo.security;

import java.util.List;

public class JwtClaims {

    private final Long userId;
    private final String username;
    private final List<String> roles;
    private final long expireTime;

    public JwtClaims(Long userId, String username, List<String> roles, long expireTime) {
        this.userId = userId;
        this.username = username;
        this.roles = roles;
        this.expireTime = expireTime;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public List<String> getRoles() {
        return roles;
    }

    public long getExpireTime() {
        return expireTime;
    }
}
