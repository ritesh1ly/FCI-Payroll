package in.gov.fci.payroll.workflow.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.ChangeType;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.workflow.api.RoleEquivalence;
import in.gov.fci.payroll.workflow.business.WorkflowRules.ProposedRoute;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A-08 Approval Workflow Master (SRS-22-15, SRS-22-19; KB BR-APPR-001: no approval path is
 * hard-coded). A route is proposed at Headquarters with a reason; the checker's approval brings it
 * into force from its date, today at the earliest, and ends every version still in force on that
 * day the day before, never a version already closed or another proposal; a rejection cancels the
 * proposal, which never routes anything.
 */
class WorkflowMasterServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    private final WorkflowRepositories.Actions actions = mock(WorkflowRepositories.Actions.class);
    private final WorkflowRepositories.Levels levels = mock(WorkflowRepositories.Levels.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final CurrentAccess contexts = mock(CurrentAccess.class);

    private final WorkflowMasterService service =
            new WorkflowMasterService(
                    actions,
                    levels,
                    mock(WorkflowRepositories.Approvals.class),
                    mock(WorkflowRepositories.Inbox.class),
                    mock(RoleEquivalence.class),
                    mock(MakerChecker.class),
                    policy,
                    mock(OrgDirectory.class),
                    audit,
                    contexts,
                    mock(in.gov.fci.payroll.workflow.api.ApproverDirectory.class),
                    (code, day) -> true);

    private final AccessContext admin =
            new AccessContext(
                    1L,
                    "sysadmin@x",
                    "Admin",
                    "s",
                    "P",
                    1L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(1L));

    private long nextId = 10;

    @BeforeEach
    void wire() {
        when(contexts.today()).thenReturn(TODAY);
    }

    private WorkflowAction version(
            int no, LocalDate from, WorkflowAction.Status status, LocalDate to) {
        WorkflowAction a =
                WorkflowAction.propose(
                        "C13_CREATE",
                        "Batch job",
                        "C-13",
                        "C-13",
                        WorkflowAction.OfficeLevel.DO,
                        2,
                        null,
                        false,
                        24,
                        no,
                        from);
        ReflectionTestUtils.setField(a, "id", nextId++);
        ReflectionTestUtils.setField(a, "status", status);
        ReflectionTestUtils.setField(a, "effectiveTo", to);
        return a;
    }

    private static ApprovalDecided decided(long id, ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                5L,
                "A-08",
                WorkflowMasterService.ENTITY,
                String.valueOf(id),
                outcome,
                reason,
                null,
                "checker.hq@x",
                Instant.parse("2026-10-23T06:00:00Z"));
    }

    private static void refused(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private static ProposedRoute route(String code, WorkflowAction.OfficeLevel level) {
        return new ProposedRoute(
                code, "Batch job", "C-13", "C-13", level, 2, null, false, 24, TODAY, List.of());
    }

    @Test
    void SRS_22_19_aRouteIsProposedAtHeadquartersWithAReason() {
        // An incomplete route is a new one (CREATE); a route proposed away from HQ is refused.
        refused(
                () -> service.propose(admin, route(null, null), "New route"),
                ApiErrorCode.ADMIN_HQ_ONLY);
        verify(policy).require(admin, "A-08", "CREATE");
        verify(actions, never()).findByActionCodeAndOfficeLevelOrderByVersionNoDesc(any(), any());

        when(policy.isAtHeadquarters(admin)).thenReturn(true);
        for (String reason : Arrays.asList(null, "", "  ")) {
            refused(
                    () -> service.propose(admin, route("C13_CREATE", null), reason),
                    ApiErrorCode.REASON_REQUIRED);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Collection<AuditService.Entry> audited() {
        ArgumentCaptor<Collection<AuditService.Entry>> entries =
                (ArgumentCaptor) ArgumentCaptor.forClass(Collection.class);
        verify(audit).recordAll(eq("checker.hq@x"), entries.capture());
        return entries.getValue();
    }

    private void history(WorkflowAction... versions) {
        when(actions.findByActionCodeAndOfficeLevelOrderByVersionNoDesc(
                        "C13_CREATE", WorkflowAction.OfficeLevel.DO))
                .thenReturn(List.of(versions));
    }

    /** A route differing from the others only in its target turnaround (TAT) hours. */
    private static WorkflowAction tat(WorkflowAction version, int hours) {
        ReflectionTestUtils.setField(version, "targetTatHours", hours);
        return version;
    }

    /**
     * SRS-20-2: the approved route is audited field by field against the route it replaces on its
     * date (only the changed TAT, never every field as an initial value).
     */
    private static void auditedAgainst(
            Collection<AuditService.Entry> entries, String replacedTat, String reason) {
        assertThat(entries).noneMatch(e -> e.changeType() == ChangeType.INITIAL_VALUE);
        assertThat(entries)
                .filteredOn(e -> e.changeType() != ChangeType.END_DATE)
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(e.fieldName()).isEqualTo("target_tat_hours");
                            assertThat(e.oldValue()).isEqualTo(replacedTat);
                            assertThat(e.newValue()).isEqualTo("48");
                            assertThat(e.reason()).isEqualTo(reason);
                        });
    }

    @Test
    void SRS_22_15_aRouteApprovedAfterItsDateIsInForceFromTodayAndEndsTheCurrentOne() {
        // Proposed to start five days ago; the checker decides only today.
        WorkflowAction proposed =
                tat(
                        version(
                                4,
                                TODAY.minusDays(5),
                                WorkflowAction.Status.PENDING_APPROVAL,
                                null),
                        48);
        WorkflowAction current =
                version(3, TODAY.minusDays(100), WorkflowAction.Status.ACTIVE, null);
        WorkflowAction superseded =
                version(
                        2,
                        TODAY.minusDays(200),
                        WorkflowAction.Status.SUPERSEDED,
                        TODAY.minusDays(101));
        WorkflowAction cancelled =
                version(1, TODAY.minusDays(300), WorkflowAction.Status.CANCELLED, null);
        when(actions.findById(proposed.getId())).thenReturn(Optional.of(proposed));
        history(proposed, current, superseded, cancelled);

        service.on(decided(proposed.getId(), ApprovalStatus.APPROVED, " "));

        assertThat(proposed.getStatus()).isEqualTo(WorkflowAction.Status.ACTIVE);
        assertThat(proposed.getEffectiveFrom()).isEqualTo(TODAY);
        assertThat(current.getEffectiveTo()).isEqualTo(TODAY.minusDays(1));
        assertThat(current.getStatus()).isEqualTo(WorkflowAction.Status.SUPERSEDED);
        assertThat(superseded.getEffectiveTo()).isEqualTo(TODAY.minusDays(101));
        assertThat(cancelled.getEffectiveTo()).isNull();
        // A blank reason is recorded as "Approved"; the closed version carries its own reason.
        Collection<AuditService.Entry> entries = audited();
        auditedAgainst(entries, "24", "Approved");
        assertThat(entries)
                .filteredOn(e -> "effective_to".equals(e.fieldName()))
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(e.changeType()).isEqualTo(ChangeType.END_DATE);
                            assertThat(e.oldValue()).isNull();
                            assertThat(e.newValue()).isEqualTo(TODAY.minusDays(1).toString());
                            assertThat(e.reason()).isEqualTo("Version 3 closed");
                            assertThat(e.effectiveDate()).isEqualTo(TODAY);
                        });
    }

    @Test
    void SRS_22_15_aLaterRouteEndsOnlyTheVersionStillOpenOnItsDate() {
        // v1 already ends on day 4 (v2 was approved from day 5); v3 now starts on day 10.
        WorkflowAction v1 =
                version(1, TODAY.minusDays(100), WorkflowAction.Status.ACTIVE, TODAY.plusDays(4));
        WorkflowAction v2 =
                tat(version(2, TODAY.plusDays(5), WorkflowAction.Status.ACTIVE, null), 36);
        WorkflowAction v3 =
                tat(
                        version(
                                3,
                                TODAY.plusDays(10),
                                WorkflowAction.Status.PENDING_APPROVAL,
                                null),
                        48);
        when(actions.findById(v3.getId())).thenReturn(Optional.of(v3));
        history(v3, v2, v1);

        service.on(decided(v3.getId(), ApprovalStatus.APPROVED, "Two levels from November"));

        assertThat(v3.getEffectiveFrom()).isEqualTo(TODAY.plusDays(10));
        assertThat(v1.getEffectiveTo())
                .as("already ends before day 10")
                .isEqualTo(TODAY.plusDays(4));
        assertThat(v2.getEffectiveTo()).isEqualTo(TODAY.plusDays(9));
        assertThat(v2.getStatus())
                .as("in force from day 5 to day 9")
                .isEqualTo(WorkflowAction.Status.ACTIVE);
        // Compared with v2, the route it replaces on day 10, not recorded as a first version.
        auditedAgainst(audited(), "36", "Two levels from November");
    }

    @Test
    void SRS_22_15_aFutureRouteReplacedBeforeItStartsIsSupersededNotLeftActive() {
        WorkflowAction current =
                version(1, TODAY.minusDays(100), WorkflowAction.Status.ACTIVE, TODAY.plusDays(29));
        WorkflowAction future =
                tat(version(2, TODAY.plusDays(30), WorkflowAction.Status.ACTIVE, null), 36);
        WorkflowAction sooner =
                tat(
                        version(
                                3,
                                TODAY.plusDays(10),
                                WorkflowAction.Status.PENDING_APPROVAL,
                                null),
                        48);
        when(actions.findById(sooner.getId())).thenReturn(Optional.of(sooner));
        history(sooner, future, current);

        service.on(decided(sooner.getId(), ApprovalStatus.APPROVED, null));

        assertThat(future.getStatus())
                .as("never in force: replaced before its start")
                .isEqualTo(WorkflowAction.Status.SUPERSEDED);
        assertThat(future.getEffectiveTo())
                .as("its dates stay: an end before the start is no range (chk_appr_wf_span)")
                .isNull();
        assertThat(current.getEffectiveTo()).isEqualTo(TODAY.plusDays(9));
        assertThat(current.getStatus()).isEqualTo(WorkflowAction.Status.ACTIVE);
        assertThat(sooner.getStatus()).isEqualTo(WorkflowAction.Status.ACTIVE);
        assertThat(sooner.isActiveOn(TODAY.plusDays(30)))
                .as("the router's route on day 30")
                .isTrue();
        // On day 10 the route in force was the current one (24 h), not the future one (36 h).
        Collection<AuditService.Entry> entries = audited();
        auditedAgainst(entries, "24", "Approved");
        // The closures: the current version's new end, the future version's status.
        assertThat(entries)
                .filteredOn(e -> e.changeType() == ChangeType.END_DATE)
                .extracting(
                        AuditService.Entry::fieldName,
                        AuditService.Entry::oldValue,
                        AuditService.Entry::newValue,
                        AuditService.Entry::reason)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "action_status",
                                "ACTIVE",
                                "SUPERSEDED",
                                "Version 2 replaced before it started"),
                        org.assertj.core.groups.Tuple.tuple(
                                "effective_to",
                                TODAY.plusDays(29).toString(),
                                TODAY.plusDays(9).toString(),
                                "Version 1 closed"));
    }

    @Test
    void SRS_22_15_aRejectedOrReturnedProposalIsCancelledAndAnotherScreensDecisionIgnored() {
        WorkflowAction proposed = version(2, TODAY, WorkflowAction.Status.PENDING_APPROVAL, null);
        when(actions.findById(proposed.getId())).thenReturn(Optional.of(proposed));

        service.on(decided(proposed.getId(), ApprovalStatus.RETURNED, "Wrong level count"));

        assertThat(proposed.getStatus()).isEqualTo(WorkflowAction.Status.CANCELLED);
        verify(actions, never()).findByActionCodeAndOfficeLevelOrderByVersionNoDesc(any(), any());
        verify(audit, never()).recordAll(any(), any());

        service.on(
                new ApprovalDecided(
                        6L,
                        "A-04",
                        "access_group",
                        "1",
                        ApprovalStatus.APPROVED,
                        null,
                        null,
                        "checker.hq@x",
                        Instant.parse("2026-10-23T06:00:00Z")));
        verify(actions, never()).findById(1L);
        refused(
                () -> service.on(decided(404L, ApprovalStatus.APPROVED, null)),
                in.gov.fci.payroll.platform.error.CommonErrorCode.NOT_FOUND);
    }

    private static in.gov.fci.payroll.workflow.business.model.ApprovalItem waiting(
            long id, Instant at) {
        in.gov.fci.payroll.workflow.business.model.ApprovalItem item =
                in.gov.fci.payroll.workflow.business.model.ApprovalItem.submit(
                        "I-20",
                        null,
                        2,
                        "off_cycle_payment",
                        "OC-" + id,
                        in.gov.fci.payroll.platform.approval.ActionType.CREATE,
                        "{}",
                        LocalDate.of(2026, 10, 1),
                        "maker",
                        "Order",
                        null,
                        null,
                        at);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    /**
     * UC-07 (l.588), SRS-G-28 / BR-APPR-007 (US-GAP-103): an item with nobody in scope to decide it
     * is on the HQ Administrator's ageing list, oldest first, "until a Checker is assigned" - once
     * its role has a holder at its office other than the maker it leaves the list.
     */
    @Test
    void SRS_G_28_anUnstaffedItemStaysOnTheAgeingListUntilACheckerIsAssigned() {
        WorkflowRepositories.Approvals approvals = mock(WorkflowRepositories.Approvals.class);
        WorkflowRepositories.Inbox inbox = mock(WorkflowRepositories.Inbox.class);
        in.gov.fci.payroll.workflow.api.ApproverDirectory approvers =
                mock(in.gov.fci.payroll.workflow.api.ApproverDirectory.class);
        WorkflowMasterService unstaffed =
                new WorkflowMasterService(
                        actions,
                        levels,
                        approvals,
                        inbox,
                        mock(RoleEquivalence.class),
                        mock(MakerChecker.class),
                        policy,
                        mock(OrgDirectory.class),
                        audit,
                        contexts,
                        approvers,
                        (code, day) -> true);
        in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem older =
                in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem.open(
                        1L, "PAYROLL_CHECKER", 40L, null);
        in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem newer =
                in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem.open(
                        2L, "PAYROLL_CHECKER", 41L, null);
        older.flagNoApprover(true);
        newer.flagNoApprover(true);
        when(inbox.findByStatus(
                        in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem.Status
                                .PENDING))
                .thenReturn(List.of(newer, older));
        Instant t0 = Instant.parse("2026-10-01T05:00:00Z");
        when(approvals.findAllById(any()))
                .thenReturn(List.of(waiting(2L, t0.plusSeconds(3600)), waiting(1L, t0)));

        java.util.concurrent.atomic.AtomicBoolean assigned =
                new java.util.concurrent.atomic.AtomicBoolean();
        when(approvers.staffing())
                .thenReturn(
                        (role, office, maker) ->
                                assigned.get()
                                        && "PAYROLL_CHECKER".equals(role)
                                        && office == 41L
                                        && !"checker".equals(maker));

        assertThat(unstaffed.unstaffed(admin))
                .extracting(WorkflowMasterService.UnstaffedItem::approvalId)
                .containsExactly(1L, 2L);
        // A Checker is assigned at office 41: that item leaves the list.
        assigned.set(true);
        assertThat(unstaffed.unstaffed(admin))
                .extracting(WorkflowMasterService.UnstaffedItem::approvalId)
                .containsExactly(1L);
        // A-08 (Wave A review): today's approvers are read once per list, never once per item.
        org.mockito.Mockito.verify(approvers, org.mockito.Mockito.times(2)).staffing();
        org.mockito.Mockito.verify(approvers, org.mockito.Mockito.never())
                .hasApprover(any(), any(), any());
        // Nothing flagged: nobody is read at all.
        when(inbox.findByStatus(
                        in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem.Status
                                .PENDING))
                .thenReturn(List.of());
        when(approvals.findAllById(any())).thenReturn(List.of());
        assertThat(unstaffed.unstaffed(admin)).isEmpty();
        org.mockito.Mockito.verify(approvers, org.mockito.Mockito.times(2)).staffing();
    }

    /**
     * SRS-22-12: a third level is checked against the matrix in force when the route takes effect,
     * today while the route names no date yet.
     */
    @Test
    void SRS_22_12_aThirdLevelOfARouteWithoutADateIsCheckedAgainstTodaysMatrix() {
        List<LocalDate> asked = new java.util.ArrayList<>();
        WorkflowMasterService checked =
                new WorkflowMasterService(
                        actions,
                        levels,
                        mock(WorkflowRepositories.Approvals.class),
                        mock(WorkflowRepositories.Inbox.class),
                        mock(RoleEquivalence.class),
                        mock(MakerChecker.class),
                        policy,
                        mock(OrgDirectory.class),
                        audit,
                        contexts,
                        mock(in.gov.fci.payroll.workflow.api.ApproverDirectory.class),
                        (code, day) -> {
                            asked.add(day);
                            return false;
                        });
        when(policy.isAtHeadquarters(admin)).thenReturn(true);
        ProposedRoute undated =
                new ProposedRoute(
                        "C13_CREATE",
                        "Batch job",
                        "C-13",
                        "C-13",
                        WorkflowAction.OfficeLevel.DO,
                        3,
                        null,
                        false,
                        24,
                        null,
                        List.of());
        refused(() -> checked.propose(admin, undated, "Third level"), ApiErrorCode.ROUTE_INVALID);
        ProposedRoute dated =
                new ProposedRoute(
                        "C13_CREATE",
                        "Batch job",
                        "C-13",
                        "C-13",
                        WorkflowAction.OfficeLevel.DO,
                        3,
                        null,
                        false,
                        24,
                        TODAY.plusDays(7),
                        List.of());
        refused(() -> checked.propose(admin, dated, "Third level"), ApiErrorCode.ROUTE_INVALID);
        assertThat(asked).containsExactly(TODAY, TODAY.plusDays(7));
    }

    /** SRS-G-28: an unstaffed item of a national task names no office. */
    @Test
    void SRS_G_28_anUnstaffedNationalItemNamesNoOffice() {
        WorkflowRepositories.Approvals approvals = mock(WorkflowRepositories.Approvals.class);
        WorkflowRepositories.Inbox inbox = mock(WorkflowRepositories.Inbox.class);
        in.gov.fci.payroll.workflow.api.ApproverDirectory approvers =
                mock(in.gov.fci.payroll.workflow.api.ApproverDirectory.class);
        OrgDirectory org = mock(OrgDirectory.class);
        WorkflowMasterService unstaffed =
                new WorkflowMasterService(
                        actions,
                        levels,
                        approvals,
                        inbox,
                        mock(RoleEquivalence.class),
                        mock(MakerChecker.class),
                        policy,
                        org,
                        audit,
                        contexts,
                        approvers,
                        (code, day) -> true);
        in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem national =
                in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem.open(
                        1L, "HQ_CHECKER", null, null);
        national.flagNoApprover(true);
        when(inbox.findByStatus(
                        in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem.Status
                                .PENDING))
                .thenReturn(List.of(national));
        when(approvals.findAllById(any()))
                .thenReturn(List.of(waiting(1L, Instant.parse("2026-10-01T05:00:00Z"))));
        when(approvers.staffing()).thenReturn((role, office, maker) -> false);

        assertThat(unstaffed.unstaffed(admin))
                .singleElement()
                .satisfies(i -> assertThat(i.office()).isNull());
        verify(org, never()).location(any());
    }
}
