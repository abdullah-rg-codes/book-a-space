# Book A Space 
Meeting Room Booking Service — Design Document
 
> Author: Abdullah R G  
> Date: July 2026

---

## 1. Overview

This is a REST API service for managing meeting rooms and their bookings. It supports room creation, booking with business-rule validation, idempotent booking creation, cancellation with a grace period, and room utilization reporting.

**Tech Stack:** Java 17, Spring Boot 3.x, Spring Data JPA, H2 (dev/test), Maven, JUnit 5 + Mockito.

---

## 2. Architecture

I went with a **layered architecture** — nothing fancy, just clean separation of concerns:

```
┌────────────────────────────────────────────┐
│  Controller Layer (@RestController)        │
│  - Receives HTTP requests                  │
│  - Basic input validation (format, types)  │
│  - Maps to HTTP responses                  │
│  - ZERO business logic here                │
├────────────────────────────────────────────┤
│  Service Layer (@Service)                  │
│  - All business rules (overlap, duration,  │
│    working hours, cancellation grace)      │
│  - Idempotency handling                    │
│  - Transaction boundaries                  │
├────────────────────────────────────────────┤
│  Repository Layer (@Repository)            │
│  - Data access via Spring Data JPA         │
│  - Custom queries for overlap checks,      │
│    filtering, pagination                   │
├────────────────────────────────────────────┤
│  Database (H2 / MySQL)                     │
│  - Relational schema with constraints      │
│  - Indexes for query performance           │
└────────────────────────────────────────────┘
```

**Why layered?** The spec explicitly says "no business logic directly in routing" and demands separation of handlers, services, and persistence. This architecture satisfies that cleanly.

---

## 3. Data Model

### 3.1 Entity Relationship Diagram

```
┌─────────────┐         ┌─────────────┐         ┌───────────────────┐
│    rooms    │         │  bookings   │         │ idempotency_keys  │
├─────────────┤         ├─────────────┤         ├───────────────────┤
│ id (PK)     │◄────────┤ id (PK)     │         │ id (PK)           │
│ name (UQ)   │   1:M   │ room_id(FK) │         │ key_value         │
│ capacity    │         │ title       │         │ organizer_email   │
│ floor       │         │ organizer   │         │ booking_id (FK)   │
│ amenities   │         │ start_time  │         │ status            │
│ created_at  │         │ end_time    │         │ created_at        │
│ updated_at  │         │ status      │         │ expires_at        │
└─────────────┘         │ created_at  │         └───────────────────┘
                        │ updated_at  │
                        └─────────────┘
```

### 3.2 Table: `rooms`

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | `BIGINT` | PRIMARY KEY, AUTO_INCREMENT |
| `name` | `VARCHAR(255)` | NOT NULL, UNIQUE (case-insensitive enforced at app layer via `LOWER()`) |
| `capacity` | `INT` | NOT NULL, CHECK (`capacity >= 1`) |
| `floor` | `INT` | NOT NULL |
| `amenities` | `VARCHAR(500)` | Stores JSON array as string (e.g., `["projector","whiteboard"]`) |
| `created_at` | `TIMESTAMP` | DEFAULT CURRENT_TIMESTAMP |
| `updated_at` | `TIMESTAMP` | DEFAULT CURRENT_TIMESTAMP |

**Notes:**
- Case-insensitive name uniqueness is enforced in the service layer before insert (`LOWER(name)`), since standard SQL `UNIQUE` is case-sensitive.
- Amenities could be normalized into a separate table, but for this scope a JSON string keeps it simple.

### 3.3 Table: `bookings`

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | `BIGINT` | PRIMARY KEY, AUTO_INCREMENT |
| `room_id` | `BIGINT` | NOT NULL, FOREIGN KEY → `rooms(id)` |
| `title` | `VARCHAR(255)` | NOT NULL |
| `organizer_email` | `VARCHAR(255)` | NOT NULL |
| `start_time` | `TIMESTAMP` | NOT NULL |
| `end_time` | `TIMESTAMP` | NOT NULL |
| `status` | `VARCHAR(20)` | NOT NULL, DEFAULT `'CONFIRMED'`. Values: `CONFIRMED`, `CANCELLED` |
| `created_at` | `TIMESTAMP` | DEFAULT CURRENT_TIMESTAMP |
| `updated_at` | `TIMESTAMP` | DEFAULT CURRENT_TIMESTAMP |

**Indexes:**
- Composite index on `(room_id, status, start_time, end_time)` — critical for overlap queries.

