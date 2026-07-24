package com.everquint.bookingservice.service;

import com.everquint.bookingservice.dto.CreateRoomRequest;
import com.everquint.bookingservice.dto.RoomResponse;
import com.everquint.bookingservice.entity.Room;
import com.everquint.bookingservice.exception.DuplicateRoomException;
import com.everquint.bookingservice.repository.RoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private final RoomRepository roomRepository;

    public RoomService(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    /**
     * Creates a new room after validating name uniqueness (case-insensitive).
     *
     * @throws DuplicateRoomException if a room with the same name already exists
     */
    @Transactional
    public RoomResponse createRoom(CreateRoomRequest request) {
        log.debug("createRoom() called with name='{}', capacity={}, floor={}",
                request.name(), request.capacity(), request.floor());

        // Business rule: name must be unique (case-insensitive)
        if (roomRepository.existsByNameIgnoreCase(request.name())) {
            log.warn("Duplicate room name attempted: '{}'", request.name());
            throw new DuplicateRoomException(request.name());
        }

        // Map DTO -> Entity
        Room room = new Room();
        room.setName(request.name());
        room.setCapacity(request.capacity());
        room.setFloor(request.floor());
        room.setAmenities(request.amenities());

        // Persist and return
        Room saved = roomRepository.save(room);
        log.info("Room created successfully: id={}, name='{}'", saved.getId(), saved.getName());
        return RoomResponse.from(saved);
    }

    /**
     * Lists all rooms, optionally filtered by minCapacity and/or amenity.
     */
    @Transactional(readOnly = true)
    public List<RoomResponse> listRooms(Integer minCapacity, String amenity) {
        log.debug("listRooms() called with minCapacity={}, amenity='{}'", minCapacity, amenity);

        List<Room> rooms = roomRepository.findAll();

        List<RoomResponse> filtered = rooms.stream()
                .filter(room -> minCapacity == null || room.getCapacity() >= minCapacity)
                .filter(room -> amenity == null || room.getAmenities().stream()
                        .anyMatch(a -> a.equalsIgnoreCase(amenity)))
                .map(RoomResponse::from)
                .collect(Collectors.toList());

        log.debug("listRooms() returning {} rooms (out of {} total)", filtered.size(), rooms.size());
        return filtered;
    }
}
