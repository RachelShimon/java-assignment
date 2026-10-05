package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.CreateLeaveRequestDto;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.ConflictException;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.List;

// All business rules for leave requests live here; controllers stay HTTP-only.
@Service
public class LeaveRequestService {

    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;

    public LeaveRequestService(EmployeeRepository employeeRepository,
                               LeaveRequestRepository leaveRequestRepository) {
        this.employeeRepository = employeeRepository;
        this.leaveRequestRepository = leaveRequestRepository;
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> getAll() {
        return leaveRequestRepository.findAllByOrderByStartDateDesc();
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> searchByEmployeeName(String name) {
        return leaveRequestRepository.findByEmployee_NameContainingIgnoreCase(name);
    }

    @Transactional
    public LeaveRequest create(CreateLeaveRequestDto dto) {
        if (dto.getEndDate().isBefore(dto.getStartDate())) {
            throw new BadRequestException("End date must not be before start date");
        }

        // Row lock keeps the balance check consistent under concurrent requests.
        Employee employee = employeeRepository.findByIdForUpdate(dto.getEmployeeId())
                .orElseThrow(() -> new NotFoundException("Employee not found"));

        int days = (int) ChronoUnit.DAYS.between(dto.getStartDate(), dto.getEndDate()) + 1;

        if (dto.getType() == LeaveType.VACATION
                && usedVacationDays(employee.getId()) + days > employee.getAnnualQuota()) {
            throw new BadRequestException("Not enough vacation balance");
        }

        LeaveRequest request = new LeaveRequest();
        request.setEmployeeId(dto.getEmployeeId());
        request.setType(dto.getType());
        request.setStartDate(dto.getStartDate());
        request.setEndDate(dto.getEndDate());
        request.setDays(days);
        request.setStatus(LeaveStatus.PENDING);

        return leaveRequestRepository.save(request);
    }

    @Transactional
    public LeaveRequest approve(Long id) {
        // Lock the request row: a concurrent approve of the same request waits here
        // and then fails the PENDING check instead of double-approving.
        LeaveRequest request = leaveRequestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Leave request not found"));

        if (request.getStatus() != LeaveStatus.PENDING) {
            throw new ConflictException("Request is already " + request.getStatus().name().toLowerCase());
        }

        if (request.getType() == LeaveType.VACATION) {
            // Lock the employee row: concurrent approvals for the same employee are
            // serialized, so the second one sees the first one's days and cannot
            // jointly exceed the quota.
            Employee employee = employeeRepository.findByIdForUpdate(request.getEmployeeId())
                    .orElseThrow(() -> new NotFoundException("Employee not found"));

            if (usedVacationDays(employee.getId()) + request.getDays() > employee.getAnnualQuota()) {
                throw new ConflictException("Approving would exceed the annual vacation quota");
            }
        }

        request.setStatus(LeaveStatus.APPROVED);
        return request;
    }

    private int usedVacationDays(Long employeeId) {
        return leaveRequestRepository
                .findByEmployeeIdAndTypeAndStatus(employeeId, LeaveType.VACATION, LeaveStatus.APPROVED)
                .stream()
                .mapToInt(LeaveRequest::getDays)
                .sum();
    }
}
