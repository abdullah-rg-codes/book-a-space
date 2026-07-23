package com.everquint.bookingservice.repository;

import com.everquint.bookingservice.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoomRepository extends JpaRepository<Room, UUID> {

    /**
     * Check if a room with this name already exists (case-insensitive).
     * Spring Data parses the method name: "existsBy" + "Name" + "IgnoreCase"
     */
    boolean existsByNameIgnoreCase(String name);

    /**
     * Find a room by name (case-insensitive) — useful for debugging/lookups.
     */
    Optional<Room> findByNameIgnoreCase(String name);
}
