package com.everquint.bookingservice.controller;

import com.everquint.bookingservice.entity.Booking;
import com.everquint.bookingservice.entity.BookingStatus;
import com.everquint.bookingservice.entity.Room;
import com.everquint.bookingservice.repository.BookingRepository;
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
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BookingCancellationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private BookingRepository bookingRepository;

    private Room testRoom;

    @BeforeEach
    void setUp() {
        bookingRepository.deleteAll();
        roomRepository.deleteAll();

        testRoom = new Room();
        testRoom.setName("Cancel Test Room");
        testRoom.setCapacity(10);
        testRoom.setFloor(1);
        testRoom.setAmenities(List.of());
        testRoom = roomRepository.save(testRoom);
    }

    @Test
    @DisplayName("successfully cancels a booking more than 1 hour before start")
    void cancelBooking_success() throws Exception {
        // Create a booking starting far in the future (48 hours from now)
        Booking booking = createBookingInDb(LocalDateTime.now().plusHours(48));

        mockMvc.perform(post("/bookings/" + booking.getId() + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(booking.getId().toString()))
                .andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    @DisplayName("returns 404 when booking does not exist")
    void cancelBooking_notFound_returns404() throws Exception {
        UUID fakeId = UUID.randomUUID();

        mockMvc.perform(post("/bookings/" + fakeId + "/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NotFoundError"));
    }

    @Test
    @DisplayName("returns existing cancelled booking when already cancelled (no-op)")
    void cancelBooking_alreadyCancelled_returnsExisting() throws Exception {
        // Create a cancelled booking directly
        Booking booking = createBookingInDb(LocalDateTime.now().plusHours(48));
        booking.setStatus(BookingStatus.CANCELLED);
        booking = bookingRepository.save(booking);

        mockMvc.perform(post("/bookings/" + booking.getId() + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.id").value(booking.getId().toString()));
    }

    @Test
    @DisplayName("returns 400 when less than 1 hour before start time")
    void cancelBooking_tooLate_returns400() throws Exception {
        // Create a booking starting 30 minutes from now (less than 1 hour)
        Booking booking = createBookingInDb(LocalDateTime.now().plusMinutes(30));

        mockMvc.perform(post("/bookings/" + booking.getId() + "/cancel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ValidationError"))
                .andExpect(jsonPath("$.message", containsString("1 hour before start time")));
    }

    @Test
    @DisplayName("returns 400 when booking has already started")
    void cancelBooking_alreadyStarted_returns400() throws Exception {
        // Create a booking that started 30 minutes ago
        Booking booking = createBookingInDb(LocalDateTime.now().minusMinutes(30));

        mockMvc.perform(post("/bookings/" + booking.getId() + "/cancel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ValidationError"));
    }

    // Helper

    private Booking createBookingInDb(LocalDateTime startTime) {
        Booking booking = new Booking();
        booking.setRoom(testRoom);
        booking.setTitle("Test Booking");
        booking.setOrganizerEmail("test@example.com");
        booking.setStartTime(startTime);
        booking.setEndTime(startTime.plusHours(1));
        booking.setStatus(BookingStatus.CONFIRMED);
        return bookingRepository.save(booking);
    }
}
