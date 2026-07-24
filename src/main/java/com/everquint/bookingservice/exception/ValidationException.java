package com.everquint.bookingservice.exception;

/**
 * Thrown when a request fails business rule validation.
 * Maps to HTTP 400 Bad Request.
 *
 * Examples: invalid duration, outside working hours, startTime >= endTime
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
