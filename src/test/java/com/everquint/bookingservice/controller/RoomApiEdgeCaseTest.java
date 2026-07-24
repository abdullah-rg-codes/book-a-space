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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Exhaustive edge-case coverage for the Rooms API (POST /rooms, GET /rooms).
 * Focuses on validation boundaries, defaulting behaviour, and filter edges
 * not covered by {@link RoomControllerIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoomApiEdgeCaseTest {

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

    @Nested
    @DisplayName("POST /rooms - Create Room edge cases")
    class CreateRoomEdgeCases {

        @Test
        @DisplayName("accepts capacity of exactly 1 (lower boundary)")
        void capacityExactlyOne_accepted() throws Exception {
            String body = json(Map.of("name", "Solo Booth", "capacity", 1, "floor", 1, "amenities", List.of()));

            mockMvc.perform(post("/rooms").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.capacity").value(1));
        }

        @Test
        @DisplayName("accepts floor of 0 (no lower bound on floor)")
        void floorZero_accepted() throws Exception {
            String body = json(Map.of("name", "Ground Room", "capacity", 5, "floor", 0, "amenities", List.of()));

            mockMvc.perform(post("/rooms").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.floor").value(0));
        }

        @Test
        @DisplayName("accepts a negative floor (e.g. basement level)")
        void floorNegative_accepted() throws Exception {
            String body = json(Map.of("name", "Basement B2", "capacity", 5, "floor", -2, "amenities", List.of()));

            mockMvc.perform(post("/rooms").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.floor").value(-2));
        }

        @Test
        @DisplayName("returns 400 when floor is missing (null)")
        void floorMissing_returns400() throws Exception {
            // floor omitted -> null -> @NotNull violation
            String body = json(Map.of("name", "No Floor Room", "capacity", 5, "amenities", List.of()));

            mockMvc.perform(post("/rooms").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsStringIgnoringCase("floor")));
        }

        @Test
        @DisplayName("returns 400 when capacity is missing (primitive defaults to 0)")
        void capacityMissing_returns400() throws Exception {
            // capacity omitted -> primitive int defaults to 0 -> @Min(1) violation
            String body = json(Map.of("name", "No Capacity Room", "floor", 1, "amenities", List.of()));

            mockMvc.perform(post("/rooms").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsStringIgnoringCase("capacity")));
        }

        @Test
        @DisplayName("defaults amenities to an empty list when omitted")
        void amenitiesOmitted_defaultsToEmpty() throws Exception {
            String body = json(Map.of("name", "Bare Room", "capacity", 4, "floor", 1));

            mockMvc.perform(post("/rooms").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.amenities", hasSize(0)));
        }

        @Test
        @DisplayName("returns 400 when name is whitespace only")
        void nameWhitespaceOnly_returns400() throws Exception {
            String body = json(Map.of("name", "   ", "capacity", 4, "floor", 1, "amenities", List.of()));

            mockMvc.perform(post("/rooms").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"));
        }

        @Test
        @DisplayName("returns 400 when name is an empty string")
        void nameEmpty_returns400() throws Exception {
            String body = json(Map.of("name", "", "capacity", 4, "floor", 1, "amenities", List.of()));

            mockMvc.perform(post("/rooms").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"));
        }

        @Test
        @DisplayName("accepts a very large capacity (Integer.MAX_VALUE)")
        void hugeCapacity_accepted() throws Exception {
            String body = json(Map.of("name", "Stadium", "capacity", Integer.MAX_VALUE, "floor", 1, "amenities", List.of()));

            mockMvc.perform(post("/rooms").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.capacity").value(Integer.MAX_VALUE));
        }

        @Test
        @DisplayName("accepts a unicode / emoji room name")
        void unicodeName_accepted() throws Exception {
            String body = json(Map.of("name", "\u4f1a\u8b70\u5ba4 \uD83D\uDE80", "capacity", 8, "floor", 2, "amenities", List.of("projector")));

            mockMvc.perform(post("/rooms").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("\u4f1a\u8b70\u5ba4 \uD83D\uDE80"));
        }

        @Test
        @DisplayName("preserves duplicate amenity entries as provided")
        void duplicateAmenities_preserved() throws Exception {
            String body = json(Map.of("name", "Dup Amenity Room", "capacity", 6, "floor", 1,
                    "amenities", List.of("projector", "projector", "whiteboard")));

            mockMvc.perform(post("/rooms").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.amenities", hasSize(3)));
        }
    }

    @Nested
    @DisplayName("GET /rooms - List Rooms edge cases")
    class ListRoomsEdgeCases {

        @BeforeEach
        void seed() throws Exception {
            createRoom("Alpha", 4, 1, List.of("whiteboard"));
            createRoom("Beta", 10, 2, List.of("projector", "whiteboard"));
            createRoom("Gamma", 20, 3, List.of("projector", "video"));
        }

        @Test
        @DisplayName("minCapacity filter is inclusive of the boundary value")
        void minCapacityBoundaryInclusive() throws Exception {
            // Beta has capacity exactly 10 -> must be included when minCapacity=10
            mockMvc.perform(get("/rooms").param("minCapacity", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[*].name", hasItem("Beta")));
        }

        @Test
        @DisplayName("minCapacity of 0 returns all rooms")
        void minCapacityZero_returnsAll() throws Exception {
            mockMvc.perform(get("/rooms").param("minCapacity", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(3)));
        }

        @Test
        @DisplayName("negative minCapacity returns all rooms (no crash)")
        void negativeMinCapacity_returnsAll() throws Exception {
            mockMvc.perform(get("/rooms").param("minCapacity", "-5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(3)));
        }

        @Test
        @DisplayName("filtering by an amenity that no room has returns an empty array")
        void amenityNotPresent_returnsEmpty() throws Exception {
            mockMvc.perform(get("/rooms").param("amenity", "helipad"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("returns an empty array when there are no rooms at all")
        void noRooms_returnsEmptyArray() throws Exception {
            roomRepository.deleteAll();

            mockMvc.perform(get("/rooms"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    // Helpers

    private String json(Map<String, Object> map) throws Exception {
        return objectMapper.writeValueAsString(new HashMap<>(map));
    }

    private void createRoom(String name, int capacity, int floor, List<String> amenities) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", name, "capacity", capacity, "floor", floor, "amenities", amenities));
        mockMvc.perform(post("/rooms").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }
}
