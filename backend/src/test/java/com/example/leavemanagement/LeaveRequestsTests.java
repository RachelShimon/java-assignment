package com.example.leavemanagement;

import com.example.leavemanagement.exception.ConflictException;
import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import com.example.leavemanagement.service.LeaveRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Runs against a real, throwaway PostgreSQL started by Testcontainers.
// (Docker must be available on the machine running the tests.)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LeaveRequestsTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private LeaveRequestRepository leaveRequests;

    @Autowired
    private LeaveRequestService service;

    // --- helpers ---

    private Employee employeeWithQuota(int quota) {
        Employee emp = new Employee();
        emp.setName("Test Emp");
        emp.setAnnualQuota(quota);
        return employees.save(emp);
    }

    private LeaveRequest vacation(Employee emp, LocalDate start, int days, LeaveStatus status) {
        LeaveRequest r = new LeaveRequest();
        r.setEmployeeId(emp.getId());
        r.setType(LeaveType.VACATION);
        r.setStartDate(start);
        r.setEndDate(start.plusDays(days - 1));
        r.setDays(days);
        r.setStatus(status);
        return leaveRequests.save(r);
    }

    private void approvedVacation(Employee emp, LocalDate start, int days) {
        vacation(emp, start, days, LeaveStatus.APPROVED);
    }

    private String createBody(Long employeeId, LeaveType type, String start, String end) {
        return """
                {"employeeId": %d, "type": %d, "startDate": "%s", "endDate": "%s"}
                """.formatted(employeeId, type.ordinal(), start, end);
    }

    private org.springframework.test.web.servlet.ResultActions postCreate(String body) throws Exception {
        return mvc.perform(post("/api/leave-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // --- create: quota ---

    @Test
    void create_WithinQuota_Succeeds() throws Exception {
        Employee emp = employeeWithQuota(20);
        long before = leaveRequests.count();

        postCreate(createBody(emp.getId(), LeaveType.VACATION, "2026-03-01", "2026-03-03"))
                .andExpect(status().isOk());

        assertEquals(before + 1, leaveRequests.count());
    }

    @Test
    void create_SingleRequestOverQuota_IsRejected() throws Exception {
        Employee emp = employeeWithQuota(20);
        long before = leaveRequests.count();

        // 25 days > 20-day quota
        postCreate(createBody(emp.getId(), LeaveType.VACATION, "2026-03-01", "2026-03-25"))
                .andExpect(status().isBadRequest());

        assertEquals(before, leaveRequests.count());
    }

    // Regression test for the balance bug: used days must count against the quota.
    @Test
    void create_ExceedingRemainingQuota_IsRejected() throws Exception {
        Employee emp = employeeWithQuota(20);
        approvedVacation(emp, LocalDate.of(2026, 1, 5), 18); // 18 of 20 used
        long before = leaveRequests.count();

        // 5 more days would make 23 > 20
        postCreate(createBody(emp.getId(), LeaveType.VACATION, "2026-06-01", "2026-06-05"))
                .andExpect(status().isBadRequest());

        assertEquals(before, leaveRequests.count());
    }

    @Test
    void create_ExactlyRemainingQuota_Succeeds() throws Exception {
        Employee emp = employeeWithQuota(20);
        approvedVacation(emp, LocalDate.of(2026, 1, 5), 18); // 18 of 20 used

        // exactly the 2 remaining days
        postCreate(createBody(emp.getId(), LeaveType.VACATION, "2026-06-01", "2026-06-02"))
                .andExpect(status().isOk());
    }

    @Test
    void create_SickLeave_NotLimitedByVacationQuota() throws Exception {
        Employee emp = employeeWithQuota(20);
        approvedVacation(emp, LocalDate.of(2026, 1, 5), 18);

        postCreate(createBody(emp.getId(), LeaveType.SICK, "2026-06-01", "2026-06-07"))
                .andExpect(status().isOk());
    }

    // --- create: input validation ---

    @Test
    void create_UnknownEmployee_Returns404() throws Exception {
        postCreate(createBody(999999L, LeaveType.VACATION, "2026-03-01", "2026-03-03"))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_EndDateBeforeStartDate_IsRejected() throws Exception {
        Employee emp = employeeWithQuota(20);
        long before = leaveRequests.count();

        postCreate(createBody(emp.getId(), LeaveType.VACATION, "2026-03-10", "2026-03-05"))
                .andExpect(status().isBadRequest());

        assertEquals(before, leaveRequests.count());
    }

    @Test
    void create_MissingType_IsRejected() throws Exception {
        Employee emp = employeeWithQuota(20);

        postCreate("""
                {"employeeId": %d, "startDate": "2026-03-01", "endDate": "2026-03-03"}
                """.formatted(emp.getId()))
                .andExpect(status().isBadRequest());
    }

    // --- approve ---

    @Test
    void approve_PendingRequest_Succeeds() throws Exception {
        Employee emp = employeeWithQuota(20);
        LeaveRequest r = vacation(emp, LocalDate.of(2026, 4, 1), 3, LeaveStatus.PENDING);

        mvc.perform(post("/api/leave-requests/{id}/approve", r.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(LeaveStatus.APPROVED.ordinal()));

        assertEquals(LeaveStatus.APPROVED, leaveRequests.findById(r.getId()).orElseThrow().getStatus());
    }

    @Test
    void approve_AlreadyApproved_Returns409() throws Exception {
        Employee emp = employeeWithQuota(20);
        LeaveRequest r = vacation(emp, LocalDate.of(2026, 4, 1), 3, LeaveStatus.APPROVED);

        mvc.perform(post("/api/leave-requests/{id}/approve", r.getId()))
                .andExpect(status().isConflict());
    }

    @Test
    void approve_RejectedRequest_Returns409() throws Exception {
        Employee emp = employeeWithQuota(20);
        LeaveRequest r = vacation(emp, LocalDate.of(2026, 4, 1), 3, LeaveStatus.REJECTED);

        mvc.perform(post("/api/leave-requests/{id}/approve", r.getId()))
                .andExpect(status().isConflict());

        assertEquals(LeaveStatus.REJECTED, leaveRequests.findById(r.getId()).orElseThrow().getStatus());
    }

    @Test
    void approve_UnknownId_Returns404() throws Exception {
        mvc.perform(post("/api/leave-requests/{id}/approve", 999999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void approve_ExceedingQuota_Returns409AndStaysPending() throws Exception {
        Employee emp = employeeWithQuota(20);
        approvedVacation(emp, LocalDate.of(2026, 1, 5), 18); // 18 of 20 used
        LeaveRequest r = vacation(emp, LocalDate.of(2026, 6, 1), 5, LeaveStatus.PENDING);

        mvc.perform(post("/api/leave-requests/{id}/approve", r.getId()))
                .andExpect(status().isConflict());

        assertEquals(LeaveStatus.PENDING, leaveRequests.findById(r.getId()).orElseThrow().getStatus());
    }

    // Two pending requests that each fit the quota alone but not together are
    // approved concurrently; row locks must allow exactly one to win.
    @Test
    void approve_ConcurrentApprovals_CannotJointlyExceedQuota() throws Exception {
        Employee emp = employeeWithQuota(10);
        LeaveRequest r1 = vacation(emp, LocalDate.of(2026, 3, 1), 6, LeaveStatus.PENDING);
        LeaveRequest r2 = vacation(emp, LocalDate.of(2026, 5, 1), 6, LeaveStatus.PENDING);

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Boolean> approve1 = () -> tryApprove(r1.getId(), barrier);
        Callable<Boolean> approve2 = () -> tryApprove(r2.getId(), barrier);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = pool.invokeAll(List.of(approve1, approve2));
            long successes = 0;
            for (Future<Boolean> f : results) {
                if (f.get()) successes++;
            }
            assertEquals(1, successes, "exactly one of the two concurrent approvals must win");
        } finally {
            pool.shutdown();
        }

        int approvedDays = leaveRequests
                .findByEmployeeIdAndTypeAndStatus(emp.getId(), LeaveType.VACATION, LeaveStatus.APPROVED)
                .stream()
                .mapToInt(LeaveRequest::getDays)
                .sum();
        assertEquals(6, approvedDays, "approved days must not exceed the quota");
    }

    private boolean tryApprove(Long requestId, CyclicBarrier barrier) throws Exception {
        barrier.await();
        try {
            service.approve(requestId);
            return true;
        } catch (ConflictException e) {
            return false;
        }
    }

    // --- search ---

    @Test
    void search_MatchesByPartialNameCaseInsensitive() throws Exception {
        Employee emp = new Employee();
        emp.setName("Zelda Searchable");
        emp.setAnnualQuota(20);
        employees.save(emp);
        approvedVacation(emp, LocalDate.of(2026, 2, 2), 3);

        mvc.perform(get("/api/leave-requests/search").param("name", "zelda sea"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].employee.name").value("Zelda Searchable"));
    }

    @Test
    void search_SqlInjectionPayload_ReturnsNoRowsInsteadOfLeakingAll() throws Exception {
        Employee emp = employeeWithQuota(20);
        approvedVacation(emp, LocalDate.of(2026, 2, 2), 3);

        mvc.perform(get("/api/leave-requests/search").param("name", "' OR '1'='1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}
