package com.everquint.bookingservice.dto;

import com.everquint.bookingservice.entity.Room;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO for room data. Maps from the Room entity.
 */
public record RoomResponse(
        UUID id,
        String name,
        int capacity,
        int floor,
        List<String> amenities
) {
    /**
     * Factory method to convert a Room entity to a response DTO.
     * Keeps conversion logic in one place.
     */
    public static RoomResponse from(Room room) {
        return new RoomResponse(
                room.getId(),
                room.getName(),
                room.getCapacity(),
                room.getFloor(),
                room.getAmenities()
        );
    }
}
