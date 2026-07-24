package com.everquint.bookingservice.controller;

import com.everquint.bookingservice.entity.Booking;
import com.everquint.bookingservice.entity.BookingStatus;
import com.everquint.bookingservice.entity.Room;
import com.everquint.bookingservice.repository.BookingRepository;
import com.everquint.bookingservice.repository.IdempotencyRepository;
import com.everquint.bookingservice.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Exhaustive edge-case coverage for GET /reports/room-utilization.
 * Focuses on the business-hours denominator, clamping, rounding, and empty
 * data situations that the base ReportController test does not exercise.
 *
 * All fixed dates are anchored to Monday 2026-07-27:
 *   Mon 2026-07-27, Tue 2026-07-28, ... Fri 2026-07-31, Sat 2026-08-01, Sun 2026-08-02.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportApiEdgeCaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    private Room roomA;

    private final LocalDateTime monday = LocalDateTime.of(2026, 7, 27, 0, 0);
    private final LocalDateTime friday = LocalDateTime.of(2026, 7, 31, 0, 0);
    private final LocalDateTime saturday = LocalDateTime.of(2026, 8, 1, 0, 0);
    private final LocalDateTime sunday = LocalDateTime.of(2026, 8, 2, 0, 0);
    private final LocalDateTime tuesday = LocalDateTime.of(2026, 7, 28, 0, 0);

    @BeforeEach
    void setUp() {
        idempotencyRepository.deleteAll();
        bookingRepository.deleteAll();
        roomRepository.deleteAll();

        roomA = new Room();
        roomA.setName("Room Alpha");
        roomA.setCapacity(10);
        roomA.setFloor(1);
        roomA.setAmenities(List.of("projector"));
        roomA = roomRepository.save(roomA);
    }

    @Test
    @DisplayName("returns 400 when 'from' equals 'to'")
    void fromEqualsTo_returns400() throws Exception {
        mockMvc.perform(get("/reports/room-utilization")
                        .param("from", monday.toString())
                        .param("to", monday.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ValidationError"))
                .andExpect(jsonPath("$.message", containsString("before")));
    }

    @Test
    @DisplayName("weekend-only range yields 0 business hours, so utilization is 0 even with a booking")
    void weekendOnlyRange_zeroUtilizationDespiteBooking() throws Exception {
        // Booking physically on Saturday 09:00-11:00 (seeded directly, bypassing weekday rule)
        saveBooking(saturday.withHour(9), saturday.withHour(11));

        mockMvc.perform(get("/reports/room-utilization")
                        .param("from", saturday.toString())
                        .param("to", sunday.withHour(23).withMinute(59).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.roomName == 'Room Alpha')].totalBookingHours", hasItem(closeTo(2.0, 0.001))))
                .andExpect(jsonPath("$[?(@.roomName == 'Room Alpha')].utilizationPercent", hasItem(closeTo(0.0, 0.001))));
    }

    @Test
    @DisplayName("multi-day range counts five weekdays (5 x 12 = 60 business hours)")
    void multiDayWeekRange_countsFiveWeekdays() throws Exception {
        // 3-hour booking on Monday
        saveBooking(monday.withHour(9), monday.withHour(12));

        // Monday 00:00 -> Friday 23:59 => 5 weekdays => 60 business hours => 3/60 = 0.05
        mockMvc.perform(get("/reports/room-utilization")
                        .param("from", monday.toString())
                        .param("to", friday.withHour(23).withMinute(59).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.roomName == 'Room Alpha')].totalBookingHours", hasItem(closeTo(3.0, 0.001))))
                .andExpect(jsonPath("$[?(@.roomName == 'Room Alpha')].utilizationPercent", hasItem(closeTo(0.05, 0.001))));
    }

    @Test
    @DisplayName("a booking fully outside the range contributes 0 hours")
    void bookingFullyOutsideRange_zeroHours() throws Exception {
        // Booking on Monday, but report only covers Tuesday
        saveBooking(monday.withHour(9), monday.withHour(10));

        mockMvc.perform(get("/reports/room-utilization")
                        .param("from", tuesday.toString())
                        .param("to", tuesday.withHour(23).withMinute(59).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.roomName == 'Room Alpha')].totalBookingHours", hasItem(closeTo(0.0, 0.001))));
    }

    @Test
    @DisplayName("fractional durations are rounded to two decimals")
    void fractionalDuration_roundedToTwoDecimals() throws Exception {
        // 20-minute booking => 0.3333h -> rounds to 0.33; utilization 0.3333/12 = 0.0278 -> 0.03
        saveBooking(monday.withHour(9), monday.withHour(9).withMinute(20));

        mockMvc.perform(get("/reports/room-utilization")
                        .param("from", monday.toString())
                        .param("to", monday.withHour(23).withMinute(59).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.roomName == 'Room Alpha')].totalBookingHours", hasItem(closeTo(0.33, 0.001))))
                .andExpect(jsonPath("$[?(@.roomName == 'Room Alpha')].utilizationPercent", hasItem(closeTo(0.03, 0.001))));
    }

    @Test
    @DisplayName("a full 12-hour business day booking yields 100% utilization (ratio 1.0)")
    void fullBusinessDay_yieldsFullUtilization() throws Exception {
        // 08:00 - 20:00 = 12 hours (seeded directly, bypassing the 4h booking cap)
        saveBooking(monday.withHour(8), monday.withHour(20));

        mockMvc.perform(get("/reports/room-utilization")
                        .param("from", monday.toString())
                        .param("to", monday.withHour(23).withMinute(59).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.roomName == 'Room Alpha')].totalBookingHours", hasItem(closeTo(12.0, 0.001))))
                .andExpect(jsonPath("$[?(@.roomName == 'Room Alpha')].utilizationPercent", hasItem(closeTo(1.0, 0.001))));
    }

    @Test
    @DisplayName("multiple bookings for the same room are summed")
    void multipleBookingsSameRoom_summed() throws Exception {
        saveBooking(monday.withHour(9), monday.withHour(10));   // 1h
        saveBooking(monday.withHour(11), monday.withHour(12));  // 1h

        mockMvc.perform(get("/reports/room-utilization")
                        .param("from", monday.toString())
                        .param("to", monday.withHour(23).withMinute(59).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.roomName == 'Room Alpha')].totalBookingHours", hasItem(closeTo(2.0, 0.001))));
    }

    @Test
    @DisplayName("returns an empty array when there are no rooms at all")
    void noRooms_returnsEmptyArray() throws Exception {
        bookingRepository.deleteAll();
        roomRepository.deleteAll();

        mockMvc.perform(get("/reports/room-utilization")
                        .param("from", monday.toString())
                        .param("to", monday.withHour(23).withMinute(59).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // Helper

    private void saveBooking(LocalDateTime start, LocalDateTime end) {
        Booking booking = new Booking();
        booking.setRoom(roomA);
        booking.setTitle("Report Booking");
        booking.setOrganizerEmail("test@example.com");
        booking.setStartTime(start);
        booking.setEndTime(end);
        booking.setStatus(BookingStatus.CONFIRMED);
        bookingRepository.save(booking);
    }
}