### 3.4 Table: `idempotency_keys`

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | `BIGINT` | PRIMARY KEY, AUTO_INCREMENT |
| `key_value` | `VARCHAR(255)` | NOT NULL |
| `organizer_email` | `VARCHAR(255)` | NOT NULL |
| `booking_id` | `BIGINT` | NULLABLE, FOREIGN KEY → `bookings(id)`. NULL when request is still `IN_PROGRESS`. |
| `status` | `VARCHAR(20)` | NOT NULL. Values: `IN_PROGRESS`, `COMPLETED`. |
| `created_at` | `TIMESTAMP` | DEFAULT CURRENT_TIMESTAMP |
| `expires_at` | `TIMESTAMP` | TTL for cleanup (e.g., 24 hours after creation) |

**Constraints:**
- `UNIQUE (key_value, organizer_email)` — this is the backbone of idempotency. Even if two requests arrive simultaneously with the same key and organizer, the database guarantees only one insert succeeds.

---

## 4. How We Enforce No Overlaps

### 4.1 The Problem

Two bookings for the same room cannot overlap in time. The tricky part is the **check-then-act race condition**: two users might simultaneously check availability, both see "free," and both book.

### 4.2 The Overlap Condition

Two intervals `[s1, e1)` and `[s2, e2)` overlap if and only if:

```
s1 < e2 AND s2 < e1
```

Translated to SQL for a new booking `(new_start, new_end)`:

```sql
SELECT * FROM bookings
WHERE room_id = :roomId
  AND status = 'CONFIRMED'
  AND start_time < :newEnd    -- existing starts before new ends
  AND end_time > :newStart    -- existing ends after new starts
```

### 4.3 Race Condition Prevention

I use **pessimistic locking** via `SELECT FOR UPDATE` on the room record:

```java
@Transactional
public Booking createBooking(BookingRequest request) {
    // 1. Lock the room row
    Room room = roomRepository.findByIdForUpdate(request.getRoomId())
        .orElseThrow(() -> new NotFoundError("Room not found"));

    // 2. Check overlap (safe because room is locked)
    List<Booking> overlaps = bookingRepository.findOverlapping(
        room.getId(), request.getStartTime(), request.getEndTime()
    );
    if (!overlaps.isEmpty()) {
        throw new ConflictError("Room already booked for this slot");
    }

    // 3. Create booking
    Booking booking = new Booking(...);
    return bookingRepository.save(booking);
}
```

**Why pessimistic locking?**
- The spec explicitly lists "DB transactions with unique constraint" and "explicit locking using DB" as acceptable solutions.
- For a booking system, conflicts are expected (popular time slots). Pessimistic locking fails fast rather than retrying.
- `SELECT FOR UPDATE` is simple, well-understood, and database-enforced.

**Limitation:** If the room table is large and heavily contended, row-level locks could cause some queueing. But for this scope, it's the right trade-off.

---

## 5. Idempotency Implementation

### 5.1 Requirement Recap

- `Idempotency-Key` header on `POST /bookings`.
- Same key + succeeded → return same booking.
- Original in-progress → concurrent calls should not create duplicates.
- Keys are unique **per organizer** (simplified as per spec).
- Must survive process restarts → persisted in DB.

### 5.2 The Flow

```
┌──────────────────────────────────────────────────────────────┐
│  Client sends POST /bookings with Idempotency-Key: "abc-123" │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│  1. Extract key + organizerEmail from request body           │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│  2. BEGIN TRANSACTION                                        │
│     Try INSERT INTO idempotency_keys (key, organizer, status)│
│     VALUES ('abc-123', 'john@example.com', 'IN_PROGRESS')    │
└──────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              │                               │
    ┌─────────▼─────────┐         ┌───────────▼──────────┐
    │  INSERT succeeds  │         │  UNIQUE violation    │
    │  (first request)  │         │  (duplicate request) │
    └─────────┬─────────┘         └───────────┬──────────┘
              │                               │
              ▼                               ▼
    ┌─────────────────┐           ┌──────────────────────────┐
    │ 3. Proceed with │           │ Check stored status:     │
    │    booking      │           │                          │
    │    creation     │           │ COMPLETED → return       │
    │    (with lock)  │           │ stored booking (200)     │
    └────────┬────────┘           │                          │
             │                    │ IN_PROGRESS → return 409 │
             │                    │ with "request in flight" │
             ▼                    └──────────────────────────┘
    ┌─────────────────┐
    │ 4. On success:  │
    │    UPDATE       │
    │    idempotency  │
    │    SET status=  │
    │    'COMPLETED', │
    │    booking_id=? │
    └────────┬────────┘
             │
             ▼
    ┌─────────────────┐
    │ 5. COMMIT       │
    │    Return 201   │
    └─────────────────┘
```

