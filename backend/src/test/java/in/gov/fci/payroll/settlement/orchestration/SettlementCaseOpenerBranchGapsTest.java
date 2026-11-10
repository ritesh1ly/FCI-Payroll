package in.gov.fci.payroll.settlement.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * O-05 case opening (SRS-10-8, SRS-10-14): the separation reason HRMS sent is kept to the 40
 * characters of {@code settlement_case.separation_reason}, stripped; a separation sent without a
 * reason opens its case without one.
 */
class SettlementCaseOpenerBranchGapsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final SettlementCaseOpener opener =
            new SettlementCaseOpener(
                    db.jdbc,
                    mock(SettlementClearances.class),
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-27T05:00:00Z"), ZoneOffset.UTC));

    private Object reasonStored(String reason) {
        opener.on(
                new EmployeeEvents.EmployeeSeparated(
                        7L, 3L, "superannuation", LocalDate.of(2026, 9, 30), reason, "ddo@x"));
        List<ScriptedJdbc.Update> cases = db.updates("INSERT INTO settlement_case");
        // separation_reason is the fourth bound value.
        return cases.getLast().args().get(3);
    }

    @Test
    void SRS_10_8_theSeparationReasonIsKeptToItsColumnOrLeftOut() {
        assertThat(reasonStored(null)).isNull();
        assertThat((String) reasonStored("  " + "r".repeat(45) + " ")).isEqualTo("r".repeat(40));
        assertThat(reasonStored(" Retired ")).isEqualTo("Retired");
    }
}
