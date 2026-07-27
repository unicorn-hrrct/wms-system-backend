package com.example.demo.config;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.common.Result;
import com.example.demo.security.JwtAuthenticationFilter;
import com.example.demo.security.ServiceTokenAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ServiceTokenAuthenticationFilter serviceTokenAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          ServiceTokenAuthenticationFilter serviceTokenAuthenticationFilter,
                          ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.serviceTokenAuthenticationFilter = serviceTokenAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, authException) -> writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ApiErrorCode.UNAUTHORIZED))
                .accessDeniedHandler((request, response, accessDeniedException) -> writeError(response, HttpServletResponse.SC_FORBIDDEN, ApiErrorCode.FORBIDDEN))
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/register").permitAll()
                // v1.2 §10.2.1：内部服务调用入口，仅接受 X-Service-Token
                .requestMatchers("/api/v1/stock/reservations", "/api/v1/order/message/send", "/api/v1/payment/mock-callback").hasRole("SERVICE")
                .requestMatchers("/api/v1/users", "/api/v1/users/**", "/api/v1/role", "/api/v1/role/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/categories/**", "/api/v1/products/**", "/api/v1/skus/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/categories/**", "/api/v1/products/**", "/api/v1/skus/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/categories/**", "/api/v1/products/**", "/api/v1/skus/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/config/list", "/api/v1/log/operation").hasRole("ADMIN")
                .requestMatchers("/api/v1/stock/lock/**", "/api/v1/order/message/**").hasAnyRole("ADMIN", "BUYER", "SELLER")
                .requestMatchers("/api/v1/dashboard/screen", "/api/v1/dashboard/screen/**").hasAnyRole("ADMIN", "BUYER", "KEEPER", "SELLER")
                .requestMatchers("/api/v1/owner/list", "/api/v1/stock/by-owner", "/api/v1/stock/isolation-check").hasAnyRole("ADMIN", "BUYER", "KEEPER")
                .requestMatchers("/api/v1/barcode/parse", "/api/v1/scan/**").hasAnyRole("ADMIN", "KEEPER")
                .requestMatchers("/api/v1/restock/**").hasAnyRole("ADMIN", "BUYER")
                .anyRequest().authenticated()
            )
            .addFilterBefore(serviceTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Idempotency-Key", "X-Service-Token", "X-Mock-Timestamp", "X-Mock-Nonce", "X-Mock-Signature"));
        configuration.setExposedHeaders(List.of("Authorization", "Idempotency-Replayed"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    private void writeError(HttpServletResponse response, int status, ApiErrorCode errorCode) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Result.error(errorCode));
    }
}
