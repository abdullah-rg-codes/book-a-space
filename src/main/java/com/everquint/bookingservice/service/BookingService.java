package com.everquint.bookingservice.service;

import com.everquint.bookingservice.dto.BookingResponse;
import com.everquint.bookingservice.dto.CreateBookingRequest;
import com.everquint.bookingservice.dto.PaginatedResponse;
import com.everquint.bookingservice.entity.Booking;
import com.everquint.bookingservice.entity.BookingStatus;
import com.everquint.bookingservice.entity.IdempotencyRecord;
import com.everquint.bookingservice.entity.Room;
import com.everquint.bookingservice.exception.BookingConflictException;
import com.everquint.bookingservice.exception.BookingNotFoundException;
import com.everquint.bookingservice.exception.RoomNotFoundException;
import com.everquint.bookingservice.exception.ValidationException;
import com.everquint.bookingservice.repository.BookingRepository;
import com.everquint.bookingservice.repository.IdempotencyRepository;
import com.everquint.bookingservice.repository.RoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.Optional;
import java.util.UUID;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private static final LocalTime BUSINESS_START = LocalTime.of(8, 0);
    private static final LocalTime BUSINESS_END = LocalTime.of(20, 0);
    private static final Duration MIN_DURATION = Duration.ofMinutes(15);
    private static final Duration MAX_DURATION = Duration.ofHours(4);

    private final BookingRepository bookingRepository;
    private final RoomRepository roomRepository;
    private final IdempotencyRepository idempotencyRepository;

    public BookingService(BookingRepository bookingRepository,
                          RoomRepository roomRepository,
                          IdempotencyRepository idempotencyRepository) {
        this.bookingRepository = bookingRepository;
        this.roomRepository = roomRepository;
        this.idempotencyRepository = idempotencyRepository;
    }

    /**
     * Creates a new booking after enforcing all business rules:
     * 1. Room must exist
     * 2. startTime < endTime
     * 3. Duration between 15 min and 4 hours
     * 4. Must fall within Mon-Fri, 08:00-20:00
     * 5. No overlapping confirmed bookings for the same room
     *
     * If an idempotencyKey is provided:
     * - Returns the existing booking if the key was already used by this organizer
     * - Otherwise creates the booking and stores the idempotency record
     * - Concurrent duplicate requests are handled via DB unique constraint + retry
     */
    @Transactional
    public BookingResponse createBooking(CreateBookingRequest request, String idempotencyKey) {
        log.debug("createBooking() called with roomId='{}', title='{}', organizer='{}', start={}, end={}, idempotencyKey='{}'",
                request.roomId(), request.title(), request.organizerEmail(),
                request.startTime(), request.endTime(), idempotencyKey);

        // Idempotency check: if key provided, look for existing record
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyRecord> existing = idempotencyRepository
                    .findByIdempotencyKeyAndOrganizerEmail(idempotencyKey, request.organizerEmail());

            if (existing.isPresent()) {
                Booking existingBooking = existing.get().getBooking();
                log.info("Idempotent request detected: key='{}', returning existing bookingId={}",
                        idempotencyKey, existingBooking.getId());
                return BookingResponse.from(existingBooking);
            }
        }

        // 1. Validate room exists
        UUID roomId = parseRoomId(request.roomId());
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> {
                    log.warn("Room not found: roomId='{}'", request.roomId());
                    return new RoomNotFoundException(request.roomId());
                });

        // 2. startTime must be before endTime
        if (!request.startTime().isBefore(request.endTime())) {
            log.warn("Validation failed: startTime={} is not before endTime={}",
                    request.startTime(), request.endTime());
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
            log.warn("Booking conflict: roomId='{}', requested={} to {}, conflicts with {} existing booking(s)",
                    roomId, request.startTime(), request.endTime(), overlapping.size());
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
        log.info("Booking created: id={}, roomId='{}', title='{}', {} to {}",
                saved.getId(), roomId, saved.getTitle(), saved.getStartTime(), saved.getEndTime());

        // Store idempotency record if key was provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                IdempotencyRecord record = new IdempotencyRecord(
                        idempotencyKey, request.organizerEmail(), saved);
                idempotencyRepository.save(record);
                log.debug("Idempotency record stored: key='{}', organizer='{}', bookingId={}",
                        idempotencyKey, request.organizerEmail(), saved.getId());
            } catch (DataIntegrityViolationException ex) {
                // Concurrent request with same key won the race — return existing
                log.info("Concurrent idempotent request detected (constraint violation): key='{}'", idempotencyKey);
                Optional<IdempotencyRecord> existing = idempotencyRepository
                        .findByIdempotencyKeyAndOrganizerEmail(idempotencyKey, request.organizerEmail());
                if (existing.isPresent()) {
                    return BookingResponse.from(existing.get().getBooking());
                }
            }
        }

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

        log.debug("listBookings() called with roomId='{}', from={}, to={}, limit={}, offset={}",
                roomId, from, to, limit, offset);

        // Convert offset/limit to Spring's page-based pagination
        int page = offset / limit;
        Pageable pageable = PageRequest.of(page, limit, Sort.by("startTime").ascending());

        UUID parsedRoomId = roomId != null ? parseRoomId(roomId) : null;

        Page<Booking> bookingPage = bookingRepository.findWithFilters(
                parsedRoomId, from, to, pageable);

        List<BookingResponse> items = bookingPage.getContent().stream()
                .map(BookingResponse::from)
                .toList();

        log.debug("listBookings() returning {} items (total={})", items.size(), bookingPage.getTotalElements());

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
        log.debug("cancelBooking() called with bookingId='{}'", bookingId);

        UUID id = parseBookingId(bookingId);

        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Booking not found for cancellation: id='{}'", bookingId);
                    return new BookingNotFoundException(bookingId);
                });

        // Already cancelled — no-op, return as-is
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            log.info("Booking already cancelled, returning existing: id='{}'", bookingId);
            return BookingResponse.from(booking);
        }

        // Grace period: must cancel at least 1 hour before start time
        LocalDateTime cancellationDeadline = booking.getStartTime().minusHours(1);
        if (!LocalDateTime.now().isBefore(cancellationDeadline)) {
            log.warn("Cancellation rejected (too late): bookingId='{}', startTime={}, deadline={}",
                    bookingId, booking.getStartTime(), cancellationDeadline);
            throw new ValidationException(
                    "Booking can only be cancelled up to 1 hour before start time");
        }

        // Cancel the booking
        booking.setStatus(BookingStatus.CANCELLED);
        Booking saved = bookingRepository.save(booking);
        log.info("Booking cancelled: id='{}', was scheduled {} to {}",
                bookingId, saved.getStartTime(), saved.getEndTime());
        return BookingResponse.from(saved);
    }

    // Private validation methods

    /**
     * Validates that the booking duration is between 15 minutes and 4 hours.
     */
    private void validateDuration(LocalDateTime start, LocalDateTime end) {
        Duration duration = Duration.between(start, end);

        if (duration.compareTo(MIN_DURATION) < 0) {
            log.warn("Validation failed: duration {} is less than minimum 15 minutes", duration);
            throw new ValidationException(
                    "Booking duration must be at least 15 minutes");
        }
        if (duration.compareTo(MAX_DURATION) > 0) {
            log.warn("Validation failed: duration {} exceeds maximum 4 hours", duration);
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
        DayOfWeek startDay = start.getDayOfWeek();
        DayOfWeek endDay = end.getDayOfWeek();

        if (isWeekend(startDay) || isWeekend(endDay)) {
            log.warn("Validation failed: booking on weekend (startDay={}, endDay={})", startDay, endDay);
            throw new ValidationException(
                    "Bookings are only allowed Monday to Friday");
        }

        LocalTime startTime = start.toLocalTime();
        LocalTime endTime = end.toLocalTime();

        if (startTime.isBefore(BUSINESS_START) || endTime.isAfter(BUSINESS_END)) {
            log.warn("Validation failed: outside business hours (start={}, end={})", startTime, endTime);
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
