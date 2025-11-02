package com.brokerx.gateway_service.config;


import com.brokerx.gateway_service.dto.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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


@Component
@RequiredArgsConstructor
public class GatewayJwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LogManager.getLogger(GatewayJwtAuthenticationFilter.class);

    @Value("${gateway.secret}")
    private String gatewaySecret;

    private final JwtService jwtService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Endpoints publics (no JWT required)
    private static final List<String> PUBLIC_PATHS = List.of(
        "/api/v1/auth/register",
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        "/api/v1/auth/verify-otp",
        "/api/v1/auth/logout",
        "/ws/market",  // WebSocket public endpoint for initial connection
        "/ws/orders",  // WebSocket public endpoint for initial connection
        "/v3/api-docs",
        "/swagger-ui",
        "/actuator/health",
        "/actuator/prometheus"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        String method = request.getMethod().name();

        // Extract client info (toujours)
        String clientIp = extractRealClientIp(request);
        String userAgent = extractUserAgent(request);

        logger.info("Incoming request - Method: {}, Path: {}, IP: {}", method, path, clientIp);

        ServerHttpRequest mutatedRequestBuilder = request.mutate()
            .header("X-Client-Real-IP", clientIp)
            .header("X-Client-User-Agent", userAgent)
            .build();


        // OPTIONS requests (CORS preflight)
        if ("OPTIONS".equals(request.getMethod().name())) {
            logger.debug("CORS preflight request for path: {}", path);
            return chain.filter(exchange.mutate().request(mutatedRequestBuilder).build());
        }

        // Skip validation for public endpoints
        if (isPublicPath(path)) {
            logger.debug("Public endpoint accessed: {}", path);
            return chain.filter(exchange.mutate().request(mutatedRequestBuilder).build());
        }

        // Extract JWT
        String jwt = extractJwt(request);
        
        if (jwt == null) {
            logger.warn("Authentication failed - Missing JWT - Path: {}, IP: {}", path, clientIp);
            return onError(exchange, "Missing or invalid Authorization header", HttpStatus.UNAUTHORIZED);
        }

        try {
            // Validate JWT
            if (!jwtService.isTokenValid(jwt)) {
                logger.warn("Authentication failed - Invalid JWT - Path: {}, IP: {}", path, clientIp);
                return onError(exchange, "Invalid JWT token", HttpStatus.UNAUTHORIZED);
            }

            // Extract claims and add them to headers for microservices
            String userId = jwtService.extractUserId(jwt);
            String email = jwtService.extractEmail(jwt);
            String role = jwtService.extractRole(jwt);

            logger.info("Authentication successful - User: {}, UserId: {}, Role: {}, Path: {}", email, userId, role, path);

            
            // Add user information, client IP and User-Agent to headers
            ServerHttpRequest modifiedRequest = request.mutate()
                .header("X-User-Id", userId)
                .header("X-User-Email", email)
                .header("X-User-Role", role)
                .header("X-Client-Real-IP", clientIp)
                .header("X-Client-User-Agent", userAgent)
                .header("X-Gateway-Secret", generateSignature(userId, email, role))
                .build();

            return chain.filter(exchange.mutate().request(modifiedRequest).build());

        } catch (ExpiredJwtException e) {
            logger.warn("Authentication failed - Expired JWT - Path: {}, IP: {}", path, clientIp);
            return onError(exchange, "JWT token expired", HttpStatus.UNAUTHORIZED);
        } catch (JwtException e) {
            logger.warn("Authentication failed - JWT validation error: {} - Path: {}, IP: {}", e.getMessage(), path, clientIp);
            return onError(exchange, "Invalid JWT token", HttpStatus.UNAUTHORIZED);
        } catch (Exception e) {
            logger.error("Unexpected error during JWT validation - Path: {}, IP: {}", path, clientIp, e);
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
     * Returns error response with proper status code in ApiResponse format
     */
    private Mono<Void> onError(ServerWebExchange exchange, String message, HttpStatus status) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json");
        
        // Create standardized error response
        ApiResponse<Void> errorResponse = new ApiResponse<>(
            "ERROR",
            determineErrorCode(status),
            message,
            null
        );
        
        String errorJson;
        try {
            errorJson = objectMapper.writeValueAsString(errorResponse);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize error response", e);
            errorJson = String.format(
                "{\"status\":\"ERROR\",\"errorCode\":\"%s\",\"message\":\"%s\",\"data\":null}",
                determineErrorCode(status), message
            );
        }
        
        logger.info("Request rejected - Status: {}, Message: {}, Path: {}", 
                status.value(), message, exchange.getRequest().getPath().value());
        
        return response.writeWith(
            Mono.just(response.bufferFactory().wrap(errorJson.getBytes()))
        );
    }

    /**
     * Determines appropriate error code based on HTTP status
     */
    private String determineErrorCode(HttpStatus status) {
        return switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 500 -> "INTERNAL_ERROR";
            default -> "UNKNOWN_ERROR";
        };
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

    /**
     * Extracts the real client IP address, handling proxies and load balancers
     */
    private String extractRealClientIp(ServerHttpRequest request) {
        // Check X-Forwarded-For header (standard for proxies)
        String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // X-Forwarded-For can contain multiple IPs: "client, proxy1, proxy2"
            // The first one is usually the real client IP
            String[] ips = xForwardedFor.split(",");
            for (String ip : ips) {
                String cleanIp = ip.trim();
                if (!isInternalIp(cleanIp)) {
                    return cleanIp;
                }
            }
        }
        
        // Check X-Real-IP header (often used by NGINX)
        String xRealIp = request.getHeaders().getFirst("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty() && !isInternalIp(xRealIp)) {
            return xRealIp;
        }
        
        // Fallback to remote address
        try {
            var remoteAddress = request.getRemoteAddress();
            if (remoteAddress != null && remoteAddress.getAddress() != null) {
                return remoteAddress.getAddress().getHostAddress();
            }
        } catch (Exception e) {
            logger.warn("Error extracting remote address: {}", e.getMessage());
        }
        
        return "unknown";
    }

    /**
     * Extracts the User-Agent header from the request
     */
    private String extractUserAgent(ServerHttpRequest request) {
        String userAgent = request.getHeaders().getFirst("User-Agent");
        if (userAgent != null && !userAgent.isEmpty()) {
            // Truncate if too long to avoid header size issues
            return userAgent.length() > 500 ? userAgent.substring(0, 500) + "..." : userAgent;
        }
        return "unknown";
    }

    /**
     * Checks if an IP address is internal/private
     */
    private boolean isInternalIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return true;
        }
        
        // Common internal IP ranges
        return ip.startsWith("10.") ||
               ip.startsWith("172.16.") || ip.startsWith("172.17.") || ip.startsWith("172.18.") ||
               ip.startsWith("172.19.") || ip.startsWith("172.20.") || ip.startsWith("172.21.") ||
               ip.startsWith("172.22.") || ip.startsWith("172.23.") || ip.startsWith("172.24.") ||
               ip.startsWith("172.25.") || ip.startsWith("172.26.") || ip.startsWith("172.27.") ||
               ip.startsWith("172.28.") || ip.startsWith("172.29.") || ip.startsWith("172.30.") ||
               ip.startsWith("172.31.") ||
               ip.startsWith("192.168.") ||
               ip.equals("127.0.0.1") ||
               ip.equals("::1") ||
               ip.equals("localhost");
    }
}
