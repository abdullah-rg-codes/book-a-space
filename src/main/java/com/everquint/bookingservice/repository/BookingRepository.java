package com.everquint.bookingservice.repository;

import com.everquint.bookingservice.entity.Booking;
import com.everquint.bookingservice.entity.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /**
     * Detects overlapping CONFIRMED bookings for a given room.
     *
     * Two time ranges [startA, endA) and [startB, endB) overlap when:
     *   startA < endB AND startB < endA
     *
     * We only check against CONFIRMED bookings — cancelled ones don't block.
     */
    @Query("SELECT b FROM Booking b WHERE b.room.id = :roomId " +
           "AND b.status = :status " +
           "AND b.startTime < :endTime " +
           "AND b.endTime > :startTime")
    List<Booking> findOverlappingBookings(
            @Param("roomId") UUID roomId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("status") BookingStatus status);

    /**
     * Find bookings with optional filters: roomId, and time range [from, to].
     * Using a flexible JPQL query with null-check pattern:
     *   - If a param is null, that condition is skipped (always true).
     */
    @Query("SELECT b FROM Booking b WHERE " +
           "(:roomId IS NULL OR b.room.id = :roomId) " +
           "AND (:from IS NULL OR b.endTime > :from) " +
           "AND (:to IS NULL OR b.startTime < :to)")
    Page<Booking> findWithFilters(
            @Param("roomId") UUID roomId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    /**
     * Finds all confirmed bookings that overlap with the given time range.
     * Used for utilization report — fetches across all rooms.
     * A booking overlaps [from, to] when: booking.startTime < to AND booking.endTime > from
     */
    @Query("SELECT b FROM Booking b WHERE b.status = :status " +
           "AND b.startTime < :to " +
           "AND b.endTime > :from")
    List<Booking> findConfirmedBookingsInRange(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("status") BookingStatus status);
}
