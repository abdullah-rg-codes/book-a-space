package com.everquint.bookingservice.dto;

/**
 * Consistent JSON error format as per spec:
 * { "error": "ValidationError", "message": "startTime must be before endTime" }
 */
public record ErrorResponse(
        String error,
        String message
) {}
