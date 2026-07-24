package com.example.demo.security;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {

    public UserPrincipal requirePrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new BusinessException(ApiErrorCode.UNAUTHORIZED);
        }
        return principal;
    }

    public Long requireUserId() {
        return requirePrincipal().getUserId();
    }

    public String requireUsername() {
        return requirePrincipal().getUsername();
    }
}
