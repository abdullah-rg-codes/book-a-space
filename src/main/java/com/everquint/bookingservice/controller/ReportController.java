package com.everquint.bookingservice.controller;

import com.everquint.bookingservice.dto.RoomUtilizationResponse;
import com.everquint.bookingservice.exception.ValidationException;
import com.everquint.bookingservice.service.ReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * GET /reports/room-utilization — Room utilization report.
     *
     * @param from Required — start of the reporting period (ISO-8601)
     * @param to   Required — end of the reporting period (ISO-8601)
     * @return Array of utilization data per room
     */
    @GetMapping("/room-utilization")
    public ResponseEntity<List<RoomUtilizationResponse>> getRoomUtilization(
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to) {

        // Both params are required per spec
        if (from == null) {
            throw new ValidationException("'from' query parameter is required");
        }
        if (to == null) {
            throw new ValidationException("'to' query parameter is required");
        }
        if (!from.isBefore(to)) {
            throw new ValidationException("'from' must be before 'to'");
        }

        List<RoomUtilizationResponse> report = reportService.getRoomUtilization(from, to);
        return ResponseEntity.ok(report);
    }
}
