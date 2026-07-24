package com.everquint.bookingservice.controller;

import com.everquint.bookingservice.entity.Room;
import com.everquint.bookingservice.repository.BookingRepository;
import com.everquint.bookingservice.repository.RoomRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BookingControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private BookingRepository bookingRepository;

    private UUID roomId;

    // Next Monday at 09:00 — guarantees a valid working day/time
    private LocalDateTime nextMonday;

    @BeforeEach
    void setUp() {
        bookingRepository.deleteAll();
        roomRepository.deleteAll();

        // Create a test room directly in the DB
        Room room = new Room();
        room.setName("Test Room");
        room.setCapacity(10);
        room.setFloor(1);
        room.setAmenities(List.of("projector"));
        room = roomRepository.save(room);
        roomId = room.getId();

        // Calculate next Monday at 09:00
        LocalDateTime now = LocalDateTime.now();
        int daysUntilMonday = (8 - now.getDayOfWeek().getValue()) % 7;
        if (daysUntilMonday == 0) daysUntilMonday = 7; // always use a future Monday
        nextMonday = now.plusDays(daysUntilMonday).withHour(9).withMinute(0).withSecond(0).withNano(0);
    }

    // POST /bookings

    @Nested
    @DisplayName("POST /bookings - Create Booking")
    class CreateBooking {

        @Test
        @DisplayName("returns 201 with status confirmed on success")
        void createBooking_success() throws Exception {
            String body = bookingJson(roomId, nextMonday, nextMonday.plusHours(1));

            mockMvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.roomId").value(roomId.toString()))
                    .andExpect(jsonPath("$.title").value("Team Standup"))
                    .andExpect(jsonPath("$.organizerEmail").value("test@example.com"))
                    .andExpect(jsonPath("$.status").value("confirmed"));
        }

        @Test
        @DisplayName("returns 404 when room does not exist")
        void createBooking_roomNotFound_returns404() throws Exception {
            UUID fakeRoomId = UUID.randomUUID();
            String body = bookingJson(fakeRoomId, nextMonday, nextMonday.plusHours(1));

            mockMvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NotFoundError"));
        }

        @Test
        @DisplayName("returns 400 when startTime >= endTime")
        void createBooking_startAfterEnd_returns400() throws Exception {
            String body = bookingJson(roomId, nextMonday.plusHours(2), nextMonday.plusHours(1));

            mockMvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsString("startTime must be before endTime")));
        }

        @Test
        @DisplayName("returns 400 when duration is less than 15 minutes")
        void createBooking_durationTooShort_returns400() throws Exception {
            String body = bookingJson(roomId, nextMonday, nextMonday.plusMinutes(10));

            mockMvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("at least 15 minutes")));
        }

        @Test
        @DisplayName("returns 400 when duration exceeds 4 hours")
        void createBooking_durationTooLong_returns400() throws Exception {
            String body = bookingJson(roomId, nextMonday, nextMonday.plusHours(5));

            mockMvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("must not exceed 4 hours")));
        }

        @Test
        @DisplayName("returns 400 when booking is on a weekend")
        void createBooking_onWeekend_returns400() throws Exception {
            // Next Saturday
            LocalDateTime saturday = nextMonday.plusDays(5);
            String body = bookingJson(roomId, saturday, saturday.plusHours(1));

            mockMvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("Monday to Friday")));
        }

        @Test
        @DisplayName("returns 400 when booking starts before 08:00")
        void createBooking_beforeBusinessHours_returns400() throws Exception {
            LocalDateTime earlyStart = nextMonday.withHour(7).withMinute(0);
            String body = bookingJson(roomId, earlyStart, earlyStart.plusHours(1));

            mockMvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("08:00 and 20:00")));
        }

        @Test
        @DisplayName("returns 400 when booking ends after 20:00")
        void createBooking_afterBusinessHours_returns400() throws Exception {
            LocalDateTime lateStart = nextMonday.withHour(19).withMinute(0);
            LocalDateTime lateEnd = nextMonday.withHour(21).withMinute(0);
            String body = bookingJson(roomId, lateStart, lateEnd);

            mockMvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("08:00 and 20:00")));
        }

        @Test
        @DisplayName("returns 409 when booking overlaps with existing confirmed booking")
        void createBooking_overlapping_returns409() throws Exception {
            // First booking: 09:00 - 10:00
            String body1 = bookingJson(roomId, nextMonday, nextMonday.plusHours(1));
            mockMvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body1))
                    .andExpect(status().isCreated());

            // Overlapping booking: 09:30 - 10:30
            String body2 = bookingJson(roomId, nextMonday.plusMinutes(30), nextMonday.plusMinutes(90));
            mockMvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body2))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("ConflictError"))
                    .andExpect(jsonPath("$.message", containsString("already booked")));
        }

        @Test
        @DisplayName("allows booking in same slot after previous booking is cancelled")
        void createBooking_noOverlapWithCancelled() throws Exception {
            // Create and then cancel a booking at 09:00 - 10:00
            String body = bookingJson(roomId, nextMonday, nextMonday.plusHours(1));
            MvcResult result = mockMvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andReturn();

            String bookingId = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("id").asText();

            // Cancel it
            mockMvc.perform(post("/bookings/" + bookingId + "/cancel"))
                    .andExpect(status().isOk());

            // Same slot should now be available
            mockMvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("confirmed"));
        }

        @Test
        @DisplayName("returns 400 when organizerEmail is invalid")
        void createBooking_invalidEmail_returns400() throws Exception {
            Map<String, Object> request = Map.of(
                    "roomId", roomId.toString(),
                    "title", "Meeting",
                    "organizerEmail", "not-an-email",
                    "startTime", nextMonday.toString(),
                    "endTime", nextMonday.plusHours(1).toString()
            );
            String body = objectMapper.writeValueAsString(request);

            mockMvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"));
        }
    }

    // GET /bookings

    @Nested
    @DisplayName("GET /bookings - List Bookings")
    class ListBookings {

        @BeforeEach
        void seedBookings() throws Exception {
            // Create 3 bookings on next Monday
            createTestBooking(nextMonday, nextMonday.plusHours(1));                          // 09:00-10:00
            createTestBooking(nextMonday.plusHours(2), nextMonday.plusHours(3));             // 11:00-12:00
            createTestBooking(nextMonday.plusHours(4), nextMonday.plusHours(5));             // 13:00-14:00
        }

        @Test
        @DisplayName("returns paginated response with all bookings")
        void listBookings_returnsAll() throws Exception {
            mockMvc.perform(get("/bookings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(3)))
                    .andExpect(jsonPath("$.total").value(3))
                    .andExpect(jsonPath("$.limit").value(20))
                    .andExpect(jsonPath("$.offset").value(0));
        }

        @Test
        @DisplayName("filters by roomId")
        void listBookings_filterByRoomId() throws Exception {
            mockMvc.perform(get("/bookings").param("roomId", roomId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(3)));

            // Non-existent room
            mockMvc.perform(get("/bookings").param("roomId", UUID.randomUUID().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(0)))
                    .andExpect(jsonPath("$.total").value(0));
        }

        @Test
        @DisplayName("filters by time range")
        void listBookings_filterByTimeRange() throws Exception {
            // Only bookings starting after 10:00 (should get 2)
            mockMvc.perform(get("/bookings")
                            .param("from", nextMonday.plusHours(1).toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(2)));
        }

        @Test
        @DisplayName("respects pagination limit and offset")
        void listBookings_pagination() throws Exception {
            mockMvc.perform(get("/bookings")
                            .param("limit", "2")
                            .param("offset", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.total").value(3))
                    .andExpect(jsonPath("$.limit").value(2))
                    .andExpect(jsonPath("$.offset").value(0));

            mockMvc.perform(get("/bookings")
                            .param("limit", "2")
                            .param("offset", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.total").value(3));
        }

        private void createTestBooking(LocalDateTime start, LocalDateTime end) throws Exception {
            String body = bookingJson(roomId, start, end);
            mockMvc.perform(post("/bookings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));
        }
    }

    // Helper

    private String bookingJson(UUID roomId, LocalDateTime start, LocalDateTime end) throws Exception {
        Map<String, Object> request = Map.of(
                "roomId", roomId.toString(),
                "title", "Team Standup",
                "organizerEmail", "test@example.com",
                "startTime", start.toString(),
                "endTime", end.toString()
        );
        return objectMapper.writeValueAsString(request);
    }
}
