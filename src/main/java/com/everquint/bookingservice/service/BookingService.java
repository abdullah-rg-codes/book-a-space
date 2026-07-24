package com.everquint.bookingservice.service;

import com.everquint.bookingservice.dto.BookingResponse;
import com.everquint.bookingservice.dto.CreateBookingRequest;
import com.everquint.bookingservice.dto.PaginatedResponse;
import com.everquint.bookingservice.entity.Booking;
import com.everquint.bookingservice.entity.BookingStatus;
import com.everquint.bookingservice.entity.Room;
import com.everquint.bookingservice.exception.BookingConflictException;
import com.everquint.bookingservice.exception.BookingNotFoundException;
import com.everquint.bookingservice.exception.RoomNotFoundException;
import com.everquint.bookingservice.exception.ValidationException;
import com.everquint.bookingservice.repository.BookingRepository;
import com.everquint.bookingservice.repository.RoomRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Service
public class BookingService {

    private static final LocalTime BUSINESS_START = LocalTime.of(8, 0);
    private static final LocalTime BUSINESS_END = LocalTime.of(20, 0);
    private static final Duration MIN_DURATION = Duration.ofMinutes(15);
    private static final Duration MAX_DURATION = Duration.ofHours(4);

    private final BookingRepository bookingRepository;
    private final RoomRepository roomRepository;

    public BookingService(BookingRepository bookingRepository, RoomRepository roomRepository) {
        this.bookingRepository = bookingRepository;
        this.roomRepository = roomRepository;
    }

    /**
     * Creates a new booking after enforcing all business rules:
     * 1. Room must exist
     * 2. startTime < endTime
     * 3. Duration between 15 min and 4 hours
     * 4. Must fall within Mon-Fri, 08:00-20:00
     * 5. No overlapping confirmed bookings for the same room
     */
    @Transactional
    public BookingResponse createBooking(CreateBookingRequest request) {
        // 1. Validate room exists
        UUID roomId = parseRoomId(request.roomId());
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new RoomNotFoundException(request.roomId()));

        // 2. startTime must be before endTime
        if (!request.startTime().isBefore(request.endTime())) {
            throw new ValidationException("startTime must be before endTime");
        }

        // 3. Validate duration (15 min to 4 hours)
        validateDuration(request.startTime(), request.endTime());

        // 4. Validate working hours (Mon-Fri, 08:00-20:00)
        validateWorkingHours(request.startTime(), request.endTime());

        // 5. Check for overlapping confirmed bookings
        List<Booking> overlapping = bookingRepository.findOverlappingBookings(
                roomId, request.startTime(), request.endTime(), BookingStatus.CONFIRMED);

        if (!overlapping.isEmpty()) {
            throw new BookingConflictException(
                    "Room is already booked during the requested time slot");
        }

        // All rules passed — create the booking
        Booking booking = new Booking();
        booking.setRoom(room);
        booking.setTitle(request.title());
        booking.setOrganizerEmail(request.organizerEmail());
        booking.setStartTime(request.startTime());
        booking.setEndTime(request.endTime());
        booking.setStatus(BookingStatus.CONFIRMED);

        Booking saved = bookingRepository.save(booking);
        return BookingResponse.from(saved);
    }

    /**
     * Lists bookings with optional filters and pagination.
     *
     * @param roomId  Optional - filter by specific room
     * @param from    Optional - filter bookings that end after this time
     * @param to      Optional - filter bookings that start before this time
     * @param limit   Page size (defaults to 20)
     * @param offset  Number of records to skip (defaults to 0)
     */
    @Transactional(readOnly = true)
    public PaginatedResponse<BookingResponse> listBookings(
            String roomId, LocalDateTime from, LocalDateTime to,
            int limit, int offset) {

        // Convert offset/limit to Spring's page-based pagination
        // offset = page * size, so page = offset / limit
        int page = offset / limit;
        Pageable pageable = PageRequest.of(page, limit, Sort.by("startTime").ascending());

        UUID parsedRoomId = roomId != null ? parseRoomId(roomId) : null;

        Page<Booking> bookingPage = bookingRepository.findWithFilters(
                parsedRoomId, from, to, pageable);

        List<BookingResponse> items = bookingPage.getContent().stream()
                .map(BookingResponse::from)
                .toList();

        return new PaginatedResponse<>(
                items,
                bookingPage.getTotalElements(),
                limit,
                offset
        );
    }

    /**
     * Cancels a booking with grace period enforcement.
     *
     * Rules:
     * 1. Booking must exist (404 if not found)
     * 2. Already cancelled → no-op, return existing cancelled booking
     * 3. Can only cancel up to 1 hour before startTime (400 if too late)
     */
    @Transactional
    public BookingResponse cancelBooking(String bookingId) {
        UUID id = parseBookingId(bookingId);

        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        // Already cancelled — no-op, return as-is
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return BookingResponse.from(booking);
        }

        // Grace period: must cancel at least 1 hour before start time
        LocalDateTime cancellationDeadline = booking.getStartTime().minusHours(1);
        if (!LocalDateTime.now().isBefore(cancellationDeadline)) {
            throw new ValidationException(
                    "Booking can only be cancelled up to 1 hour before start time");
        }

        // Cancel the booking
        booking.setStatus(BookingStatus.CANCELLED);
        Booking saved = bookingRepository.save(booking);
        return BookingResponse.from(saved);
    }

    // Private validation methods

    /**
     * Validates that the booking duration is between 15 minutes and 4 hours.
     */
    private void validateDuration(LocalDateTime start, LocalDateTime end) {
        Duration duration = Duration.between(start, end);

        if (duration.compareTo(MIN_DURATION) < 0) {
            throw new ValidationException(
                    "Booking duration must be at least 15 minutes");
        }
        if (duration.compareTo(MAX_DURATION) > 0) {
            throw new ValidationException(
                    "Booking duration must not exceed 4 hours");
        }
    }

    /**
     * Validates that the booking falls within business hours:
     * - Monday to Friday only
     * - Start time >= 08:00 and end time <= 20:00
     */
    private void validateWorkingHours(LocalDateTime start, LocalDateTime end) {
        // Check day of week for both start and end
        DayOfWeek startDay = start.getDayOfWeek();
        DayOfWeek endDay = end.getDayOfWeek();

        if (isWeekend(startDay) || isWeekend(endDay)) {
            throw new ValidationException(
                    "Bookings are only allowed Monday to Friday");
        }

        // Check time bounds
        LocalTime startTime = start.toLocalTime();
        LocalTime endTime = end.toLocalTime();

        if (startTime.isBefore(BUSINESS_START) || endTime.isAfter(BUSINESS_END)) {
            throw new ValidationException(
                    "Bookings are only allowed between 08:00 and 20:00");
        }
    }

    private boolean isWeekend(DayOfWeek day) {
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    /**
     * Parses a string roomId into a UUID.
     * roomId can be "string-or-int" — UUID strings.
     */
    private UUID parseRoomId(String roomId) {
        try {
            return UUID.fromString(roomId);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid roomId format: " + roomId);
        }
    }

    /**
     * Parses a string bookingId into a UUID.
     */
    private UUID parseBookingId(String bookingId) {
        try {
            return UUID.fromString(bookingId);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid bookingId format: " + bookingId);
        }
    }
}
