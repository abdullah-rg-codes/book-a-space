package com.everquint.bookingservice.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for POST /rooms.
 *
 * Using a Java record — gives us constructor, getters, equals, hashCode, toString for free.
 * Validation annotations trigger automatically when @Valid is used in the controller.
 */
public record CreateRoomRequest(

        @NotBlank(message = "Room name is required")
        String name,

        @Min(value = 1, message = "Capacity must be at least 1")
        int capacity,

        @NotNull(message = "Floor is required")
        Integer floor,

        List<String> amenities
) {
    // Provide default empty list if amenities is null
    public CreateRoomRequest {
        if (amenities == null) {
            amenities = List.of();
        }
    }
}
