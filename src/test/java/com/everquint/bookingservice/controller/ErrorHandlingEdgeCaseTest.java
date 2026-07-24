package com.everquint.bookingservice.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Cross-cutting error-contract coverage. Verifies that malformed request bodies
 * and bad query-parameter types are always answered with a clean 400 and the
 * consistent { "error", "message" } shape, never an HTTP 500 or a leaked stack
 * trace.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorHandlingEdgeCaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Nested
    @DisplayName("Malformed request bodies")
    class MalformedBodies {

        @Test
        @DisplayName("POST /bookings with broken JSON returns a clean 400")
        void bookingBrokenJson_returns400() throws Exception {
            mockMvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ this is not valid json "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message").exists());
        }

        @Test
        @DisplayName("POST /rooms with broken JSON returns a clean 400")
        void roomBrokenJson_returns400() throws Exception {
            mockMvc.perform(post("/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("}{"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"));
        }

        @Test
        @DisplayName("POST /bookings with an empty body returns a clean 400")
        void bookingEmptyBody_returns400() throws Exception {
            mockMvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"));
        }

        @Test
        @DisplayName("POST /bookings with an unparseable datetime returns a clean 400")
        void bookingBadDatetime_returns400() throws Exception {
            String body = "{\"roomId\":\"" + java.util.UUID.randomUUID()
                    + "\",\"title\":\"X\",\"organizerEmail\":\"a@b.com\","
                    + "\"startTime\":\"not-a-date\",\"endTime\":\"2026-07-27T10:00:00\"}";

            mockMvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"));
        }
    }

    @Nested
    @DisplayName("Query-parameter type mismatches")
    class ParamTypeMismatches {

        @Test
        @DisplayName("GET /bookings with a non-integer limit returns 400 (not 500)")
        void nonIntegerLimit_returns400() throws Exception {
            mockMvc.perform(get("/bookings").param("limit", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsString("limit")));
        }

        @Test
        @DisplayName("GET /bookings with a non-integer offset returns 400 (not 500)")
        void nonIntegerOffset_returns400() throws Exception {
            mockMvc.perform(get("/bookings").param("offset", "xyz"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsString("offset")));
        }

        @Test
        @DisplayName("GET /rooms with a non-integer minCapacity returns 400 (not 500)")
        void nonIntegerMinCapacity_returns400() throws Exception {
            mockMvc.perform(get("/rooms").param("minCapacity", "lots"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsString("minCapacity")));
        }

        @Test
        @DisplayName("GET /reports/room-utilization with an unparseable 'from' returns 400 (not 500)")
        void reportBadFrom_returns400() throws Exception {
            mockMvc.perform(get("/reports/room-utilization")
                            .param("from", "not-a-date")
                            .param("to", "2026-07-27T23:59:00"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsString("from")));
        }

        @Test
        @DisplayName("GET /reports/room-utilization with an unparseable 'to' returns 400 (not 500)")
        void reportBadTo_returns400() throws Exception {
            mockMvc.perform(get("/reports/room-utilization")
                            .param("from", "2026-07-27T00:00:00")
                            .param("to", "nonsense"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ValidationError"))
                    .andExpect(jsonPath("$.message", containsString("to")));
        }
    }
}
