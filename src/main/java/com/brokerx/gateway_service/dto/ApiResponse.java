package com.brokerx.gateway_service.dto;

/* Generic API response wrapper */
public record ApiResponse<T>(
    String status,
    String errorCode,
    String message,
    T data
) {}
