package com.example.demo.aspect;

import com.example.demo.security.UserPrincipal;
import com.example.demo.service.OperationLogWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class OperationLogAspect {

    private final OperationLogWriter writer;

    public OperationLogAspect(OperationLogWriter writer) { this.writer = writer; }

    @Around("execution(public * com.example.demo.controller..*(..))")
    public Object log(ProceedingJoinPoint joinPoint) throws Throwable {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null || "GET".equalsIgnoreCase(attributes.getRequest().getMethod())) {
            return joinPoint.proceed();
        }
        HttpServletRequest request = attributes.getRequest();
        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            write(joinPoint, request, System.currentTimeMillis() - start, 0, null);
            return result;
        } catch (Throwable ex) {
            write(joinPoint, request, System.currentTimeMillis() - start, 1, ex.getMessage());
            throw ex;
        }
    }

    private void write(ProceedingJoinPoint point, HttpServletRequest request, long cost, int status, String error) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            Long userId = null; String username = null;
            if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
                userId = principal.getUserId(); username = principal.getUsername();
            }
            String[] segments = request.getRequestURI().split("/");
            String module = segments.length > 3 ? segments[3] : "system";
            writer.write(userId, username, module, point.getSignature().getName(),
                request.getMethod() + " " + request.getRequestURI(), request.getRequestURI(), clientIp(request), cost, status, error);
        } catch (RuntimeException ignored) {
            // Logging failures must not alter the business response.
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
    }
}
