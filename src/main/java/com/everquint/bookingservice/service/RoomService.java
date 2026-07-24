package com.everquint.bookingservice.service;

import com.everquint.bookingservice.dto.CreateRoomRequest;
import com.everquint.bookingservice.dto.RoomResponse;
import com.everquint.bookingservice.entity.Room;
import com.everquint.bookingservice.exception.DuplicateRoomException;
import com.everquint.bookingservice.repository.RoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RoomService {

    private final RoomRepository roomRepository;

    // Constructor injection — preferred over @Autowired field injection (testable, explicit)
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
        // Business rule: name must be unique (case-insensitive)
        if (roomRepository.existsByNameIgnoreCase(request.name())) {
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
        return RoomResponse.from(saved);
    }

    /**
     * Lists all rooms, optionally filtered by minCapacity and/or amenity.
     *
     * Filtering approach: load all rooms and filter in-memory.
     * For a production system with thousands of rooms, you'd push these
     * filters into a JPQL query or use Specifications. Fine for this scale.
     */
    @Transactional(readOnly = true)
    public List<RoomResponse> listRooms(Integer minCapacity, String amenity) {
        List<Room> rooms = roomRepository.findAll();

        return rooms.stream()
                .filter(room -> minCapacity == null || room.getCapacity() >= minCapacity)
                .filter(room -> amenity == null || room.getAmenities().stream()
                        .anyMatch(a -> a.equalsIgnoreCase(amenity)))
                .map(RoomResponse::from)
                .collect(Collectors.toList());
    }
}
