package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.LabourPersons;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.persistence.GeneratedKeysStub;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M-02 labour transfer refusals and effects (plan 20 phase 20e; SRS-2-6; BR-LABM-007 … 009): a
 * handling member moves to an active gang of the same payroll, once at a time, with the sending
 * month marked, no later work-slip and the receiving month open.
 */
class TransferServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final long LABOUR = 11L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LabourGangs gangs = mock(LabourGangs.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final LabourPersons persons = mock(LabourPersons.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final TransferService service =
            new TransferService(
                    jdbc,
                    gangs,
                    mock(Authorization.class),
                    org,
                    approvals,
                    persons,
                    parameters,
                    audit);

    private static LabourGangs.Gang gang(long id, String type, String status) {
        return new LabourGangs.Gang(
                id, "G" + id, "Gang " + id, 7L, "DP-DL01", type, status, TODAY.minusYears(1), null);
    }

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(TODAY);
        OrgLocation office = mock(OrgLocation.class);
        when(office.getId()).thenReturn(3L);
        when(office.getZoneId()).thenReturn(1L);
        when(org.cycleUnitOf(7L)).thenReturn(Optional.of(office));
        membership("HANDLING", TODAY.minusYears(1));
        when(gangs.gang(1L)).thenReturn(gang(1L, "DEPT_LABOUR", "ACTIVE"));
        when(gangs.gang(2L)).thenReturn(gang(2L, "DEPT_LABOUR", "ACTIVE"));
        when(approvals.submit(any(), any(Submission.class), any()))
                .thenReturn(mock(SubmittedApproval.class));
        marked(100);
    }

    private void membership(String role, LocalDate from) {
        Map<String, Object> m = new HashMap<>();
        m.put("GANG_ID", 1L);
        m.put("GANG_ROLE", role);
        m.put("EFFECTIVE_FROM", Date.valueOf(from));
        when(jdbc.queryForList(contains("ORDER BY m.effective_from DESC"), any(Object[].class)))
                .thenReturn(List.of(m));
    }

    private void marked(Integer n) {
        when(jdbc.queryForObject(
                        contains("FROM labour_attendance WHERE employee_id"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(n);
    }

    private static TransferService.Request to(Long gang, LocalDate date, String reason) {
        return new TransferService.Request(gang, date, reason, "TO/7", TODAY, "Area Manager");
    }

    private static TransferService.Request ordered(
            String ref, LocalDate orderDate, String authority) {
        return new TransferService.Request(2L, TODAY, "x", ref, orderDate, authority);
    }

    private void cycles(String... states) {
        when(jdbc.queryForList(
                        contains("FROM payroll_cycle_instance"),
                        eq(String.class),
                        any(Object[].class)))
                .thenReturn(List.of(states));
    }

    // SRS l.4429 / NWNP l.4937: the transfer starts with the order attached.
    @Test
    void SRS_2_6_aTransferNeedsItsOrder() {
        defect(
                () -> service.propose(context, LABOUR, new TransferService.Request(2L, TODAY, "x")),
                "ORDER");
        defect(() -> service.propose(context, LABOUR, ordered(" ", TODAY, "AM")), "ORDER");
        defect(
                () -> service.propose(context, LABOUR, ordered("R".repeat(101), TODAY, "AM")),
                "ORDER");
        defect(() -> service.propose(context, LABOUR, ordered("TO/1", null, "AM")), "ORDER");
        defect(() -> service.propose(context, LABOUR, ordered("TO/1", TODAY, null)), "ORDER");
        defect(() -> service.propose(context, LABOUR, ordered("TO/1", TODAY, " ")), "ORDER");
        defect(
                () -> service.propose(context, LABOUR, ordered("TO/1", TODAY, "A".repeat(151))),
                "ORDER");
        defect(
                () -> service.propose(context, LABOUR, ordered("TO/1", TODAY.plusDays(1), "AM")),
                "ORDER_DATE");
    }

    // SRS-2-6 "not started or still open"; BR-LABM-007 FRS l.3394; OD-86: only INITIATED is open.
    @Test
    void BR_LABM_007_eachCycleStatePastInitiatedRefusesTheTransfer() {
        for (String state : List.of("FROZEN", "COMPUTED", "VERIFIED", "LOCKED")) {
            cycles("INITIATED", state);
            defect(() -> service.propose(context, LABOUR, to(2L, TODAY, "x")), "RECEIVING_CLOSED");
        }
    }

    // Defect found 2026-11-03 (SC-22-0031): NWNP marks no attendance (D22-1, OD-87).
    @Test
    void SRS_5_14_anNwnpTransferIsNotAskedForAttendance() {
        GeneratedKeysStub.answer(jdbc, "INSERT INTO labour_transfer", 41L);
        when(gangs.gang(1L)).thenReturn(gang(1L, "NWNP_LABOUR", "ACTIVE"));
        when(gangs.gang(2L)).thenReturn(gang(2L, "NWNP_LABOUR", "ACTIVE"));
        marked(0);
        service.propose(context, LABOUR, to(2L, TODAY, "x"));
        verify(approvals).submit(eq(context), any(Submission.class), any());
        verify(jdbc, never())
                .queryForObject(
                        contains("FROM labour_attendance WHERE employee_id"),
                        eq(Integer.class),
                        any(Object[].class));
    }

    @Test
    void SRS_2_6_aDepartmentalTransferIsStillAskedForAttendance() {
        cycles("INITIATED");
        marked(0);
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, "x")), "ATTENDANCE_INCOMPLETE");
    }

    private static void defect(ThrowingCallable call, String expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(String.valueOf(e.getMessageArgs()[0])).isEqualTo(expected));
    }

    @Test
    void SRS_2_6_aTransferNamesItsGangDateAndReason() {
        defect(() -> service.propose(context, LABOUR, null), "ENTRY");
        defect(() -> service.propose(context, LABOUR, to(null, TODAY, "x")), "ENTRY");
        defect(() -> service.propose(context, LABOUR, to(2L, null, "x")), "ENTRY");
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, null)), "REASON");
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, " ")), "REASON");
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, "x".repeat(501))), "REASON");
        when(jdbc.queryForList(contains("ORDER BY m.effective_from DESC"), any(Object[].class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.propose(context, LABOUR, to(2L, TODAY, "x")))
                .isInstanceOf(FciException.class);
    }

    @Test
    void BR_LABM_007_aHandlingMemberMovesToAnActiveGangOfTheSamePayrollOnceAtATime() {
        membership("SARDAR", TODAY.minusYears(1));
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, "x")), "LEADER");
        membership("HANDLING", TODAY.minusYears(1));
        when(gangs.gang(2L)).thenReturn(gang(2L, "DEPT_LABOUR", "END_DATED"));
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, "x")), "TO_GANG");
        when(gangs.gang(2L)).thenReturn(gang(2L, "NWNP_LABOUR", "ACTIVE"));
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, "x")), "TO_GANG");
        when(gangs.gang(2L)).thenReturn(gang(2L, "DEPT_LABOUR", "ACTIVE"));
        when(jdbc.queryForObject(
                        contains("q.status = 'PENDING_APPROVAL'"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(1);
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, "x")), "PENDING");
        when(org.cycleUnitOf(7L)).thenReturn(Optional.empty());
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, "x")), "DEPOT_OFFICE");
    }

    @Test
    void BR_LABM_008_theSendingMonthIsMarkedAndNoLaterSlipExists() {
        marked(null);
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, "x")), "ATTENDANCE_INCOMPLETE");
        marked(3);
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, "x")), "ATTENDANCE_INCOMPLETE");
        // Joined after the first of the month and moving on the first day there: nothing to mark.
        membership("HANDLING", TODAY);
        marked(null);
        when(jdbc.queryForObject(
                        contains("FROM work_slip s JOIN work_slip_participant"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(1);
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, "x")), "WORK_SLIP_AFTER");
        when(jdbc.queryForObject(
                        contains("FROM work_slip s JOIN work_slip_participant"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(null);
        when(jdbc.queryForList(
                        contains("FROM payroll_cycle_instance"),
                        eq(String.class),
                        any(Object[].class)))
                .thenReturn(List.of("LOCKED"));
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, "x")), "RECEIVING_CLOSED");
    }

    // Found 2026-10-30: a transfer on the first of next month still needs this month marked.
    @Test
    void BR_LABM_008_aTransferOnTheFirstOfNextMonthChecksThisMonthUpToToday() {
        membership("HANDLING", TODAY.minusMonths(6));
        marked(0);
        defect(
                () -> service.propose(context, LABOUR, to(2L, LocalDate.of(2026, 11, 1), "x")),
                "ATTENDANCE_INCOMPLETE");
        verify(jdbc)
                .queryForObject(
                        contains("FROM labour_attendance WHERE employee_id"),
                        eq(Integer.class),
                        any(),
                        any(),
                        eq(Date.valueOf(LocalDate.of(2026, 10, 1))),
                        eq(Date.valueOf(TODAY)));
    }

    @Test
    void SRS_2_6_aValidTransferGoesToTheCheckerAndAMissingRowFails() {
        GeneratedKeysStub.answer(jdbc, "INSERT INTO labour_transfer", null);
        assertThatThrownBy(() -> service.propose(context, LABOUR, to(2L, TODAY.plusDays(10), "x")))
                .isInstanceOf(IllegalStateException.class);
        GeneratedKeysStub.answer(jdbc, "INSERT INTO labour_transfer", 81L);
        service.propose(context, LABOUR, to(2L, TODAY.plusDays(10), "Shed 4"));
        verify(approvals).submit(eq(context), any(Submission.class), eq(null));
    }

    @Test
    void SRS_4_8_aDpsLabourMovesOnlyOnceItsEarlierBookingSlipsAreDecided() {
        when(gangs.gang(1L)).thenReturn(gang(1L, "DPS_LABOUR", "ACTIVE"));
        when(gangs.gang(2L)).thenReturn(gang(2L, "DPS_LABOUR", "ACTIVE"));
        when(jdbc.queryForObject(contains("'DRAFT'"), eq(Integer.class), any(Object[].class)))
                .thenReturn(2);
        defect(
                () -> service.propose(context, LABOUR, to(2L, TODAY.plusDays(10), "x")),
                "WORK_SLIPS_INCOMPLETE");
        verify(approvals, never()).submit(any(), any(Submission.class), any());
        // None open, or no count read back: the transfer goes ahead.
        GeneratedKeysStub.answer(jdbc, "INSERT INTO labour_transfer", 81L);
        when(jdbc.queryForObject(contains("'DRAFT'"), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
        service.propose(context, LABOUR, to(2L, TODAY.plusDays(10), "Shed 4"));
        when(jdbc.queryForObject(contains("'DRAFT'"), eq(Integer.class), any(Object[].class)))
                .thenReturn(null);
        service.propose(context, LABOUR, to(2L, TODAY.plusDays(10), "Shed 4"));
        verify(approvals, times(2)).submit(eq(context), any(Submission.class), eq(null));
    }

    @Test
    void SRS_4_8_aDepartmentalLabourIsNotAskedForBookingSlips() {
        GeneratedKeysStub.answer(jdbc, "INSERT INTO labour_transfer", 81L);
        service.propose(context, LABOUR, to(2L, TODAY.plusDays(10), "Shed 4"));
        verify(jdbc, never())
                .queryForObject(contains("'DRAFT'"), eq(Integer.class), any(Object[].class));
    }

    private ApprovalDecided decided(String entity, ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                91L, "M-02", entity, "81", outcome, reason, null, "checker", Instant.now(), null);
    }

    @Test
    void BR_LABM_009_anApprovedTransferMovesMembershipDepotAndOfficeARejectedOneNothing() {
        service.on(decided("labour_light_duty", ApprovalStatus.APPROVED, null));
        Map<String, Object> t = new HashMap<>();
        t.put("EMPLOYEE_ID", LABOUR);
        t.put("FROM_GANG_ID", 1L);
        t.put("TO_GANG_ID", 2L);
        t.put("TO_DEPOT_ID", 7L);
        t.put("TRANSFER_DATE", Date.valueOf(TODAY));
        when(jdbc.queryForMap(contains("FROM labour_transfer WHERE"), any(Object[].class)))
                .thenReturn(t);
        service.on(decided(TransferService.ENTITY, ApprovalStatus.REJECTED, "Not now"));
        verify(persons, never()).move(anyLong(), any(), any(), any(), any());
        service.on(decided(TransferService.ENTITY, ApprovalStatus.APPROVED, null));
        verify(persons).move(LABOUR, 7L, 7L, 1L, null);
    }
}
