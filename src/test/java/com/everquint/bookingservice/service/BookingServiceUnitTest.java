package com.everquint.bookingservice.service;

import com.everquint.bookingservice.dto.BookingResponse;
import com.everquint.bookingservice.dto.CreateBookingRequest;
import com.everquint.bookingservice.entity.Booking;
import com.everquint.bookingservice.entity.BookingStatus;
import com.everquint.bookingservice.entity.Room;
import com.everquint.bookingservice.exception.BookingConflictException;
import com.everquint.bookingservice.exception.RoomNotFoundException;
import com.everquint.bookingservice.exception.ValidationException;
import com.everquint.bookingservice.repository.BookingRepository;
import com.everquint.bookingservice.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceUnitTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private RoomRepository roomRepository;

    @InjectMocks
    private BookingService bookingService;

    private UUID roomId;
    private Room testRoom;

    // Next Monday at 09:00
    private LocalDateTime mondayAt9;

    @BeforeEach
    void setUp() {
        roomId = UUID.randomUUID();
        testRoom = new Room();
        testRoom.setId(roomId);
        testRoom.setName("Test Room");
        testRoom.setCapacity(10);
        testRoom.setFloor(1);
        testRoom.setAmenities(List.of());

        // Calculate a Monday at 09:00
        LocalDateTime now = LocalDateTime.now();
        int daysUntilMonday = (8 - now.getDayOfWeek().getValue()) % 7;
        if (daysUntilMonday == 0) daysUntilMonday = 7;
        mondayAt9 = now.plusDays(daysUntilMonday).withHour(9).withMinute(0).withSecond(0).withNano(0);
    }

    // Room validation

    @Test
    @DisplayName("throws RoomNotFoundException when room does not exist")
    void createBooking_roomNotFound() {
        CreateBookingRequest request = new CreateBookingRequest(
                roomId.toString(), "Meeting", "test@email.com",
                mondayAt9, mondayAt9.plusHours(1)
        );

        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.createBooking(request))
                .isInstanceOf(RoomNotFoundException.class);
    }

    // Time validation

    @Nested
    @DisplayName("Time Validation Rules")
    class TimeValidation {

        @BeforeEach
        void mockRoom() {
            when(roomRepository.findById(roomId)).thenReturn(Optional.of(testRoom));
        }

        @Test
        @DisplayName("throws when startTime equals endTime")
        void startEqualsEnd() {
            CreateBookingRequest request = new CreateBookingRequest(
                    roomId.toString(), "Meeting", "test@email.com",
                    mondayAt9, mondayAt9
            );

            assertThatThrownBy(() -> bookingService.createBooking(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("startTime must be before endTime");
        }

        @Test
        @DisplayName("throws when startTime is after endTime")
        void startAfterEnd() {
            CreateBookingRequest request = new CreateBookingRequest(
                    roomId.toString(), "Meeting", "test@email.com",
                    mondayAt9.plusHours(2), mondayAt9
            );

            assertThatThrownBy(() -> bookingService.createBooking(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("startTime must be before endTime");
        }
    }

    // Duration validation

    @Nested
    @DisplayName("Duration Validation Rules")
    class DurationValidation {

        @BeforeEach
        void mockRoom() {
            when(roomRepository.findById(roomId)).thenReturn(Optional.of(testRoom));
        }

        @Test
        @DisplayName("throws when duration is less than 15 minutes")
        void durationTooShort() {
            CreateBookingRequest request = new CreateBookingRequest(
                    roomId.toString(), "Meeting", "test@email.com",
                    mondayAt9, mondayAt9.plusMinutes(10)
            );

            assertThatThrownBy(() -> bookingService.createBooking(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("at least 15 minutes");
        }

        @Test
        @DisplayName("throws when duration exceeds 4 hours")
        void durationTooLong() {
            CreateBookingRequest request = new CreateBookingRequest(
                    roomId.toString(), "Meeting", "test@email.com",
                    mondayAt9, mondayAt9.plusHours(5)
            );

            assertThatThrownBy(() -> bookingService.createBooking(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("must not exceed 4 hours");
        }

        @Test
        @DisplayName("accepts exactly 15 minutes (boundary)")
        void durationExactly15min() {
            CreateBookingRequest request = new CreateBookingRequest(
                    roomId.toString(), "Meeting", "test@email.com",
                    mondayAt9, mondayAt9.plusMinutes(15)
            );

            when(bookingRepository.findOverlappingBookings(any(), any(), any(), any()))
                    .thenReturn(List.of());
            when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
                Booking b = inv.getArgument(0);
                b.setId(UUID.randomUUID());
                return b;
            });

            BookingResponse response = bookingService.createBooking(request);
            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        }

        @Test
        @DisplayName("accepts exactly 4 hours (boundary)")
        void durationExactly4hours() {
            CreateBookingRequest request = new CreateBookingRequest(
                    roomId.toString(), "Meeting", "test@email.com",
                    mondayAt9, mondayAt9.plusHours(4)
            );

            when(bookingRepository.findOverlappingBookings(any(), any(), any(), any()))
                    .thenReturn(List.of());
            when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
                Booking b = inv.getArgument(0);
                b.setId(UUID.randomUUID());
                return b;
            });

            BookingResponse response = bookingService.createBooking(request);
            assertThat(response).isNotNull();
        }
    }

    // Working hours validation

    @Nested
    @DisplayName("Working Hours Validation Rules")
    class WorkingHoursValidation {

        @BeforeEach
        void mockRoom() {
            when(roomRepository.findById(roomId)).thenReturn(Optional.of(testRoom));
        }

        @Test
        @DisplayName("throws when booking is on Saturday")
        void saturday() {
            LocalDateTime saturday = mondayAt9.plusDays(5); // Monday + 5 = Saturday
            CreateBookingRequest request = new CreateBookingRequest(
                    roomId.toString(), "Meeting", "test@email.com",
                    saturday, saturday.plusHours(1)
            );

            assertThatThrownBy(() -> bookingService.createBooking(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Monday to Friday");
        }

        @Test
        @DisplayName("throws when booking is on Sunday")
        void sunday() {
            LocalDateTime sunday = mondayAt9.plusDays(6); // Monday + 6 = Sunday
            CreateBookingRequest request = new CreateBookingRequest(
                    roomId.toString(), "Meeting", "test@email.com",
                    sunday, sunday.plusHours(1)
            );

            assertThatThrownBy(() -> bookingService.createBooking(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Monday to Friday");
        }

        @Test
        @DisplayName("throws when start time is before 08:00")
        void beforeBusinessHours() {
            LocalDateTime earlyStart = mondayAt9.withHour(7).withMinute(30);
            CreateBookingRequest request = new CreateBookingRequest(
                    roomId.toString(), "Meeting", "test@email.com",
                    earlyStart, earlyStart.plusHours(1)
            );

            assertThatThrownBy(() -> bookingService.createBooking(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("08:00 and 20:00");
        }

        @Test
        @DisplayName("throws when end time is after 20:00")
        void afterBusinessHours() {
            LocalDateTime lateStart = mondayAt9.withHour(19).withMinute(30);
            CreateBookingRequest request = new CreateBookingRequest(
                    roomId.toString(), "Meeting", "test@email.com",
                    lateStart, lateStart.plusHours(1)
            );

            assertThatThrownBy(() -> bookingService.createBooking(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("08:00 and 20:00");
        }

        @Test
        @DisplayName("accepts booking at exact boundaries (08:00 - 20:00)")
        void exactBoundaries() {
            LocalDateTime start = mondayAt9.withHour(8).withMinute(0);
            LocalDateTime end = mondayAt9.withHour(12).withMinute(0);

            CreateBookingRequest request = new CreateBookingRequest(
                    roomId.toString(), "Meeting", "test@email.com",
                    start, end
            );

            when(bookingRepository.findOverlappingBookings(any(), any(), any(), any()))
                    .thenReturn(List.of());
            when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
                Booking b = inv.getArgument(0);
                b.setId(UUID.randomUUID());
                return b;
            });

            BookingResponse response = bookingService.createBooking(request);
            assertThat(response).isNotNull();
        }
    }

    // Overlap validation

    @Nested
    @DisplayName("Overlap Detection")
    class OverlapDetection {

        @BeforeEach
        void mockRoom() {
            when(roomRepository.findById(roomId)).thenReturn(Optional.of(testRoom));
        }

        @Test
        @DisplayName("throws BookingConflictException when overlapping booking exists")
        void overlappingBooking() {
            CreateBookingRequest request = new CreateBookingRequest(
                    roomId.toString(), "Meeting", "test@email.com",
                    mondayAt9, mondayAt9.plusHours(1)
            );

            Booking existing = new Booking();
            existing.setId(UUID.randomUUID());
            existing.setRoom(testRoom);
            existing.setStartTime(mondayAt9);
            existing.setEndTime(mondayAt9.plusHours(1));
            existing.setStatus(BookingStatus.CONFIRMED);

            when(bookingRepository.findOverlappingBookings(
                    eq(roomId), any(), any(), eq(BookingStatus.CONFIRMED)))
                    .thenReturn(List.of(existing));

            assertThatThrownBy(() -> bookingService.createBooking(request))
                    .isInstanceOf(BookingConflictException.class)
                    .hasMessageContaining("already booked");
        }

        @Test
        @DisplayName("succeeds when no overlapping bookings")
        void noOverlap() {
            CreateBookingRequest request = new CreateBookingRequest(
                    roomId.toString(), "Meeting", "test@email.com",
                    mondayAt9, mondayAt9.plusHours(1)
            );

            when(bookingRepository.findOverlappingBookings(
                    eq(roomId), any(), any(), eq(BookingStatus.CONFIRMED)))
                    .thenReturn(List.of());
            when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
                Booking b = inv.getArgument(0);
                b.setId(UUID.randomUUID());
                return b;
            });

            BookingResponse response = bookingService.createBooking(request);
            assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        }
    }
}