### 5.3 Why This Works Under Concurrency

The `UNIQUE (key_value, organizer_email)` constraint is the guardrail. Even if two threads/servers hit the database simultaneously with the same key:

- **Thread A** inserts first → succeeds, gets lock, creates booking.
- **Thread B** tries insert → `DuplicateKeyException`, reads status.
  - If A already committed → status is `COMPLETED` → B returns the same booking.
  - If A still in transaction → status is `IN_PROGRESS` → B returns 409.

This is database-enforced, so it works across multiple server instances and survives restarts.

### 5.4 Cleanup

`IN_PROGRESS` keys that never complete (e.g., server crash mid-request) are cleaned up by a scheduled job or TTL (`expires_at`). After expiry, the client can retry with the same key.

---

## 6. Concurrency Issues & Handling

| Scenario | Risk | Mitigation |
|----------|------|------------|
| **Double booking** (two users, same slot) | Race condition in overlap check | `SELECT FOR UPDATE` on room row + transaction |
| **Duplicate booking** (same user clicks twice) | Same idempotency key, two requests | `UNIQUE` constraint on `(key, organizer)` |
| **Concurrent idempotency key insert** | Two threads try to insert same key simultaneously | Database `UNIQUE` constraint — one fails, reads the winner's state |
| **Cancellation while booking in progress** | Booking cancelled before overlap check completes | Row lock on room serializes operations |

**Key principle:** All booking mutations happen inside `@Transactional` with pessimistic locking. The database is the source of truth for concurrency.

---

## 7. Error Handling Strategy

### 7.1 Consistent JSON Format

Every error response follows this exact shape (as required by the spec):

```json
{
  "error": "ErrorType",
  "message": "Human-readable description"
}
```

### 7.2 Error Types & HTTP Codes

| Error Type | HTTP Code | When It Happens |
|------------|-----------|-----------------|
| `ValidationError` | `400` | Invalid input format, missing fields, `startTime >= endTime`, duration outside 15min–4hr, booking outside Mon–Fri 08:00–20:00 |
| `NotFoundError` | `404` | Room ID doesn't exist, Booking ID doesn't exist |
| `ConflictError` | `409` | Overlapping confirmed booking for same room |
| `IdempotencyError` | `409` | Concurrent request with same `Idempotency-Key` still in progress |
| `CancellationError` | `400` | Attempt to cancel within 1 hour of `startTime`, or booking already passed |

### 7.3 Implementation

A `@ControllerAdvice` global exception handler catches all custom exceptions and maps them:

```java
@ExceptionHandler(ValidationError.class)
public ResponseEntity<ErrorResponse> handleValidation(ValidationError ex) {
    return ResponseEntity.status(400)
        .body(new ErrorResponse("ValidationError", ex.getMessage()));
}
```

Spring's default validation (`@Valid`, `@NotNull`, etc.) is also wired in at the controller layer for format-level checks.

---

## 8. Utilization Calculation

### 8.1 Formula

```
utilizationPercent = totalBookingHours / totalBusinessHours
```

Where:
- **Business hours:** Monday–Friday, 08:00–20:00 (12 hours/day).
- **Range:** `[from, to]` query parameters (both required).

### 8.2 Calculating Total Business Hours

For each day in `[from, to]`:
1. Skip weekends (Saturday, Sunday).
2. For weekdays, calculate the intersection with 08:00–20:00.
3. Sum the intersections.

**Examples:**

| from | to | Business Hours |
|------|-----|----------------|
| Mon 08:00 | Wed 20:00 | 3 days × 12h = **36h** |
| Mon 10:00 | Wed 14:00 | Mon 10h + Tue 12h + Wed 6h = **28h** |
| Fri 18:00 | Mon 10:00 | Fri 2h + Mon 2h = **4h** (weekend excluded) |
| Sat 08:00 | Sun 20:00 | **0h** (weekend) |

### 8.3 Calculating Total Booked Hours

For each **confirmed** booking of a room:
1. Check if the booking interval overlaps with `[from, to]`.
2. If yes, compute the intersection duration:
   ```
   intersectionStart = max(booking.startTime, from)
   intersectionEnd   = min(booking.endTime, to)
   bookedHours       = duration(intersectionStart, intersectionEnd)
   ```
3. Sum all `bookedHours`.

