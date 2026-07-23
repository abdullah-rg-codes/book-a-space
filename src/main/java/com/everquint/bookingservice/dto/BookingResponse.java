package com.everquint.bookingservice.dto;

import com.everquint.bookingservice.entity.Booking;
import com.everquint.bookingservice.entity.BookingStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for booking data.
 * Returns roomId as a string (matching the flexible "string-or-int" contract in the spec).
 */
public record BookingResponse(
        UUID id,
        String roomId,
        String title,
        String organizerEmail,
        LocalDateTime startTime,
        LocalDateTime endTime,
        BookingStatus status
) {
    /**
     * Factory method to convert a Booking entity to a response DTO.
     */
    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getRoom().getId().toString(),
                booking.getTitle(),
                booking.getOrganizerEmail(),
                booking.getStartTime(),
                booking.getEndTime(),
                booking.getStatus()
        );
    }
}
