package com.everquint.bookingservice.exception;

/**
 * Thrown when a referenced room does not exist.
 * Maps to HTTP 404 Not Found.
 */
public class RoomNotFoundException extends RuntimeException {

    public RoomNotFoundException(String roomId) {
        super("Room not found with id: " + roomId);
    }
}
