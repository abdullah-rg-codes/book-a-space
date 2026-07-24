package com.everquint.bookingservice.controller;

import com.everquint.bookingservice.entity.Room;
import com.everquint.bookingservice.repository.BookingRepository;
import com.everquint.bookingservice.repository.IdempotencyRepository;
import com.everquint.bookingservice.repository.RoomRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyIntegrationTest {

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

    private UUID roomId;
    private LocalDateTime nextMonday;

    @BeforeEach
    void setUp() {
        idempotencyRepository.deleteAll();
        bookingRepository.deleteAll();
        roomRepository.deleteAll();

        Room room = new Room();
        room.setName("Idempotency Test Room");
        room.setCapacity(10);
        room.setFloor(1);
        room.setAmenities(List.of());
        room = roomRepository.save(room);
        roomId = room.getId();

        // Calculate next Monday at 09:00
        LocalDateTime now = LocalDateTime.now();
        int daysUntilMonday = (8 - now.getDayOfWeek().getValue()) % 7;
        if (daysUntilMonday == 0) daysUntilMonday = 7;
        nextMonday = now.plusDays(daysUntilMonday).withHour(9).withMinute(0).withSecond(0).withNano(0);
    }

    @Test
    @DisplayName("same idempotency key returns same booking (no duplicate created)")
    void sameKey_returnsSameBooking() throws Exception {
        String body = bookingJson(nextMonday, nextMonday.plusHours(1));
        String key = "unique-key-001";

        // First request — creates booking
        MvcResult first = mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String firstId = extractId(first);

        // Second request with same key — returns same booking
        MvcResult second = mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String secondId = extractId(second);

        assertThat(secondId).isEqualTo(firstId);

        // Verify only 1 booking exists in DB
        assertThat(bookingRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("different idempotency keys create separate bookings")
    void differentKeys_createSeparateBookings() throws Exception {
        String body1 = bookingJson(nextMonday, nextMonday.plusHours(1));
        String body2 = bookingJson(nextMonday.plusHours(2), nextMonday.plusHours(3));

        // First booking with key-A
        MvcResult first = mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-A")
                        .content(body1))
                .andExpect(status().isCreated())
                .andReturn();

        // Second booking with key-B (different time slot to avoid overlap)
        MvcResult second = mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-B")
                        .content(body2))
                .andExpect(status().isCreated())
                .andReturn();

        String firstId = extractId(first);
        String secondId = extractId(second);

        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(bookingRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("same key but different organizer creates separate bookings")
    void sameKey_differentOrganizer_createsSeparateBookings() throws Exception {
        String key = "shared-key-123";

        // Organizer A
        String bodyA = objectMapper.writeValueAsString(Map.of(
                "roomId", roomId.toString(),
                "title", "Meeting A",
                "organizerEmail", "alice@example.com",
                "startTime", nextMonday.toString(),
                "endTime", nextMonday.plusHours(1).toString()
        ));

        MvcResult first = mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(bodyA))
                .andExpect(status().isCreated())
                .andReturn();

        // Organizer B — same key but different organizer (different time to avoid overlap)
        String bodyB = objectMapper.writeValueAsString(Map.of(
                "roomId", roomId.toString(),
                "title", "Meeting B",
                "organizerEmail", "bob@example.com",
                "startTime", nextMonday.plusHours(2).toString(),
                "endTime", nextMonday.plusHours(3).toString()
        ));

        MvcResult second = mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(bodyB))
                .andExpect(status().isCreated())
                .andReturn();

        String firstId = extractId(first);
        String secondId = extractId(second);

        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(bookingRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("no idempotency key header — creates booking normally")
    void noKey_createsNormally() throws Exception {
        String body = bookingJson(nextMonday, nextMonday.plusHours(1));

        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("confirmed"));

        assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(idempotencyRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("idempotency record persists in database")
    void idempotencyRecord_persistedInDb() throws Exception {
        String body = bookingJson(nextMonday, nextMonday.plusHours(1));
        String key = "persist-test-key";

        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(body))
                .andExpect(status().isCreated());

        assertThat(idempotencyRepository.count()).isEqualTo(1);
        assertThat(idempotencyRepository
                .findByIdempotencyKeyAndOrganizerEmail(key, "test@example.com"))
                .isPresent();
    }

    // Helpers

    private String bookingJson(LocalDateTime start, LocalDateTime end) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "roomId", roomId.toString(),
                "title", "Team Standup",
                "organizerEmail", "test@example.com",
                "startTime", start.toString(),
                "endTime", end.toString()
        ));
    }

    private String extractId(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }
}
