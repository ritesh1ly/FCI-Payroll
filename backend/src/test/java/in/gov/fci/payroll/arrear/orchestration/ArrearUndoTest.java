package in.gov.fci.payroll.arrear.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * SRS-21-6 (plan 13 D13-4): only an approved arrear event of an office whose cycle for the posting
 * month is still open may be undone; one not approved, of no office, of a locked month, unknown or
 * named by an unreadable key may not; an undo of an event no longer approved fails.
 */
class ArrearUndoTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final ArrearUndo undo = new ArrearUndo(db.jdbc, org, payroll, mock(AuditService.class));

    ArrearUndoTest() {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(40L);
        when(org.cycleUnitOf(41L)).thenReturn(Optional.of(unit));
    }

    private void event(String status, Long location) {
        db.rows(
                "FROM arrear_event e WHERE e.arrear_event_id",
                row(
                        "event_status",
                        status,
                        "scope_location_id",
                        location,
                        "posted_to_yymm",
                        "202610",
                        "event_reference",
                        "DA-2026-07"));
    }

    @Test
    void SRS_21_6_onlyAnApprovedEventOfAnOpenMonthIsUndoable() {
        assertThat(undo.find(null)).isEmpty();
        assertThat(undo.find("x")).isEmpty();
        assertThat(undo.find("5")).isEmpty();
        event("POSTED", 41L);
        assertThat(undo.find("5")).isEmpty();
        event("APPROVED", null);
        assertThat(undo.find("5")).isEmpty();
        event("APPROVED", 41L);
        when(payroll.cycle(40L, "202610"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(9L, 40L, "202610", "LOCKED")));
        assertThat(undo.find(" 5 ")).isEmpty();
        when(payroll.cycle(40L, "202610"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(9L, 40L, "202610", "COMPUTED")));
        assertThat(undo.find("5"))
                .hasValueSatisfying(t -> assertThat(t.cycleInstanceId()).isEqualTo(9L));
    }

    @Test
    void SRS_21_6_anUndoOfAnEventNoLongerApprovedFails() {
        db.value("UPDATE arrear_event SET event_status = 'REVERSED'", 0);
        assertThatThrownBy(() -> undo.undo("5", "r", "admin@x", 9L))
                .isInstanceOf(IllegalStateException.class);
    }
}
