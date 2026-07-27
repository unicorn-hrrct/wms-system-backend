package com.example.demo.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class JwtUtil {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final String secret;
    private final long expireSeconds;

    public JwtUtil(ObjectMapper objectMapper,
                   @Value("${app.jwt.secret}") String secret,
                   @Value("${app.jwt.expire-seconds:86400}") long expireSeconds) {
        this.objectMapper = objectMapper;
        this.secret = secret;
        this.expireSeconds = expireSeconds;
    }

    public String generateToken(Long userId, String username, List<String> roles) {
        long expireAt = Instant.now().plusSeconds(expireSeconds).getEpochSecond();
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = Map.of(
            "sub", String.valueOf(userId),
            "userId", userId,
            "username", username,
            "roles", roles,
            "exp", expireAt
        );
        String unsigned = encodeJson(header) + "." + encodeJson(payload);
        return unsigned + "." + sign(unsigned);
    }

    public JwtClaims parseToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            String unsigned = parts[0] + "." + parts[1];
            String expectedSignature = sign(unsigned);
            if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.US_ASCII), parts[2].getBytes(StandardCharsets.US_ASCII))) {
                return null;
            }

            byte[] payloadBytes = URL_DECODER.decode(parts[1]);
            Map<String, Object> payload = objectMapper.readValue(payloadBytes, new TypeReference<>() {});
            Number exp = (Number) payload.get("exp");
            if (exp == null || Instant.now().getEpochSecond() >= exp.longValue()) {
                return null;
            }

            Number userId = (Number) payload.get("userId");
            String username = String.valueOf(payload.get("username"));
            List<String> roles = ((List<?>) payload.getOrDefault("roles", List.of()))
                .stream()
                .map(String::valueOf)
                .toList();
            return new JwtClaims(userId.longValue(), username, roles, exp.longValue() * 1000);
        } catch (Exception ex) {
            return null;
        }
    }

    public long getExpireSeconds() {
        return expireSeconds;
    }

    private String encodeJson(Object value) {
        try {
            return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception ex) {
            throw new IllegalStateException("JWT JSON encode failed", ex);
        }
    }

    private String sign(String unsigned) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return URL_ENCODER.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("JWT signature failed", ex);
        }
    }
}
