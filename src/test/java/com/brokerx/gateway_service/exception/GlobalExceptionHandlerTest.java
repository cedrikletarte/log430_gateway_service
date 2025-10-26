package com.brokerx.gateway_service.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTest {

    @Test
    void shouldMapResponseStatusException() {
        var handler = new GlobalExceptionHandler();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());

        handler.handle(exchange, new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"))
            .block();

        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
    }

    @Test
    void shouldDefaultToInternalError() {
        var handler = new GlobalExceptionHandler();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());

        handler.handle(exchange, new RuntimeException("boom"))
            .block();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
    }
}
