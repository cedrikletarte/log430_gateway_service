package com.brokerx.gateway_service;


import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;


@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayJwtAuthenticationFilter implements GlobalFilter, Ordered {

    @Value("${gateway.secret}")
    private String gatewaySecret;

    private final JwtService jwtService;

    // Endpoints publics (no JWT required)
    private static final List<String> PUBLIC_PATHS = List.of(
        "/api/auth/register",
        "/api/auth/login",
        "/api/auth/refresh",
        "/api/auth/verify-otp",
        "/ws/market",  // WebSocket endpoint public pour la connexion initiale
        "/v3/api-docs",
        "/swagger-ui",
        "/actuator/health"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        System.out.println("Request Path: " + path);

        // Skip validation for public endpoints
        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        // OPTIONS requests (CORS preflight)
        if ("OPTIONS".equals(request.getMethod().name())) {
            return chain.filter(exchange);
        }

        // Extract JWT
        String jwt = extractJwt(request);
        
        if (jwt == null) {
            return onError(exchange, "Missing or invalid Authorization header", HttpStatus.UNAUTHORIZED);
        }

        try {
            // Validate JWT
            if (!jwtService.isTokenValid(jwt)) {
                return onError(exchange, "Invalid JWT token", HttpStatus.UNAUTHORIZED);
            }

            // Extract claims and add them to headers for microservices
            String userId = jwtService.extractUserId(jwt);
            String email = jwtService.extractEmail(jwt);
            String role = jwtService.extractRole(jwt);

            System.out.println("JWT validated - userId: " + userId + ", email: " + email + ", role: " + role);

            // Add user information to headers
            ServerHttpRequest modifiedRequest = request.mutate()
                .header("X-User-Id", userId)
                .header("X-User-Email", email)
                .header("X-User-Role", role)
                .header("X-Gateway-Secret", generateSignature(userId, email, role))
                .build();

            log.debug("JWT validated for user: {} ({})", email, userId);

            return chain.filter(exchange.mutate().request(modifiedRequest).build());

        } catch (ExpiredJwtException e) {
            log.warn("Expired JWT token: {}", e.getMessage());
            return onError(exchange, "JWT token expired", HttpStatus.UNAUTHORIZED);
        } catch (JwtException e) {
            log.error("JWT validation error: {}", e.getMessage());
            return onError(exchange, "Invalid JWT token", HttpStatus.UNAUTHORIZED);
        } catch (Exception e) {
            log.error("Unexpected error during JWT validation", e);
            return onError(exchange, "Authentication error", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Extracts JWT from Authorization header or cookies
     */
    private String extractJwt(ServerHttpRequest request) {
        // 1. Try Authorization header
        HttpHeaders headers = request.getHeaders();
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
        
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        // 2. Try cookie (fallback)
        var cookies = request.getCookies().get("accessToken");
        if (cookies != null && !cookies.isEmpty()) {
            return cookies.get(0).getValue();
        }

        return null;
    }

    /**
     * Checks if the path is public (no authentication required)
     */
    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * Returns error response with proper status code
     */
    private Mono<Void> onError(ServerWebExchange exchange, String message, HttpStatus status) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json");
        
        String errorJson = String.format(
            "{\"error\":\"%s\",\"status\":%d,\"timestamp\":\"%s\"}",
            message, status.value(), java.time.Instant.now()
        );
        
        return response.writeWith(
            Mono.just(response.bufferFactory().wrap(errorJson.getBytes()))
        );
    }

    @Override
    public int getOrder() {
        return -100; // Exécuter tôt dans la chaîne de filtres
    }

    private String generateSignature(String userId, String email, String role) {
        String data = userId + ":" + email + ":" + role + ":" + System.currentTimeMillis();
        return Jwts.builder()
            .subject("gateway-auth")
            .claim("data", data)
            .signWith(Keys.hmacShaKeyFor(gatewaySecret.getBytes()))
            .compact();
    }
}
