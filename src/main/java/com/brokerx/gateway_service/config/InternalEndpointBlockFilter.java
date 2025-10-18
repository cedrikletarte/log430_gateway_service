package com.brokerx.gateway_service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global filter that blocks access to internal endpoints (/internal/**).
 * These endpoints are reserved for inter-microservice communication
 * and should never be exposed publicly via the Gateway.
 */
@Slf4j
@Component
public class InternalEndpointBlockFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        
        // Block access to /internal/** endpoints
        if (path.contains("/internal/")) {
            log.warn("Blocked access to internal endpoint: {} from IP: {}", 
                    path, 
                    exchange.getRequest().getRemoteAddress());
            
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            exchange.getResponse().getHeaders().add("Content-Type", "application/json");
            
            String errorMessage = "{\"error\":\"Access to internal endpoints is forbidden\"}";
            return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(errorMessage.getBytes()))
            );
        }
        
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // Execute this filter first (before authentication)
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
