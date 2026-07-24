package com.everquint.bookingservice.controller;

import com.everquint.bookingservice.dto.CreateRoomRequest;
import com.everquint.bookingservice.dto.RoomResponse;
import com.everquint.bookingservice.service.RoomService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    /**
     * POST /rooms — Create a new room.
     * @Valid triggers bean validation on the request body.
     * Returns 201 Created with the room object (including generated id).
     */
    @PostMapping
    public ResponseEntity<RoomResponse> createRoom(@Valid @RequestBody CreateRoomRequest request) {
        RoomResponse response = roomService.createRoom(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /rooms — List all rooms with optional filters.
     * @param minCapacity Optional — minimum room capacity
     * @param amenity     Optional — rooms must include this amenity
     */
    @GetMapping
    public ResponseEntity<List<RoomResponse>> listRooms(
            @RequestParam(required = false) Integer minCapacity,
            @RequestParam(required = false) String amenity) {

        List<RoomResponse> rooms = roomService.listRooms(minCapacity, amenity);
        return ResponseEntity.ok(rooms);
    }
}
