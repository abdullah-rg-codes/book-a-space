package com.everquint.bookingservice.exception;

/**
 * Thrown when attempting to create a room with a name that already exists.
 * Maps to HTTP 409 Conflict.
 */
public class DuplicateRoomException extends RuntimeException {

    public DuplicateRoomException(String name) {
        super("A room with the name '" + name + "' already exists");
    }
}
