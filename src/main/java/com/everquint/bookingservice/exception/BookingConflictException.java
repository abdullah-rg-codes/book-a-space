package com.everquint.bookingservice.exception;

/**
 * Thrown when a booking overlaps with an existing confirmed booking.
 * Maps to HTTP 409 Conflict.
 */
public class BookingConflictException extends RuntimeException {

    public BookingConflictException(String message) {
        super(message);
    }
}
