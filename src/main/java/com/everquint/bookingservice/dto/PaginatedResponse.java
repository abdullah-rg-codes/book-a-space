package com.everquint.bookingservice.dto;

import java.util.List;

/**
 * Generic paginated response matching the spec format:
 * { "items": [...], "total": N, "limit": N, "offset": N }
 *
 * Using generics so this can wrap any type (BookingResponse, etc.)
 */
public record PaginatedResponse<T>(
        List<T> items,
        long total,
        int limit,
        int offset
) {}
