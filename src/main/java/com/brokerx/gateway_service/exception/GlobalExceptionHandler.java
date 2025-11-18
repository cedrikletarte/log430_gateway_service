package com.brokerx.gateway_service.exception;

import com.brokerx.gateway_service.dto.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Global exception handler for Spring Cloud Gateway (WebFlux).
 * Handles errors in a consistent ApiResponse format across all microservices.
 */
@Component
@Order(-1)
public class GlobalExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger logger = LogManager.getLogger(GlobalExceptionHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @NonNull
    public Mono<Void> handle(@NonNull ServerWebExchange exchange, @NonNull Throwable ex) {
        HttpStatus status;
        String errorCode;
        String message;

        // Log the error
        logger.error("Gateway error occurred: {}", ex.getMessage(), ex);

        // Determine error type and response
        if (ex instanceof ExpiredJwtException) {
            status = HttpStatus.UNAUTHORIZED;
            errorCode = "JWT_EXPIRED";
            message = "JWT token has expired";
        } else if (ex instanceof JwtException) {
            status = HttpStatus.UNAUTHORIZED;
            errorCode = "JWT_INVALID";
            message = "Invalid JWT token";
        } else if (ex instanceof ResponseStatusException rse) {
            HttpStatusCode statusCode = rse.getStatusCode();
            status = HttpStatus.valueOf(statusCode.value());
            errorCode = determineErrorCode(status);
            message = rse.getReason() != null ? rse.getReason() : status.getReasonPhrase();
        } else if (ex instanceof org.springframework.web.server.ServerWebInputException) {
            status = HttpStatus.BAD_REQUEST;
            errorCode = "INVALID_REQUEST";
            message = "Invalid request format";
        } else if (ex.getCause() instanceof java.net.ConnectException) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            errorCode = "SERVICE_UNAVAILABLE";
            message = "Downstream service is unavailable";
        } else if (ex.getCause() instanceof java.util.concurrent.TimeoutException) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            errorCode = "GATEWAY_TIMEOUT";
            message = "Request timeout while contacting downstream service";
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            errorCode = "INTERNAL_ERROR";
            message = "An unexpected error occurred in the gateway";
        }

        // Create standardized error response
        ApiResponse<Void> errorResponse = new ApiResponse<>(
            "ERROR",
            errorCode,
            message,
            null
        );

        // Set response status and headers
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        // Write JSON response
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(errorResponse);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize error response", e);
            bytes = "{\"status\":\"ERROR\",\"errorCode\":\"SERIALIZATION_ERROR\",\"message\":\"Failed to process error\",\"data\":null}"
                    .getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /* Determines appropriate error code based on HTTP status. */
    private String determineErrorCode(HttpStatus status) {
        return switch (status.value()) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 408 -> "REQUEST_TIMEOUT";
            case 429 -> "TOO_MANY_REQUESTS";
            case 500 -> "INTERNAL_ERROR";
            case 502 -> "BAD_GATEWAY";
            case 503 -> "SERVICE_UNAVAILABLE";
            case 504 -> "GATEWAY_TIMEOUT";
            default -> "UNKNOWN_ERROR";
        };
    }
}
