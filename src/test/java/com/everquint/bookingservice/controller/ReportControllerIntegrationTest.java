package com.everquint.bookingservice.controller;

import com.everquint.bookingservice.entity.Booking;
import com.everquint.bookingservice.entity.BookingStatus;
import com.everquint.bookingservice.entity.Room;
import com.everquint.bookingservice.repository.BookingRepository;
import com.everquint.bookingservice.repository.IdempotencyRepository;
import com.everquint.bookingservice.repository.RoomRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@SpringBootTest
@AutoConfigureMockMvc
class ReportControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    private Room roomA;
    private Room roomB;

    // Use a fixed Monday for predictable calculations
    // Monday 2026-07-27 (a known Monday)
    private final LocalDateTime monday = LocalDateTime.of(2026, 7, 27, 0, 0);

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

        roomB = new Room();
        roomB.setName("Room Beta");
        roomB.setCapacity(6);
        roomB.setFloor(2);
        roomB.setAmenities(List.of());
        roomB = roomRepository.save(roomB);
    }

    @Test
    @DisplayName("returns utilization for all rooms with bookings")
    void utilization_withBookings() throws Exception {
        // Room A: 2-hour booking on Monday 09:00-11:00
        createBooking(roomA, monday.withHour(9), monday.withHour(11));

        // Report for Monday only (1 weekday = 12 business hours)
        // Room A: 2 hours / 12 hours = 0.17
        mockMvc.perform(get("/reports/room-utilization")
                        .param("from", monday.toString())
                        .param("to", monday.withHour(23).withMinute(59).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.roomName == 'Room Alpha')].totalBookingHours").value(2.0))
                .andExpect(jsonPath("$[?(@.roomName == 'Room Alpha')].utilizationPercent", hasItem(closeTo(0.17, 0.01))))
                .andExpect(jsonPath("$[?(@.roomName == 'Room Beta')].totalBookingHours").value(0.0))
                .andExpect(jsonPath("$[?(@.roomName == 'Room Beta')].utilizationPercent").value(0.0));
    }

    @Test
    @DisplayName("returns 0 utilization when no bookings in range")
    void utilization_noBookings() throws Exception {
        // No bookings created
        mockMvc.perform(get("/reports/room-utilization")
                        .param("from", monday.toString())
                        .param("to", monday.withHour(23).withMinute(59).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].totalBookingHours").value(0.0))
                .andExpect(jsonPath("$[0].utilizationPercent").value(0.0))
                .andExpect(jsonPath("$[1].totalBookingHours").value(0.0))
                .andExpect(jsonPath("$[1].utilizationPercent").value(0.0));
    }

    @Test
    @DisplayName("clamps booking that starts before 'from' to the range start")
    void utilization_bookingStartsBeforeFrom() throws Exception {
        // Booking: Monday 07:00 - 11:00 (starts before business hours and before 'from')
        // Report range: Monday 09:00 - Monday 23:59
        // Effective: 09:00 - 11:00 = 2 hours
        createBooking(roomA, monday.withHour(7), monday.withHour(11));

        mockMvc.perform(get("/reports/room-utilization")
                        .param("from", monday.withHour(9).toString())
                        .param("to", monday.withHour(23).withMinute(59).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.roomName == 'Room Alpha')].totalBookingHours").value(2.0));
    }

    @Test
    @DisplayName("clamps booking that ends after 'to' to the range end")
    void utilization_bookingEndsAfterTo() throws Exception {
        // Booking: Monday 14:00 - 18:00
        // Report range: Monday 00:00 - Monday 16:00
        // Effective: 14:00 - 16:00 = 2 hours
        createBooking(roomA, monday.withHour(14), monday.withHour(18));

        mockMvc.perform(get("/reports/room-utilization")
                        .param("from", monday.toString())
                        .param("to", monday.withHour(16).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.roomName == 'Room Alpha')].totalBookingHours").value(2.0));
    }

    @Test
    @DisplayName("cancelled bookings are not counted in utilization")
    void utilization_cancelledNotCounted() throws Exception {
        // Create a confirmed booking
        createBooking(roomA, monday.withHour(9), monday.withHour(11));

        // Create a cancelled booking (should be ignored)
        Booking cancelled = new Booking();
        cancelled.setRoom(roomA);
        cancelled.setTitle("Cancelled Meeting");
        cancelled.setOrganizerEmail("test@example.com");
        cancelled.setStartTime(monday.withHour(13));
        cancelled.setEndTime(monday.withHour(15));
        cancelled.setStatus(BookingStatus.CANCELLED);
        bookingRepository.save(cancelled);

        // Only the confirmed booking (2 hours) should count
        mockMvc.perform(get("/reports/room-utilization")
                        .param("from", monday.toString())
                        .param("to", monday.withHour(23).withMinute(59).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.roomName == 'Room Alpha')].totalBookingHours").value(2.0));
    }

    @Test
    @DisplayName("multiple rooms have independent utilization")
    void utilization_multipleRooms() throws Exception {
        // Room A: 3 hours booked
        createBooking(roomA, monday.withHour(9), monday.withHour(12));

        // Room B: 1 hour booked
        createBooking(roomB, monday.withHour(14), monday.withHour(15));

        mockMvc.perform(get("/reports/room-utilization")
                        .param("from", monday.toString())
                        .param("to", monday.withHour(23).withMinute(59).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.roomName == 'Room Alpha')].totalBookingHours").value(3.0))
                .andExpect(jsonPath("$[?(@.roomName == 'Room Beta')].totalBookingHours").value(1.0));
    }

    @Test
    @DisplayName("returns 400 when 'from' is missing")
    void utilization_missingFrom_returns400() throws Exception {
        mockMvc.perform(get("/reports/room-utilization")
                        .param("to", monday.withHour(23).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ValidationError"))
                .andExpect(jsonPath("$.message", containsString("from")));
    }

    @Test
    @DisplayName("returns 400 when 'to' is missing")
    void utilization_missingTo_returns400() throws Exception {
        mockMvc.perform(get("/reports/room-utilization")
                        .param("from", monday.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ValidationError"))
                .andExpect(jsonPath("$.message", containsString("to")));
    }

    @Test
    @DisplayName("returns 400 when 'from' is after 'to'")
    void utilization_fromAfterTo_returns400() throws Exception {
        mockMvc.perform(get("/reports/room-utilization")
                        .param("from", monday.plusDays(5).toString())
                        .param("to", monday.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ValidationError"))
                .andExpect(jsonPath("$.message", containsString("before")));
    }

    // Helper

    private void createBooking(Room room, LocalDateTime start, LocalDateTime end) {
        Booking booking = new Booking();
        booking.setRoom(room);
        booking.setTitle("Test Booking");
        booking.setOrganizerEmail("test@example.com");
        booking.setStartTime(start);
        booking.setEndTime(end);
        booking.setStatus(BookingStatus.CONFIRMED);
        bookingRepository.save(booking);
    }
}