**Edge cases handled:**
- **No bookings in range:** `totalBookingHours = 0`, `utilizationPercent = 0.0`.
- **Partial overlap (booking starts before `from`):** Count only from `from` to booking end.
- **Partial overlap (booking ends after `to`):** Count only from booking start to `to`.
- **Booking fully outside range:** Count 0.
- **Zero business hours in range:** Return `0.0` to avoid division by zero.

### 8.4 Response Format

```json
[
  {
    "roomId": 1,
    "roomName": "Conference A",
    "totalBookingHours": 12.5,
    "utilizationPercent": 0.45
  }
]
```

---

## 9. API Contracts

### 9.1 `POST /rooms`

**Request:**
```json
{
  "name": "Conference A",
  "capacity": 10,
  "floor": 2,
  "amenities": ["projector", "whiteboard"]
}
```

**Response 201:**
```json
{
  "id": 1,
  "name": "Conference A",
  "capacity": 10,
  "floor": 2,
  "amenities": ["projector", "whiteboard"]
}
```

**Response 400:**
```json
{
  "error": "ValidationError",
  "message": "Room name 'conference a' already exists"
}
```

### 9.2 `GET /rooms`

**Request:** `GET /rooms?minCapacity=5&amenity=projector`

**Response 200:**
```json
[
  {
    "id": 1,
    "name": "Conference A",
    "capacity": 10,
    "floor": 2,
    "amenities": ["projector", "whiteboard"]
  }
]
```

### 9.3 `POST /bookings`

**Request Headers:** `Idempotency-Key: abc-123-xyz`

**Request:**
```json
{
  "roomId": 1,
  "title": "Sprint Planning",
  "organizerEmail": "john@example.com",
  "startTime": "2026-07-28T09:00:00",
  "endTime": "2026-07-28T10:00:00"
}
```

**Response 201:**
```json
{
  "id": 1,
  "roomId": 1,
  "title": "Sprint Planning",
  "organizerEmail": "john@example.com",
  "startTime": "2026-07-28T09:00:00",
  "endTime": "2026-07-28T10:00:00",
  "status": "CONFIRMED"
}
```

**Response 400 (validation):**
```json
{
  "error": "ValidationError",
  "message": "Booking duration must be between 15 minutes and 4 hours"
}
```

**Response 409 (overlap):**
```json
{
  "error": "ConflictError",
  "message": "Room is already booked for the requested time slot"
}
```

**Response 404:**
```json
{
  "error": "NotFoundError",
  "message": "Room with id 99 not found"
}
```

### 9.4 `GET /bookings`

**Request:** `GET /bookings?roomId=1&from=2026-07-28T00:00:00&to=2026-07-30T23:59:59&limit=10&offset=0`

**Response 200:**
```json
{
  "items": [
    {
      "id": 1,
      "roomId": 1,
      "title": "Sprint Planning",
      "organizerEmail": "john@example.com",
      "startTime": "2026-07-28T09:00:00",
      "endTime": "2026-07-28T10:00:00",
      "status": "CONFIRMED"
    }
  ],
  "total": 5,
  "limit": 10,
  "offset": 0
}
```

### 9.5 `POST /bookings/{id}/cancel`

**Response 200:**
```json
{
  "id": 1,
  "roomId": 1,
  "title": "Sprint Planning",
  "organizerEmail": "john@example.com",
  "startTime": "2026-07-28T09:00:00",
  "endTime": "2026-07-28T10:00:00",
  "status": "CANCELLED"
}
```

**Response 400 (grace period expired):**
```json
{
  "error": "CancellationError",
  "message": "Booking can only be cancelled up to 1 hour before start time"
}
```

### 9.6 `GET /reports/room-utilization`

**Request:** `GET /reports/room-utilization?from=2026-07-28T00:00:00&to=2026-08-01T23:59:59`

**Response 200:**
```json
[
  {
    "roomId": 1,
    "roomName": "Conference A",
    "totalBookingHours": 12.5,
    "utilizationPercent": 0.45
  },
  {
    "roomId": 2,
    "roomName": "War Room",
    "totalBookingHours": 0.0,
    "utilizationPercent": 0.0
  }
]
```

---

## 10. Testing Strategy

### 10.1 Unit Tests (Booking Rules)

| Test Case | What It Validates |
|-----------|-------------------|
| `createBooking_durationTooShort` | Duration < 15 min → `ValidationError` |
| `createBooking_durationTooLong` | Duration > 4 hours → `ValidationError` |
| `createBooking_startAfterEnd` | `startTime >= endTime` → `ValidationError` |
| `createBooking_weekendNotAllowed` | Saturday/Sunday booking → `ValidationError` |
| `createBooking_before8am` | Start before 08:00 → `ValidationError` |
| `createBooking_after8pm` | End after 20:00 → `ValidationError` |
| `createBooking_overlapDetected` | Overlapping confirmed booking → `ConflictError` |
| `createBooking_noOverlapWithCancelled` | Cancelled booking does NOT block new booking |

