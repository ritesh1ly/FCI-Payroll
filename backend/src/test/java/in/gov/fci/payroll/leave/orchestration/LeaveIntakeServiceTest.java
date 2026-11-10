package in.gov.fci.payroll.leave.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.leave.api.LeaveReceipts.Balance;
import in.gov.fci.payroll.leave.api.LeaveReceipts.EncashmentSanction;
import in.gov.fci.payroll.leave.api.LeaveReceipts.Event;
import in.gov.fci.payroll.leave.api.LeaveReceipts.EventType;
import in.gov.fci.payroll.leave.api.LeaveReceipts.Outcome;
import in.gov.fci.payroll.leave.api.LeaveReceipts.Received;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Leave received from HRMS (SRS-LV-13, SRS-LV-34, BR-LEAVE-003): a receipt without a member number
 * or with a blank sanction is held, a balance without a leave type is refused, and the sanctioning
 * authority is stored trimmed to its column (150) or not at all.
 */
class LeaveIntakeServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 5);

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final HrmsChanges hrms = mock(HrmsChanges.class);
    private final LeaveIntakeService service =
            new LeaveIntakeService(
                    db.jdbc,
                    hrms,
                    mock(ExceptionWorklist.class),
                    mock(AuditService.class),
                    JsonMapper.builder().build(),
                    Clock.fixed(Instant.parse("2026-10-29T06:00:00Z"), ZoneOffset.UTC));

    LeaveIntakeServiceTest() {
        db.rows("FROM employee WHERE emp_num", cols(501L))
                .rows("FROM employee WHERE employee_id = ? AND record_status", cols(501L))
                .rows(
                        "FROM leave_type_reference WHERE leave_type_code",
                        row(
                                "leave_type_name", "Earned Leave",
                                "is_encashable", "Y",
                                "supports_half_day", "N"));
        when(hrms.receive(any())).thenReturn(new HrmsChanges.Received(9L, "T-9", "OPEN", false));
    }

    private static Event event(String eis, String authority) {
        return new Event(
                eis,
                "EL",
                FROM,
                FROM.plusDays(1),
                new BigDecimal("2"),
                false,
                "SNC/2026/41",
                null,
                authority,
                EventType.ADDITION,
                null);
    }

    @Test
    void SRS_LV_13_anEventWithoutAMemberNumberIsHeld() {
        assertThat(service.receiveEvent("hrms", event(null, "AGM")).outcome())
                .isEqualTo(Outcome.HELD);
        assertThat(service.receiveEvent("hrms", event("  ", "AGM")).defect()).isEqualTo("MEMBER");
    }

    @Test
    void SRS_LV_13_theSanctioningAuthorityIsStoredTrimmedToItsColumnOrNotAtAll() {
        assertThat(service.receiveEvent("hrms", event("E0501", null)).outcome())
                .isEqualTo(Outcome.TASK_OPENED);
        assertThat(service.receiveEvent("hrms", event("E0501", "   ")).applicationId())
                .isEqualTo(1001L);
        service.receiveEvent("hrms", event("E0501", " " + "A".repeat(160) + " "));
        service.receiveEvent("hrms", event("E0501", " AGM (P) "));
        assertThat(db.updates("INSERT INTO leave_application"))
                .extracting(u -> u.args().get(6))
                .containsExactly(null, null, "A".repeat(150), "AGM (P)");
    }

    @Test
    void SRS_LV_25_anEventOfAMemberNoLongerOnTheRollsIsHeldNotApplied() {
        // US-GAP-305 (SRS I-11 Member l.4196: "must match an active Payroll employee"; CLR-69): a
        // back-dated leave of a separated member - even with the settlement open - is held on X-05
        // and referred to HRMS; no task opens.
        db.rows("FROM employee WHERE employee_id = ? AND record_status");
        Received held = service.receiveEvent("hrms", event("E0501", "AGM"));
        assertThat(held.outcome()).isEqualTo(Outcome.HELD);
        assertThat(held.defect()).isEqualTo("MEMBER_INACTIVE");
        assertThat(db.updates("INSERT INTO leave_application")).isEmpty();
        verify(hrms, never()).receive(any());
        // Back on the rolls (the lookup finds the member active): the event opens its task.
        db.rows("FROM employee WHERE employee_id = ? AND record_status", cols(501L));
        assertThat(service.receiveEvent("hrms", event("E0501", "AGM")).outcome())
                .isEqualTo(Outcome.TASK_OPENED);
        assertThat(db.queries("FROM employee WHERE employee_id = ? AND record_status").get(0))
                .contains("employment_status NOT IN ('SEPARATED', 'DISMISSED')");
    }

    @Test
    void SRS_LV_34_anEncashmentWithABlankSanctionIsHeld() {
        assertThat(
                        service.receiveEncashment(
                                        "hrms",
                                        new EncashmentSanction(
                                                "E0501",
                                                "EL",
                                                BigDecimal.TEN,
                                                FROM,
                                                "  ",
                                                null,
                                                false))
                                .defect())
                .isEqualTo("NO_SANCTION_REF");
    }

    @Test
    void BR_LEAVE_003_aBalanceWithoutALeaveTypeIsRefused() {
        db.rows("FROM leave_type_reference WHERE leave_type_code");
        assertThatThrownBy(
                        () ->
                                service.receiveBalance(
                                        "hrms",
                                        new Balance(
                                                "E0501", null, "2026-27", BigDecimal.TEN, FROM)))
                .isInstanceOf(FciException.class);
    }

    @Test
    void SRS_LV_34_anEncashmentWithoutASanctionReferenceIsHeld() {
        assertThat(
                        service.receiveEncashment(
                                        "hrms",
                                        new EncashmentSanction(
                                                "E0501",
                                                "EL",
                                                BigDecimal.TEN,
                                                FROM,
                                                null,
                                                null,
                                                false))
                                .defect())
                .isEqualTo("NO_SANCTION_REF");
    }
}
