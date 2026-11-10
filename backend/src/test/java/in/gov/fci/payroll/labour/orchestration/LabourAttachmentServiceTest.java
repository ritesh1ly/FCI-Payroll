package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.Clock;
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
 * M-09 temporary attachment refusals and effects (plan 20 phase 20e; SRS-2-47, SRS-2-6; D20-12,
 * D20-15): a member, not a leader, attached from today to an active gang of the same payroll, with
 * the membership unchanged through it and no other attachment that day.
 */
class LabourAttachmentServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final long LABOUR = 11L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LabourGangs gangs = mock(LabourGangs.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final LabourAttachmentService service =
            new LabourAttachmentService(
                    jdbc,
                    gangs,
                    mock(Authorization.class),
                    org,
                    approvals,
                    parameters,
                    audit,
                    Clock.systemUTC());

    private static LabourGangs.Gang gang(long id, String type, String status, LocalDate to) {
        return new LabourGangs.Gang(
                id, "G" + id, "Gang " + id, 7L, "DP-DL01", type, status, TODAY.minusYears(1), to);
    }

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(TODAY);
        OrgLocation office = mock(OrgLocation.class);
        when(office.getId()).thenReturn(3L);
        when(org.cycleUnitOf(7L)).thenReturn(Optional.of(office));
        membership("HANDLING", null);
        when(gangs.gang(1L)).thenReturn(gang(1L, "DEPT_LABOUR", "ACTIVE", null));
        when(gangs.gang(2L)).thenReturn(gang(2L, "DEPT_LABOUR", "ACTIVE", null));
        SubmittedApproval item = mock(SubmittedApproval.class);
        when(item.id()).thenReturn(55L);
        when(approvals.submit(any(), any(Submission.class), any())).thenReturn(item);
    }

    private void membership(String role, Date endsAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("GANG_ID", 1L);
        m.put("GANG_ROLE", role);
        m.put("EFFECTIVE_TO", endsAt);
        when(jdbc.queryForList(
                        contains("m.employee_id = ? AND p.labour_status"), any(Object[].class)))
                .thenReturn(List.of(m));
    }

    private static LabourAttachmentService.Request to(
            Long gang, LocalDate from, LocalDate to, String reason) {
        return new LabourAttachmentService.Request(
                gang, from, to, reason, " MEMO/1 ", TODAY, " AM ");
    }

    private void count(String part, Integer n) {
        when(jdbc.queryForObject(contains(part), eq(Integer.class), any(Object[].class)))
                .thenReturn(n);
    }

    private static void defect(ThrowingCallable call, String expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(String.valueOf(e.getMessageArgs()[0])).isEqualTo(expected));
    }

    @Test
    void SRS_2_47_anAttachmentNamesItsGangReasonAndDates() {
        defect(() -> service.propose(context, LABOUR, null), "ENTRY");
        defect(() -> service.propose(context, LABOUR, to(null, TODAY, null, "x")), "ENTRY");
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, null, null)), "REASON");
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, null, " ")), "REASON");
        defect(
                () -> service.propose(context, LABOUR, to(2L, TODAY, null, "x".repeat(501))),
                "REASON");
        defect(
                () -> service.propose(context, LABOUR, to(2L, TODAY.minusDays(1), null, "x")),
                "FROM");
        when(jdbc.queryForList(
                        contains("m.employee_id = ? AND p.labour_status"), any(Object[].class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.propose(context, LABOUR, to(2L, TODAY, null, "x")))
                .isInstanceOf(FciException.class);
    }

    @Test
    void SRS_2_47_aLeaderIsNotAttachedAndTheGangIsAnotherActiveOneOfTheSamePayroll() {
        membership("SARDAR", null);
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, null, "x")), "LEADER");
        membership("ANCILLARY", null);
        defect(() -> service.propose(context, LABOUR, to(1L, TODAY, null, "x")), "TO_GANG");
        when(gangs.gang(2L)).thenReturn(gang(2L, "DEPT_LABOUR", "END_DATED", null));
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, null, "x")), "TO_GANG");
        when(gangs.gang(2L)).thenReturn(gang(2L, "DEPT_LABOUR", "ACTIVE", TODAY.plusDays(1)));
        defect(
                () -> service.propose(context, LABOUR, to(2L, TODAY, TODAY.plusDays(3), "x")),
                "TO_GANG");
        when(gangs.gang(2L)).thenReturn(gang(2L, "NWNP_LABOUR", "ACTIVE", null));
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, null, "x")), "TO_GANG");
        when(org.cycleUnitOf(7L)).thenReturn(Optional.empty());
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, null, "x")), "DEPOT_OFFICE");
    }

    @Test
    void SRS_2_6_theMembershipRunsUnchangedThroughTheAttachmentAndOneGangADay() {
        membership("HANDLING", Date.valueOf(TODAY.plusDays(10)));
        defect(() -> service.propose(context, LABOUR, to(2L, TODAY, null, "x")), "MEMBERSHIP_ENDS");
        defect(
                () -> service.propose(context, LABOUR, to(2L, TODAY, TODAY.plusDays(11), "x")),
                "MEMBERSHIP_ENDS");
        count("effective_from > ?", 1);
        defect(
                () -> service.propose(context, LABOUR, to(2L, TODAY, TODAY.plusDays(5), "x")),
                "LATER_CHANGE");
        count("effective_from > ?", null);
        count("FROM labour_temp_attachment WHERE employee_id", 1);
        defect(
                () -> service.propose(context, LABOUR, to(2L, TODAY, TODAY.plusDays(5), "x")),
                "OVERLAP");
        count("FROM labour_temp_attachment WHERE employee_id", null);
        membership("HANDLING", null);
        when(jdbc.queryForObject(
                        contains("SELECT temp_attachment_id"), eq(Long.class), any(Object[].class)))
                .thenReturn(null);
        assertThatThrownBy(() -> service.propose(context, LABOUR, to(2L, TODAY, null, "x")))
                .isInstanceOf(IllegalStateException.class);
        when(jdbc.queryForObject(
                        contains("SELECT temp_attachment_id"), eq(Long.class), any(Object[].class)))
                .thenReturn(71L);
        service.propose(context, LABOUR, to(2L, TODAY, TODAY.plusDays(5), "Rake"));
        verify(jdbc).update(contains("SET approval_id"), eq(55L), eq(71L));
    }

    private ApprovalDecided decided(String entity, ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                61L, "M-09", entity, "71", outcome, reason, null, "checker", Instant.now(), null);
    }

    private void attachment(Date toDate) {
        Map<String, Object> a = new HashMap<>();
        a.put("ATTACHMENT_REF", "TA-2026-00001");
        a.put("EMPLOYEE_ID", LABOUR);
        a.put("LABOUR_CATEGORY", "HANDLING");
        a.put("FROM_GANG_ID", 1L);
        a.put("TO_GANG_ID", 2L);
        a.put("FROM_DATE", Date.valueOf(TODAY));
        a.put("TO_DATE", toDate);
        when(jdbc.queryForMap(contains("FROM labour_temp_attachment WHERE"), any(Object[].class)))
                .thenReturn(a);
    }

    @Test
    void SRS_2_47_anApprovedAttachmentMovesTheMembershipAndBackARejectedOneOnlyRecordsIt() {
        service.on(decided("labour_transfer", ApprovalStatus.APPROVED, null));
        verify(jdbc, never()).queryForMap(any(String.class), any(Object[].class));
        attachment(Date.valueOf(TODAY.plusDays(3)));
        service.on(decided(LabourAttachmentService.ENTITY, ApprovalStatus.REJECTED, "Not needed"));
        verify(jdbc, never())
                .update(contains("INSERT INTO labour_gang_membership"), any(Object[].class));
        service.on(decided(LabourAttachmentService.ENTITY, ApprovalStatus.APPROVED, null));
        verify(jdbc, times(2))
                .update(contains("INSERT INTO labour_gang_membership"), any(Object[].class));
        attachment(null);
        service.on(decided(LabourAttachmentService.ENTITY, ApprovalStatus.APPROVED, null));
        verify(jdbc, times(3))
                .update(contains("INSERT INTO labour_gang_membership"), any(Object[].class));
    }
}
