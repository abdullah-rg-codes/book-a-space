package com.everquint.bookingservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * Request body for POST /bookings.
 *
 * Note: Business rule validations (duration, working hours, overlaps)
 * are handled in the service layer — not here. Bean validation only
 * checks structural correctness (non-null, format).
 */
public record CreateBookingRequest(

        @NotNull(message = "roomId is required")
        String roomId,

        @NotBlank(message = "Title is required")
        String title,

        @NotBlank(message = "Organizer email is required")
        @Email(message = "Organizer email must be a valid email address")
        String organizerEmail,

        @NotNull(message = "startTime is required")
        LocalDateTime startTime,

        @NotNull(message = "endTime is required")
        LocalDateTime endTime
) {}
