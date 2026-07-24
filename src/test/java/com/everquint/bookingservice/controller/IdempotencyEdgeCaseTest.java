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

/**
 * Edge cases for the Idempotency-Key behaviour that the base
 * IdempotencyIntegrationTest does not cover: replay with a differing body,
 * blank keys, and the interaction between distinct keys and conflict detection.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyEdgeCaseTest {

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
        room.setName("Idempotency Edge Room");
        room.setCapacity(10);
        room.setFloor(1);
        room.setAmenities(List.of());
        room = roomRepository.save(room);
        roomId = room.getId();

        LocalDateTime now = LocalDateTime.now();
        int daysUntilMonday = (8 - now.getDayOfWeek().getValue()) % 7;
        if (daysUntilMonday == 0) daysUntilMonday = 7;
        nextMonday = now.plusDays(daysUntilMonday).withHour(9).withMinute(0).withSecond(0).withNano(0);
    }

    @Test
    @DisplayName("replaying a key returns the ORIGINAL booking even when the request body differs")
    void replayWithDifferentBody_returnsOriginal() throws Exception {
        String key = "replay-key";

        // Original booking: 09:00 - 10:00
        MvcResult first = mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(bookingJson(nextMonday, nextMonday.plusHours(1))))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = extractId(first);

        // Replay same key + organizer but a DIFFERENT time window (11:00 - 12:00)
        MvcResult replay = mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(bookingJson(nextMonday.plusHours(2), nextMonday.plusHours(3))))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode replayNode = objectMapper.readTree(replay.getResponse().getContentAsString());

        // Same booking id returned, original start time preserved, no duplicate created
        assertThat(replayNode.get("id").asText()).isEqualTo(firstId);
        assertThat(replayNode.get("startTime").asText()).startsWith(nextMonday.toLocalDate().toString());
        assertThat(replayNode.get("startTime").asText()).contains("T09:00");
        assertThat(bookingRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a blank Idempotency-Key is ignored and the booking is created normally")
    void blankKey_createsNormally() throws Exception {
        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "   ")
                        .content(bookingJson(nextMonday, nextMonday.plusHours(1))))
                .andExpect(status().isCreated());

        assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(idempotencyRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("distinct keys do NOT bypass conflict detection for the same slot")
    void differentKeys_sameSlot_secondConflicts() throws Exception {
        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-1")
                        .content(bookingJson(nextMonday, nextMonday.plusHours(1))))
                .andExpect(status().isCreated());

        // Different key, same room + same slot -> business conflict still applies
        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-2")
                        .content(bookingJson(nextMonday, nextMonday.plusHours(1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ConflictError"));
    }

    // Helpers

    private String bookingJson(LocalDateTime start, LocalDateTime end) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "roomId", roomId.toString(),
                "title", "Team Standup",
                "organizerEmail", "test@example.com",
                "startTime", start.toString(),
                "endTime", end.toString()));
    }

    private String extractId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
