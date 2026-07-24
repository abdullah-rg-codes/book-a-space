package com.everquint.bookingservice.exception;

import com.everquint.bookingservice.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Centralized exception handling for all controllers.
 *
 * Converts exceptions into the consistent JSON error format:
 * { "error": "ErrorType", "message": "Human-readable explanation" }
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse("ValidationError", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(DuplicateRoomException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateRoom(DuplicateRoomException ex) {
        log.warn("Duplicate room conflict: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse("ConflictError", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(BookingConflictException.class)
    public ResponseEntity<ErrorResponse> handleBookingConflict(BookingConflictException ex) {
        log.warn("Booking conflict: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse("ConflictError", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(RoomNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRoomNotFound(RoomNotFoundException ex) {
        log.warn("Room not found: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse("NotFoundError", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBookingNotFound(BookingNotFoundException ex) {
        log.warn("Booking not found: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse("NotFoundError", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Handles bean validation failures (e.g., @NotBlank, @Min, @Email).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("Validation failed");

        log.warn("Bean validation error: {}", message);
        ErrorResponse error = new ErrorResponse("ValidationError", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Handles malformed / unreadable request bodies (e.g. invalid JSON, wrong
     * value types inside the body). Returns a consistent 400 instead of leaking
     * the framework's default error shape.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMostSpecificCause().getMessage());
        ErrorResponse error = new ErrorResponse(
                "ValidationError", "Malformed or unreadable request body");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Handles query/path parameter type mismatches (e.g. limit=abc,
     * from=not-a-date, minCapacity=xyz). Returns a consistent 400 rather than
     * allowing the request to fail with the framework default response.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "Invalid value for parameter '" + ex.getName() + "'";
        log.warn("Parameter type mismatch: {}", message);
        ErrorResponse error = new ErrorResponse("ValidationError", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }
}
