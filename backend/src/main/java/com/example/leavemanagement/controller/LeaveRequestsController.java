package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.CreateLeaveRequestDto;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.service.LeaveRequestService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Thin HTTP layer; business rules live in LeaveRequestService.
@RestController
@RequestMapping("/api/leave-requests")
public class LeaveRequestsController {

    private final LeaveRequestService service;

    public LeaveRequestsController(LeaveRequestService service) {
        this.service = service;
    }

    // GET /api/leave-requests
    @GetMapping
    public ResponseEntity<List<LeaveRequest>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    // GET /api/leave-requests/search?name=Dana
    @GetMapping("/search")
    public ResponseEntity<List<LeaveRequest>> search(@RequestParam String name) {
        return ResponseEntity.ok(service.searchByEmployeeName(name));
    }

    // POST /api/leave-requests
    @PostMapping
    public ResponseEntity<LeaveRequest> create(@Valid @RequestBody CreateLeaveRequestDto dto) {
        return ResponseEntity.ok(service.create(dto));
    }

    // POST /api/leave-requests/{id}/approve
    @PostMapping("/{id}/approve")
    public ResponseEntity<LeaveRequest> approve(@PathVariable Long id) {
        return ResponseEntity.ok(service.approve(id));
    }
}
