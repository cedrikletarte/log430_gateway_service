package com.brokerx.gateway_service.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

class InternalEndpointBlockFilterTest {

    @Test
    void shouldBlockInternalEndpoints() {
        var filter = new InternalEndpointBlockFilter();

        var request = MockServerHttpRequest.get("/api/v1/auth/internal/health").build();
        var exchange = MockServerWebExchange.from(request);

        var chainCalled = new boolean[] { false };
        var chain = (org.springframework.cloud.gateway.filter.GatewayFilterChain) ex -> {
            chainCalled[0] = true;
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertFalse(chainCalled[0], "Chain should not be called for internal endpoint");
    }
}
