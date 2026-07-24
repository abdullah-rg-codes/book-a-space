package com.everquint.bookingservice.repository;

import com.everquint.bookingservice.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, UUID> {

    /**
     * Looks up an existing idempotency record by key + organizer.
     * If found, the associated booking should be returned (no new booking created).
     */
    Optional<IdempotencyRecord> findByIdempotencyKeyAndOrganizerEmail(
            String idempotencyKey, String organizerEmail);
}
