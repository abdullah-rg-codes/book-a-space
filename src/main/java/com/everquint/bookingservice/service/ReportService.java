package com.everquint.bookingservice.service;

import com.everquint.bookingservice.dto.RoomUtilizationResponse;
import com.everquint.bookingservice.entity.Booking;
import com.everquint.bookingservice.entity.BookingStatus;
import com.everquint.bookingservice.entity.Room;
import com.everquint.bookingservice.repository.BookingRepository;
import com.everquint.bookingservice.repository.RoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private static final int BUSINESS_HOURS_PER_DAY = 12; // 08:00 - 20:00

    private final RoomRepository roomRepository;
    private final BookingRepository bookingRepository;

    public ReportService(RoomRepository roomRepository, BookingRepository bookingRepository) {
        this.roomRepository = roomRepository;
        this.bookingRepository = bookingRepository;
    }

    /**
     * Calculates room utilization for all rooms in the given time range.
     *
     * Formula:
     *   utilizationPercent = totalBookingHours / totalBusinessHours
     *   where totalBusinessHours = weekdays in [from, to] × 12 hours
     *
     * Bookings that partially overlap the range are clamped to [from, to].
     * Only CONFIRMED bookings are counted.
     */
    @Transactional(readOnly = true)
    public List<RoomUtilizationResponse> getRoomUtilization(LocalDateTime from, LocalDateTime to) {
        log.debug("getRoomUtilization() called with from={}, to={}", from, to);

        // Calculate total business hours in the range
        double totalBusinessHours = calculateBusinessHours(from, to);
        log.debug("Total business hours in range: {}", totalBusinessHours);

        // Get all rooms
        List<Room> allRooms = roomRepository.findAll();

        // Get all confirmed bookings that overlap with the range
        List<Booking> bookingsInRange = bookingRepository.findConfirmedBookingsInRange(
                from, to, BookingStatus.CONFIRMED);

        // Group bookings by room ID
        Map<UUID, List<Booking>> bookingsByRoom = bookingsInRange.stream()
                .collect(Collectors.groupingBy(b -> b.getRoom().getId()));

        // Calculate utilization for each room
        List<RoomUtilizationResponse> results = new ArrayList<>();

        for (Room room : allRooms) {
            List<Booking> roomBookings = bookingsByRoom.getOrDefault(room.getId(), List.of());

            double totalBookingHours = 0.0;
            for (Booking booking : roomBookings) {
                totalBookingHours += calculateClampedHours(booking, from, to);
            }

            double utilizationPercent = totalBusinessHours > 0
                    ? totalBookingHours / totalBusinessHours
                    : 0.0;

            // Round to avoid floating-point noise
            totalBookingHours = Math.round(totalBookingHours * 100.0) / 100.0;
            utilizationPercent = Math.round(utilizationPercent * 100.0) / 100.0;

            results.add(new RoomUtilizationResponse(
                    room.getId(),
                    room.getName(),
                    totalBookingHours,
                    utilizationPercent
            ));
        }

        log.info("Utilization report generated for {} rooms in range [{}, {}]",
                results.size(), from, to);
        return results;
    }

    /**
     * Calculates the effective booked hours for a booking, clamped to [from, to].
     *
     * If a booking starts before 'from', we only count from 'from'.
     * If a booking ends after 'to', we only count up to 'to'.
     */
    private double calculateClampedHours(Booking booking, LocalDateTime from, LocalDateTime to) {
        LocalDateTime effectiveStart = booking.getStartTime().isBefore(from)
                ? from : booking.getStartTime();
        LocalDateTime effectiveEnd = booking.getEndTime().isAfter(to)
                ? to : booking.getEndTime();

        if (!effectiveStart.isBefore(effectiveEnd)) {
            return 0.0;
        }

        return Duration.between(effectiveStart, effectiveEnd).toMinutes() / 60.0;
    }

    /**
     * Calculates total business hours between two dates.
     * Business hours = weekdays (Mon-Fri) × 12 hours per day (08:00-20:00).
     */
    private double calculateBusinessHours(LocalDateTime from, LocalDateTime to) {
        int weekdayCount = 0;
        LocalDate date = from.toLocalDate();
        LocalDate endDate = to.toLocalDate();

        while (!date.isAfter(endDate)) {
            DayOfWeek day = date.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                weekdayCount++;
            }
            date = date.plusDays(1);
        }

        return (double) weekdayCount * BUSINESS_HOURS_PER_DAY;
    }
}
