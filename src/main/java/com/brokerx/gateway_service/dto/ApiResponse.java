package com.brokerx.gateway_service.dto;

public record ApiResponse<T>(
    String status,
    String errorCode,
    String message,
    T data
) {}
