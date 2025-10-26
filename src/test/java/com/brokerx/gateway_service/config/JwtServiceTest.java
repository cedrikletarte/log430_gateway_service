package com.brokerx.gateway_service.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;

class JwtServiceTest {

    private JwtService jwtService;

    // Base64 secret for tests (HS256)
    private static final String TEST_SECRET =
            "dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtdG9rZW4tdGhhdC1pcy1sb25nLWVub3VnaC1mb3ItaHMyNTY=";

    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey", TEST_SECRET);
        signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_SECRET));
    }

    private String buildToken(String userId, String email, String role, Date exp) {
        return Jwts.builder()
            .subject(userId)
            .claim("email", email)
            .claim("role", role)
            .expiration(exp)
            .signWith(signingKey)
            .compact();
    }

    @Test
    void shouldValidateAndExtractClaims() {
        String token = buildToken("123", "john@example.com", "USER", new Date(System.currentTimeMillis() + 3600_000));

        assertTrue(jwtService.isTokenValid(token));
        assertEquals("123", jwtService.extractUserId(token));
        assertEquals("john@example.com", jwtService.extractEmail(token));
        assertEquals("USER", jwtService.extractRole(token));
        String subject = jwtService.extractClaim(token, Claims::getSubject);
        assertEquals("123", subject);
    }

    @Test
    void shouldDetectExpiredToken() {
        String token = buildToken("1", "a@b.com", "USER", new Date(System.currentTimeMillis() - 1000));
        assertFalse(jwtService.isTokenValid(token));
    }
}
