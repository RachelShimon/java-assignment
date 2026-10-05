package com.example.leavemanagement;

import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
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

    // --- helpers ---

    private Employee employeeWithQuota(int quota) {
        Employee emp = new Employee();
        emp.setName("Test Emp");
        emp.setAnnualQuota(quota);
        return employees.save(emp);
    }

    private void approvedVacation(Employee emp, LocalDate start, int days) {
        LeaveRequest r = new LeaveRequest();
        r.setEmployeeId(emp.getId());
        r.setType(LeaveType.VACATION);
        r.setStartDate(start);
        r.setEndDate(start.plusDays(days - 1));
        r.setDays(days);
        r.setStatus(LeaveStatus.APPROVED);
        leaveRequests.save(r);
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
