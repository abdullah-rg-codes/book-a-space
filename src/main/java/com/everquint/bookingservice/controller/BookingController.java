package com.everquint.bookingservice.controller;

import com.everquint.bookingservice.dto.BookingResponse;
import com.everquint.bookingservice.dto.CreateBookingRequest;
import com.everquint.bookingservice.dto.PaginatedResponse;
import com.everquint.bookingservice.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * POST /bookings — Create a new booking.
     * Business rules validated in the service layer.
     * Returns 201 Created with booking (status: "CONFIRMED").
     */
    @PostMapping
    public ResponseEntity<BookingResponse> createBooking(
            @Valid @RequestBody CreateBookingRequest request) {

        BookingResponse response = bookingService.createBooking(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /bookings — List bookings with optional filters and pagination.
     * @param roomId  Optional — filter by room
     * @param from    Optional — ISO-8601, bookings ending after this time
     * @param to      Optional — ISO-8601, bookings starting before this time
     * @param limit   Page size (default 20)
     * @param offset  Records to skip (default 0)
     */
    @GetMapping
    public ResponseEntity<PaginatedResponse<BookingResponse>> listBookings(
            @RequestParam(required = false) String roomId,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        PaginatedResponse<BookingResponse> response =
                bookingService.listBookings(roomId, from, to, limit, offset);
        return ResponseEntity.ok(response);
    }
}
