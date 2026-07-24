package com.everquint.bookingservice.controller;

import com.everquint.bookingservice.repository.BookingRepository;
import com.everquint.bookingservice.repository.IdempotencyRepository;
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

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class RoomControllerIntegrationTest {

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

    @BeforeEach
    void setUp() {
        idempotencyRepository.deleteAll();
        bookingRepository.deleteAll();
        roomRepository.deleteAll();
    }

    // POST /rooms

    @Nested
    @DisplayName("POST /rooms - Create Room")
    class CreateRoom {

        @Test
        @DisplayName("returns 201 with room object including generated id")
        void createRoom_success() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "name", "Board Room A",
                    "capacity", 10,
                    "floor", 2,
                    "amenities", List.of("projector", "whiteboard")
            ));

            mockMvc.perform(post("/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.name").value("Board Room A"))
                    .andExpect(jsonPath("$.capacity").value(10))
                    .andExpect(jsonPath("$.floor").value(2))
                    .andExpect(jsonPath("$.amenities", hasSize(2)))
                    .andExpect(jsonPath("$.amenities", containsInAnyOrder("projector", "whiteboard")));
        }

        @Test
        @DisplayName("returns 409 when room name already exists")
        void createRoom_duplicateName_returns409() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "name", "Room X",
                    "capacity", 5,
                    "floor", 1,
                    "amenities", List.of()
            ));

            // First creation — succeeds
            mockMvc.perform(post("/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());

            // Duplicate — fails with 409
            mockMvc.perform(post("/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("ConflictError"))
                    .andExpect(jsonPath("$.message", containsString("Room X")));
        }

        @Test
        @DisplayName("returns 409 for duplicate name (case-insensitive)")
        void createRoom_duplicateNameCaseInsensitive_returns409() throws Exception {
            // Create "Meeting Room"
            mockMvc.perform(post("/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "name", "Meeting Room",
                                    "capacity", 5,
                                    "floor", 1,
                                    "amenities", List.of()
                            ))))
                    .andExpect(status().isCreated());

            // Try "meeting room" (lowercase) — should conflict
            mockMvc.perform(post("/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "name", "meeting room",
                                    "capacity", 8,
                                    "floor", 2,
                                    "amenities", List.of()
                            ))))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("returns 400 when name is missing")
        void createRoom_missingName_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "capacity", 5,
                    "floor", 1,
                    "amenities", List.of()
            ));

            mockMvc.perform(post("/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"));
        }

        @Test
        @DisplayName("returns 400 when capacity is zero")
        void createRoom_zeroCapacity_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "name", "Tiny Room",
                    "capacity", 0,
                    "floor", 1,
                    "amenities", List.of()
            ));

            mockMvc.perform(post("/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsString("capacity")));
        }

        @Test
        @DisplayName("returns 400 when capacity is negative")
        void createRoom_negativeCapacity_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "name", "Bad Room",
                    "capacity", -3,
                    "floor", 1,
                    "amenities", List.of()
            ));

            mockMvc.perform(post("/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // GET /rooms

    @Nested
    @DisplayName("GET /rooms - List Rooms")
    class ListRooms {

        @BeforeEach
        void seedRooms() throws Exception {
            createTestRoom("Small Room", 4, 1, List.of("whiteboard"));
            createTestRoom("Medium Room", 10, 2, List.of("projector", "whiteboard"));
            createTestRoom("Large Room", 20, 3, List.of("projector", "video_conferencing", "whiteboard"));
        }

        @Test
        @DisplayName("returns all rooms when no filters applied")
        void listRooms_noFilters_returnsAll() throws Exception {
            mockMvc.perform(get("/rooms"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(3)));
        }

        @Test
        @DisplayName("filters rooms by minCapacity")
        void listRooms_filterByMinCapacity() throws Exception {
            mockMvc.perform(get("/rooms").param("minCapacity", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[*].name", hasItems("Medium Room", "Large Room")));
        }

        @Test
        @DisplayName("filters rooms by amenity")
        void listRooms_filterByAmenity() throws Exception {
            mockMvc.perform(get("/rooms").param("amenity", "video_conferencing"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name").value("Large Room"));
        }

        @Test
        @DisplayName("filters rooms by both minCapacity and amenity")
        void listRooms_filterByBoth() throws Exception {
            mockMvc.perform(get("/rooms")
                            .param("minCapacity", "8")
                            .param("amenity", "projector"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)));
        }

        @Test
        @DisplayName("returns empty array when no rooms match")
        void listRooms_noMatch_returnsEmpty() throws Exception {
            mockMvc.perform(get("/rooms").param("minCapacity", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("amenity filter is case-insensitive")
        void listRooms_amenityFilterCaseInsensitive() throws Exception {
            mockMvc.perform(get("/rooms").param("amenity", "PROJECTOR"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)));
        }

        private void createTestRoom(String name, int capacity, int floor, List<String> amenities) throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "name", name,
                    "capacity", capacity,
                    "floor", floor,
                    "amenities", amenities
            ));
            mockMvc.perform(post("/rooms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));
        }
    }
}
