package com.everquint.bookingservice.entity;

/**
 * Represents the lifecycle states of a booking.
 * - CONFIRMED: Active booking that occupies the time slot
 * - CANCELLED: Booking was cancelled and no longer blocks the slot
 */
public enum BookingStatus {
    CONFIRMED,
    CANCELLED
}
