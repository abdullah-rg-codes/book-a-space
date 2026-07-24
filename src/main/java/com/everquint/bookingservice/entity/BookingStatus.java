package com.everquint.bookingservice.entity;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Represents the lifecycle states of a booking.
 * - CONFIRMED: Active booking that occupies the time slot
 * - CANCELLED: Booking was cancelled and no longer blocks the slot
 *
 * @JsonValue ensures Jackson serializes as lowercase ("confirmed", "cancelled")
 * to match the API spec, while the DB stores the uppercase enum name via @Enumerated(STRING).
 */
public enum BookingStatus {
    CONFIRMED,
    CANCELLED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
