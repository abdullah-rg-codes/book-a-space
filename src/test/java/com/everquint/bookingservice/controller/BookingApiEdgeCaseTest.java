package com.everquint.bookingservice.controller;

import com.everquint.bookingservice.entity.Booking;
import com.everquint.bookingservice.entity.BookingStatus;
import com.everquint.bookingservice.entity.Room;
import com.everquint.bookingservice.repository.BookingRepository;
import com.everquint.bookingservice.repository.IdempotencyRepository;
import com.everquint.bookingservice.repository.RoomRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Exhaustive edge-case coverage for the Bookings API:
 * POST /bookings, GET /bookings, POST /bookings/{id}/cancel.
 * Complements the existing BookingController / BookingCancellation tests with
 * boundary values, half-open overlap edges, field-level validation, pagination
 * guards, and cancellation edges.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BookingApiEdgeCaseTest {

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

    private Room room;
    private UUID roomId;
    private LocalDateTime nextMonday;

    @BeforeEach
    void setUp() {
        idempotencyRepository.deleteAll();
        bookingRepository.deleteAll();
        roomRepository.deleteAll();

        room = new Room();
        room.setName("Edge Room");
        room.setCapacity(10);
        room.setFloor(1);
        room.setAmenities(List.of("projector"));
        room = roomRepository.save(room);
        roomId = room.getId();

        LocalDateTime now = LocalDateTime.now();
        int daysUntilMonday = (8 - now.getDayOfWeek().getValue()) % 7;
        if (daysUntilMonday == 0) daysUntilMonday = 7;
        nextMonday = now.plusDays(daysUntilMonday).withHour(9).withMinute(0).withSecond(0).withNano(0);
    }

    @Nested
    @DisplayName("POST /bookings - Create Booking edge cases")
    class CreateBookingEdgeCases {

        @Test
        @DisplayName("returns 400 when startTime equals endTime")
        void startEqualsEnd_returns400() throws Exception {
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(validBookingJson(nextMonday, nextMonday)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsString("startTime must be before endTime")));
        }

        @Test
        @DisplayName("accepts a booking of exactly 15 minutes (min duration boundary)")
        void durationExactly15Min_returns201() throws Exception {
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(validBookingJson(nextMonday, nextMonday.plusMinutes(15))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("confirmed"));
        }

        @Test
        @DisplayName("accepts a booking of exactly 4 hours (max duration boundary)")
        void durationExactly4Hours_returns201() throws Exception {
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(validBookingJson(nextMonday, nextMonday.plusHours(4))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("confirmed"));
        }

        @Test
        @DisplayName("accepts a booking that starts exactly at 08:00 (open boundary)")
        void startExactly0800_returns201() throws Exception {
            LocalDateTime start = nextMonday.withHour(8).withMinute(0);
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(validBookingJson(start, start.plusHours(1))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("accepts a booking that ends exactly at 20:00 (close boundary)")
        void endExactly2000_returns201() throws Exception {
            LocalDateTime start = nextMonday.withHour(18).withMinute(0);
            LocalDateTime end = nextMonday.withHour(20).withMinute(0);
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(validBookingJson(start, end)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("allows back-to-back bookings (half-open interval, no conflict)")
        void backToBack_bothSucceed() throws Exception {
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(validBookingJson(nextMonday, nextMonday.plusHours(1))))
                    .andExpect(status().isCreated());

            // Starts exactly when the previous one ends -> must NOT conflict
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(validBookingJson(nextMonday.plusHours(1), nextMonday.plusHours(2))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("returns 409 for an identical time slot in the same room")
        void identicalSlot_returns409() throws Exception {
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(validBookingJson(nextMonday, nextMonday.plusHours(1))))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(validBookingJson(nextMonday, nextMonday.plusHours(1))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("ConflictError"));
        }

        @Test
        @DisplayName("returns 409 when a new booking encloses an existing one")
        void enclosingOverlap_returns409() throws Exception {
            // Existing: 10:00 - 11:00
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(validBookingJson(nextMonday.plusHours(1), nextMonday.plusHours(2))))
                    .andExpect(status().isCreated());

            // New: 09:30 - 11:30 fully encloses the existing booking
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(validBookingJson(nextMonday.plusMinutes(30), nextMonday.plusMinutes(150))))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("allows overlapping bookings in different rooms")
        void overlapDifferentRoom_allowed() throws Exception {
            Room other = new Room();
            other.setName("Other Room");
            other.setCapacity(5);
            other.setFloor(1);
            other.setAmenities(List.of());
            other = roomRepository.save(other);

            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(validBookingJson(nextMonday, nextMonday.plusHours(1))))
                    .andExpect(status().isCreated());

            // Same time, different room -> allowed
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(bookingJson(other.getId().toString(), nextMonday, nextMonday.plusHours(1))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("returns 400 when roomId is missing")
        void missingRoomId_returns400() throws Exception {
            Map<String, Object> m = baseBooking(nextMonday, nextMonday.plusHours(1));
            m.remove("roomId");
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content(json(m)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsStringIgnoringCase("roomId")));
        }

        @Test
        @DisplayName("returns 400 when roomId is an empty string")
        void roomIdEmpty_returns400() throws Exception {
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(bookingJson("", nextMonday, nextMonday.plusHours(1))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsString("Invalid roomId format")));
        }

        @Test
        @DisplayName("returns 400 when roomId is not a valid UUID")
        void roomIdNonUuid_returns400() throws Exception {
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(bookingJson("not-a-uuid", nextMonday, nextMonday.plusHours(1))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsString("Invalid roomId format")));
        }

        @Test
        @DisplayName("returns 400 when title is missing")
        void missingTitle_returns400() throws Exception {
            Map<String, Object> m = baseBooking(nextMonday, nextMonday.plusHours(1));
            m.remove("title");
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content(json(m)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"));
        }

        @Test
        @DisplayName("returns 400 when title is blank")
        void blankTitle_returns400() throws Exception {
            Map<String, Object> m = baseBooking(nextMonday, nextMonday.plusHours(1));
            m.put("title", "   ");
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content(json(m)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"));
        }

        @Test
        @DisplayName("returns 400 when startTime is missing")
        void missingStartTime_returns400() throws Exception {
            Map<String, Object> m = baseBooking(nextMonday, nextMonday.plusHours(1));
            m.remove("startTime");
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content(json(m)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"));
        }

        @Test
        @DisplayName("returns 400 when endTime is missing")
        void missingEndTime_returns400() throws Exception {
            Map<String, Object> m = baseBooking(nextMonday, nextMonday.plusHours(1));
            m.remove("endTime");
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content(json(m)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"));
        }

        @Test
        @DisplayName("returns 400 when organizerEmail is missing")
        void missingEmail_returns400() throws Exception {
            Map<String, Object> m = baseBooking(nextMonday, nextMonday.plusHours(1));
            m.remove("organizerEmail");
            mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content(json(m)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"));
        }
    }

    @Nested
    @DisplayName("GET /bookings - List Bookings edge cases")
    class ListBookingsEdgeCases {

        @BeforeEach
        void seedBookings() {
            saveBooking(nextMonday, nextMonday.plusHours(1), BookingStatus.CONFIRMED);              // 09:00-10:00
            saveBooking(nextMonday.plusHours(2), nextMonday.plusHours(3), BookingStatus.CONFIRMED); // 11:00-12:00
            saveBooking(nextMonday.plusHours(4), nextMonday.plusHours(5), BookingStatus.CANCELLED); // 13:00-14:00
        }

        @Test
        @DisplayName("list includes cancelled bookings (no status filter applied)")
        void listIncludesCancelled() throws Exception {
            mockMvc.perform(get("/bookings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(3)))
                    .andExpect(jsonPath("$.total").value(3))
                    .andExpect(jsonPath("$.items[?(@.status == 'cancelled')]", hasSize(1)));
        }

        @Test
        @DisplayName("filters by 'to' (bookings starting before the boundary)")
        void filterByTo() throws Exception {
            mockMvc.perform(get("/bookings").param("to", nextMonday.plusHours(3).toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(2)));
        }

        @Test
        @DisplayName("filters by a combined from/to window")
        void filterByFromAndTo() throws Exception {
            mockMvc.perform(get("/bookings")
                            .param("from", nextMonday.plusMinutes(90).toString())   // 10:30
                            .param("to", nextMonday.plusMinutes(210).toString()))   // 12:30
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)));
        }

        @Test
        @DisplayName("returns 400 (not 500) when limit is 0")
        void limitZero_returns400() throws Exception {
            mockMvc.perform(get("/bookings").param("limit", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsString("limit must be at least 1")));
        }

        @Test
        @DisplayName("returns 400 (not 500) when limit is negative")
        void negativeLimit_returns400() throws Exception {
            mockMvc.perform(get("/bookings").param("limit", "-3"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsString("limit must be at least 1")));
        }

        @Test
        @DisplayName("returns 400 (not 500) when offset is negative")
        void negativeOffset_returns400() throws Exception {
            mockMvc.perform(get("/bookings").param("offset", "-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsString("offset must not be negative")));
        }

        @Test
        @DisplayName("returns empty items but correct total when offset exceeds total")
        void offsetBeyondTotal_returnsEmpty() throws Exception {
            mockMvc.perform(get("/bookings").param("limit", "20").param("offset", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(0)))
                    .andExpect(jsonPath("$.total").value(3));
        }

        @Test
        @DisplayName("returns all items when limit exceeds total")
        void limitLargerThanTotal_returnsAll() throws Exception {
            mockMvc.perform(get("/bookings").param("limit", "500"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(3)))
                    .andExpect(jsonPath("$.total").value(3))
                    .andExpect(jsonPath("$.limit").value(500));
        }

        @Test
        @DisplayName("returns 400 when roomId filter is not a valid UUID")
        void invalidRoomIdFilter_returns400() throws Exception {
            mockMvc.perform(get("/bookings").param("roomId", "not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsString("Invalid roomId format")));
        }

        @Test
        @DisplayName("returns an empty page when there are no bookings")
        void emptyDatabase_returnsEmptyPage() throws Exception {
            bookingRepository.deleteAll();

            mockMvc.perform(get("/bookings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(0)))
                    .andExpect(jsonPath("$.total").value(0));
        }

        @Test
        @DisplayName("results are ordered by startTime ascending regardless of insertion order")
        void resultsSortedByStartTimeAscending() throws Exception {
            bookingRepository.deleteAll();
            // Insert deliberately out of order: 13:00, then 09:00, then 11:00
            saveBooking(nextMonday.plusHours(4), nextMonday.plusHours(5), BookingStatus.CONFIRMED);
            saveBooking(nextMonday, nextMonday.plusHours(1), BookingStatus.CONFIRMED);
            saveBooking(nextMonday.plusHours(2), nextMonday.plusHours(3), BookingStatus.CONFIRMED);

            MvcResult result = mockMvc.perform(get("/bookings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(3)))
                    .andReturn();

            JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
            LocalDateTime t0 = LocalDateTime.parse(items.get(0).get("startTime").asText());
            LocalDateTime t1 = LocalDateTime.parse(items.get(1).get("startTime").asText());
            LocalDateTime t2 = LocalDateTime.parse(items.get(2).get("startTime").asText());

            assertThat(t0).isBefore(t1);
            assertThat(t1).isBefore(t2);
        }

        private void saveBooking(LocalDateTime start, LocalDateTime end, BookingStatus status) {
            Booking b = new Booking();
            b.setRoom(room);
            b.setTitle("Seed Booking");
            b.setOrganizerEmail("test@example.com");
            b.setStartTime(start);
            b.setEndTime(end);
            b.setStatus(status);
            bookingRepository.save(b);
        }
    }

    @Nested
    @DisplayName("POST /bookings/{id}/cancel - Cancellation edge cases")
    class CancelBookingEdgeCases {

        @Test
        @DisplayName("returns 400 when the booking id is not a valid UUID")
        void invalidBookingId_returns400() throws Exception {
            mockMvc.perform(post("/bookings/not-a-uuid/cancel"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsString("Invalid bookingId format")));
        }

        @Test
        @DisplayName("cancelling twice is idempotent (second call is a no-op)")
        void doubleCancel_isIdempotent() throws Exception {
            Booking booking = createBookingInDb(LocalDateTime.now().plusHours(48));

            mockMvc.perform(post("/bookings/" + booking.getId() + "/cancel"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("cancelled"));

            mockMvc.perform(post("/bookings/" + booking.getId() + "/cancel"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("cancelled"));
        }

        @Test
        @DisplayName("cancels successfully when ~90 minutes before start (grace boundary)")
        void cancelWellBeforeStart_succeeds() throws Exception {
            Booking booking = createBookingInDb(LocalDateTime.now().plusMinutes(90));

            mockMvc.perform(post("/bookings/" + booking.getId() + "/cancel"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("cancelled"));
        }

        private Booking createBookingInDb(LocalDateTime start) {
            Booking b = new Booking();
            b.setRoom(room);
            b.setTitle("Cancel Target");
            b.setOrganizerEmail("test@example.com");
            b.setStartTime(start);
            b.setEndTime(start.plusHours(1));
            b.setStatus(BookingStatus.CONFIRMED);
            return bookingRepository.save(b);
        }
    }

    // Helpers

    private String json(Map<String, Object> map) throws Exception {
        return objectMapper.writeValueAsString(map);
    }

    private Map<String, Object> baseBooking(LocalDateTime start, LocalDateTime end) {
        Map<String, Object> m = new HashMap<>();
        m.put("roomId", roomId.toString());
        m.put("title", "Team Standup");
        m.put("organizerEmail", "test@example.com");
        m.put("startTime", start.toString());
        m.put("endTime", end.toString());
        return m;
    }

    private String validBookingJson(LocalDateTime start, LocalDateTime end) throws Exception {
        return json(baseBooking(start, end));
    }

    private String bookingJson(String roomIdValue, LocalDateTime start, LocalDateTime end) throws Exception {
        Map<String, Object> m = baseBooking(start, end);
        m.put("roomId", roomIdValue);
        return json(m);
    }
}