### 10.2 Integration Tests

| Test Case | What It Validates |
|-----------|-------------------|
| `createBooking_happyPath` | Full flow: create room → book → verify response |
| `createBooking_conflict` | Two bookings same slot → first succeeds, second 409 |
| `idempotency_sameKeyReturnsSameBooking` | Same `Idempotency-Key` → no duplicate, same response |
| `idempotency_concurrentSameKey` | Two parallel requests same key → only one booking created |
| `cancelBooking_withinGracePeriod` | Cancel > 1hr before start → success |
| `cancelBooking_afterGracePeriod` | Cancel < 1hr before start → `CancellationError` |
| `cancelBooking_alreadyCancelled` | Cancel again → no-op, returns existing cancelled booking |
| `utilization_noBookings` | Empty range → `0.0` |
| `utilization_partialOverlap` | Booking starts before `from` → counts only intersection |
| `utilization_fullOverlap` | Booking fully inside range → counts full duration |

### 10.3 Running Tests

```bash
# Run all tests
./mvnw test

# Run only unit tests
./mvnw test -Dtest="*UnitTest"

# Run only integration tests
./mvnw test -Dtest="*IntegrationTest"
```

---

## 11. Project Structure

```
meeting-room-booking/
├── src/
│   ├── main/
│   │   ├── java/com/everquint/booking/
│   │   │   ├── BookingApplication.java
│   │   │   ├── controller/
│   │   │   │   ├── RoomController.java
│   │   │   │   ├── BookingController.java
│   │   │   │   └── ReportController.java
│   │   │   ├── service/
│   │   │   │   ├── RoomService.java
│   │   │   │   ├── BookingService.java
│   │   │   │   ├── ReportService.java
│   │   │   │   └── IdempotencyService.java
│   │   │   ├── repository/
│   │   │   │   ├── RoomRepository.java
│   │   │   │   ├── BookingRepository.java
│   │   │   │   └── IdempotencyKeyRepository.java
│   │   │   ├── model/
│   │   │   │   ├── Room.java
│   │   │   │   ├── Booking.java
│   │   │   │   └── IdempotencyKey.java
│   │   │   ├── dto/
│   │   │   │   ├── RoomRequest.java
│   │   │   │   ├── RoomResponse.java
│   │   │   │   ├── BookingRequest.java
│   │   │   │   ├── BookingResponse.java
│   │   │   │   └── UtilizationReport.java
│   │   │   ├── exception/
│   │   │   │   ├── GlobalExceptionHandler.java
│   │   │   │   ├── ValidationError.java
│   │   │   │   ├── NotFoundError.java
│   │   │   │   ├── ConflictError.java
│   │   │   │   └── CancellationError.java
│   │   │   └── util/
│   │   │       └── TimeUtil.java
│   │   └── resources/
│   │       ├── application.properties
│   │       └── schema.sql (optional, for H2)
│   └── test/
│       └── java/com/everquint/booking/
│           ├── unit/
│           │   ├── BookingRulesTest.java
│           │   └── UtilizationCalcTest.java
│           └── integration/
│               ├── BookingApiTest.java
│               ├── IdempotencyTest.java
│               └── CancellationTest.java
├── pom.xml
├── README.md
└── DESIGN.md
```

---

## 12. Assumptions & Clarifications

| Topic | Assumption |
|-------|------------|
| **Time zone** | All times are in the room's local time zone (as stated in spec). For simplicity, the system uses system local time; production would use `ZoneId` per room. |
| **Cancelled bookings** | Do NOT block new bookings. Overlap check only considers `status = 'CONFIRMED'`. |
| **Idempotency key scope** | Unique per `organizer_email` (as per spec simplification). |
| **Idempotency key expiry** | `IN_PROGRESS` keys expire after 24 hours. After expiry, client can retry with same key. |
| **Pagination defaults** | `limit` defaults to 20, `offset` defaults to 0 if not provided. |
| **Amenities filter** | `amenity` query param checks if the room's amenities array contains the given string (case-insensitive). |
| **Business hours calculation** | Counts actual hours within 08:00–20:00 that fall inside `[from, to]`, not just full days. |
| **Room deletion** | Not in scope. Rooms are created once and persist. |
| **Booking update** | Not in scope. Only creation and cancellation. |

---

*End of document. This design will be iterated and refined as implementation progresses.*
