package com.brokerx.gateway_service.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;

class GatewayJwtAuthenticationFilterTest {

    private JwtService jwtService;
    private GatewayJwtAuthenticationFilter filter;

    private static final String TEST_SECRET =
        "dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtdG9rZW4tdGhhdC1pcy1sb25nLWVub3VnaC1mb3ItaHMyNTY=";

    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey", TEST_SECRET);
        signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_SECRET));

        filter = new GatewayJwtAuthenticationFilter(jwtService);
        // 32-byte secret for HS256 signing used inside generateSignature
        ReflectionTestUtils.setField(filter, "gatewaySecret", "0123456789abcdef0123456789abcdef");
    }

    private String validToken(String userId, String email, String role) {
        return Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .claim("role", role)
                .expiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(signingKey)
                .compact();
    }

    @Test
    void shouldPassThroughAndEnrichHeadersWhenJwtValid() {
        String token = validToken("42", "user@example.com", "USER");

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/wallet/balance")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header("User-Agent", "JUnit")
            .header("X-Forwarded-For", "1.2.3.4")
            .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerHttpRequest> captured = new AtomicReference<>();

        var chain = (org.springframework.cloud.gateway.filter.GatewayFilterChain) ex -> {
            captured.set(ex.getRequest());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        ServerHttpRequest mutated = captured.get();
        assertNotNull(mutated, "Filter chain should be invoked with mutated request");

        assertEquals("42", mutated.getHeaders().getFirst("X-User-Id"));
        assertEquals("user@example.com", mutated.getHeaders().getFirst("X-User-Email"));
        assertEquals("USER", mutated.getHeaders().getFirst("X-User-Role"));
        assertEquals("1.2.3.4", mutated.getHeaders().getFirst("X-Client-Real-IP"));
        assertEquals("JUnit", mutated.getHeaders().getFirst("X-Client-User-Agent"));
        assertNotNull(mutated.getHeaders().getFirst("X-Gateway-Secret"));
        assertFalse(mutated.getHeaders().getFirst("X-Gateway-Secret").isBlank());
    }

    @Test
    void shouldRejectWhenMissingAuthorization() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/wallet/balance").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        var chainCalled = new boolean[] { false };
        var chain = (org.springframework.cloud.gateway.filter.GatewayFilterChain) ex -> {
            chainCalled[0] = true;
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertFalse(chainCalled[0], "Chain should not be called on unauthorized requests");
    }
}
