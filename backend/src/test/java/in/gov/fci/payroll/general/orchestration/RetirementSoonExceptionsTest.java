package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** SRS-1-24 (US-GAP-45): the retirement alert is an X-05 WARNING that holds no gate. */
class RetirementSoonExceptionsTest {

    @Test
    void SRS_1_24_aRetirementThisMonthOrNextIsAWarningForTheMembersOffice() {
        ExceptionWorklist worklist = mock(ExceptionWorklist.class);
        new RetirementSoonExceptions(worklist)
                .on(
                        new EmployeeEvents.RetirementSoon(
                                7L, LocalDate.of(2026, 11, 30), 9L, "ddo.delhi"));
        ArgumentCaptor<ExceptionWorklist.NewEntry> entry =
                ArgumentCaptor.forClass(ExceptionWorklist.NewEntry.class);
        verify(worklist).raise(eq("ddo.delhi"), entry.capture());
        assertThat(entry.getValue().severity()).isEqualTo("WARNING");
        assertThat(entry.getValue().blocksGate()).isNull();
        assertThat(entry.getValue().employeeId()).isEqualTo(7L);
        assertThat(entry.getValue().exceptionCode()).isEqualTo("RETIREMENT_SOON");
        assertThat(entry.getValue().detail()).contains("2026-11-30");
    }
}
