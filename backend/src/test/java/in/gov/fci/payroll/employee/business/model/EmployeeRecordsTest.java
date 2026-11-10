package in.gov.fci.payroll.employee.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * I-17 life-cycle events and M-11 employee-number history: recorded by Payroll with their maker,
 * reason and approval; maker and reason cut to their columns.
 */
class EmployeeRecordsTest {

    private static final Instant NOW = Instant.parse("2026-10-21T05:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Test
    void I_17_anEventIsRecordedByPayrollWithItsMakerCut() {
        LifecycleEvent e =
                LifecycleEvent.record(
                        5L,
                        "SUSPENSION",
                        DAY,
                        DAY.plusDays(9),
                        "order",
                        new BigDecimal("50"),
                        "202610",
                        "202611",
                        "RO/1",
                        "m".repeat(51),
                        NOW);
        assertThat(ReflectionTestUtils.getField(e, "eventSource")).isEqualTo("PAYROLL");
        assertThat((String) ReflectionTestUtils.getField(e, "createdBy")).hasSize(50);
        assertThat(ReflectionTestUtils.getField(e, "eventType")).isEqualTo("SUSPENSION");
        assertThat(ReflectionTestUtils.getField(e, "createdAt")).isEqualTo(NOW);
        LifecycleEvent exact =
                LifecycleEvent.record(
                        5L, "HOLD", DAY, null, "r", null, null, null, null, "m".repeat(50), NOW);
        assertThat((String) ReflectionTestUtils.getField(exact, "createdBy")).hasSize(50);
        exact.attachApproval(7L);
        assertThat(ReflectionTestUtils.getField(exact, "approvalId")).isEqualTo(7L);
    }

    @Test
    void M_11_aNumberChangeKeepsOldAndNewNumbersAndItsReasonCut() {
        EmployeeNumberHistory h =
                EmployeeNumberHistory.of(
                        5L, "105220", "205220", 1L, 2L, DAY, "TRANSFER", "r".repeat(501), 9L);
        assertThat(ReflectionTestUtils.getField(h, "oldEmpNum")).isEqualTo("105220");
        assertThat(ReflectionTestUtils.getField(h, "newEmpNum")).isEqualTo("205220");
        assertThat(ReflectionTestUtils.getField(h, "changeEvent")).isEqualTo("TRANSFER");
        assertThat((String) ReflectionTestUtils.getField(h, "reason")).hasSize(500);
        EmployeeNumberHistory exact =
                EmployeeNumberHistory.of(
                        5L, "a", "b", 1L, 2L, DAY, "TRANSFER", "r".repeat(500), 9L);
        assertThat((String) ReflectionTestUtils.getField(exact, "reason")).hasSize(500);
        assertThat(ReflectionTestUtils.getField(exact, "approvalId")).isEqualTo(9L);
    }
}
