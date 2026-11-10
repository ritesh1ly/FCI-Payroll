package in.gov.fci.payroll.increment.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PreLockCheck;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The pre-payroll check INCREMENTS_DUE_APPLIED (SRS-13-21, IN-11): only the members paid by the
 * cycle's unit count; a member without an office, or of another unit, does not.
 */
class IncrementsDueCheckBranchGapsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final IncrementsDueCheck check = new IncrementsDueCheck(db.jdbc, org);

    private static OrgLocation unit(long id) {
        OrgLocation l = mock(OrgLocation.class);
        when(l.getId()).thenReturn(id);
        return l;
    }

    @Test
    void SRS_13_21_onlyTheMembersOfTheCyclesUnitCountAndOneWithoutAnOfficeDoesNot() {
        db.rows(
                "FROM employee e JOIN employee_increment_due d",
                row("location_id", null, "n", 4L),
                row("location_id", 501L, "n", 2L),
                row("location_id", 601L, "n", 5L));
        OrgLocation ours = unit(300L);
        OrgLocation theirs = unit(400L);
        when(org.cycleUnitOf(501L)).thenReturn(Optional.of(ours));
        when(org.cycleUnitOf(601L)).thenReturn(Optional.of(theirs));

        PreLockCheck.Outcome outcome = check.evaluate(new PreLockCheck.Cycle(1L, 300L, "202610"));

        assertThat(outcome.failures()).isEqualTo(2L);
        assertThat(outcome.detail()).startsWith("2 increments due in or before 2026-10");
    }
}
