# Book-A-Space

Reserve meeting rooms without double-booking headaches. Handles idempotent requests, concurrent slot locking, grace-period cancellations, and room utilization analytics.

**Live API:** [https://book-a-space.onrender.com/swagger-ui/index.html](https://book-a-space.onrender.com/swagger-ui/index.html)

## Tech Stack

- **Java 17** / **Spring Boot 3.2.5**
- Spring Data JPA + Hibernate
- H2 (dev/test) / PostgreSQL (production)
- Bean Validation (Jakarta)
- JUnit 5 + MockMvc + Mockito
- Docker (multi-stage build)
- Hosted on [Render](https://render.com)

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/rooms` | Create a room |
| `GET` | `/rooms` | List rooms (filter by `minCapacity`, `amenity`) |
| `POST` | `/bookings` | Create a booking (supports `Idempotency-Key` header) |
| `GET` | `/bookings` | List bookings (filter by `roomId`, `from`, `to`; paginate with `limit`, `offset`) |
| `POST` | `/bookings/{id}/cancel` | Cancel a booking (1-hour grace period) |
| `GET` | `/reports/room-utilization` | Room utilization report for a date range |

## Business Rules

- Bookings allowed **Mon-Fri, 08:00-20:00** only
- Duration must be between **15 minutes** and **4 hours**
- No overlapping confirmed bookings for the same room (half-open interval)
- Cancelled bookings do **not** block new bookings
- Cancellation allowed up to **1 hour** before start time
- Idempotency keys are scoped **per organizer email**

## Getting Started

### Prerequisites

- Java 17+
- Maven 3.9+

### Run Locally

```bash
mvn spring-boot:run
```

The app starts on `http://localhost:8080` with an in-memory H2 database. Access the H2 console at `http://localhost:8080/h2-console`.

### Run Tests

```bash
mvn clean test
```

127 tests (unit + integration) covering all endpoints, validation rules, edge cases, and error contracts.

### API Documentation (Swagger)

Once the app is running: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

## Project Structure

```
src/main/java/com/everquint/bookingservice/
├── controller/          # REST controllers (no business logic)
│   ├── BookingController.java
│   ├── RoomController.java
│   └── ReportController.java
├── service/             # Business rules, validation, transactions
│   ├── BookingService.java
│   ├── RoomService.java
│   └── ReportService.java
├── repository/          # Spring Data JPA interfaces + JPQL queries
│   ├── BookingRepository.java
│   ├── RoomRepository.java
│   └── IdempotencyRepository.java
├── entity/              # JPA entities
│   ├── Booking.java
│   ├── Room.java
│   ├── BookingStatus.java
│   └── IdempotencyRecord.java
├── dto/                 # Request/response records
│   ├── CreateBookingRequest.java
│   ├── CreateRoomRequest.java
│   ├── BookingResponse.java
│   ├── RoomResponse.java
│   ├── PaginatedResponse.java
│   ├── RoomUtilizationResponse.java
│   └── ErrorResponse.java
└── exception/           # Custom exceptions + global handler
    ├── GlobalExceptionHandler.java
    ├── BookingConflictException.java
    ├── BookingNotFoundException.java
    ├── RoomNotFoundException.java
    ├── DuplicateRoomException.java
    └── ValidationException.java
```

## Error Handling

All errors return a consistent JSON format:

```json
{
  "error": "ValidationError",
  "message": "startTime must be before endTime"
}
```

| HTTP Code | Error Type | When |
|-----------|------------|------|
| 400 | `ValidationError` | Invalid input, business rule violation, malformed body |
| 404 | `NotFoundError` | Room or booking not found |
| 409 | `ConflictError` | Overlapping booking, duplicate room name |

## Deployment (Render)

The project includes a `render.yaml` Blueprint for one-click deployment:

1. Push to `main`
2. On Render: **New** > **Blueprint** > select this repo
3. Render provisions a PostgreSQL database and Docker web service automatically

### Manual Setup

1. Create a PostgreSQL instance on Render
2. Create a Web Service (Docker), pointing to this repo
3. Set environment variables:
   - `SPRING_PROFILES_ACTIVE=prod`
   - `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` (from your Render DB)

## Design Decisions

Detailed design documentation is available in [`DESIGN.md`](igmoredmds/DESIGN.md), covering:

- Data model and entity relationships
- Overlap detection (half-open interval with JPQL)
- Idempotency implementation (DB unique constraint + retry on race)
- Concurrency handling (`@Transactional` boundaries)
- Utilization calculation formula and assumptions

## License

This project is an interview assignment for Ever Quint.
