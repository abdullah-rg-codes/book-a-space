package com.everquint.bookingservice.dto;

import java.util.UUID;

/**
 * Response for the room utilization report.
 * One entry per room in the system.
 */
public record RoomUtilizationResponse(
        UUID roomId,
        String roomName,
        double totalBookingHours,
        double utilizationPercent
) {}
