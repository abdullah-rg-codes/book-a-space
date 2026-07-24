package com.everquint.bookingservice.exception;

/**
 * Thrown when a referenced booking does not exist.
 * Maps to HTTP 404 Not Found.
 */
public class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException(String bookingId) {
        super("Booking not found with id: " + bookingId);
    }
}
