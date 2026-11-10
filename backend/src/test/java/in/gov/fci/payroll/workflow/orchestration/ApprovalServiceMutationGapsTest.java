package in.gov.fci.payroll.workflow.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalAdvanced;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalDeciding;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.AttachmentPresence;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.workflow.api.ApprovalSubjects;
import in.gov.fci.payroll.workflow.api.ApproverDirectory;
import in.gov.fci.payroll.workflow.api.RoleEquivalence;
import in.gov.fci.payroll.workflow.api.SecondFactor;
import in.gov.fci.payroll.workflow.business.model.ApprovalDecision;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction.OfficeLevel;
import in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem;
import in.gov.fci.payroll.workflow.business.model.WorkflowLevel;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Kills the PIT survivors of {@link ApprovalService} (R-MC, SRS-G-1, SRS-G-13, SRS-G-21, SRS-G-31,
 * SRS-22-16, BR-APPR-003 / 007): what a submission and every decision write (task, audit, events),
 * the order of the denial checks, the office a level is decided at and the fields of an inbox row.
 */
class ApprovalServiceMutationGapsTest {

    private static final Instant NOW = Instant.parse("2026-11-04T05:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    private static final long ACTION_ID = 30L;
    private static final long OFFICE = 40L;

    private final AttachmentPresence attachments = mock(AttachmentPresence.class);
    private final WorkflowRepositories.Actions actions = mock(WorkflowRepositories.Actions.class);
    private final WorkflowRepositories.Levels levels = mock(WorkflowRepositories.Levels.class);
    private final WorkflowRepositories.Approvals approvals =
            mock(WorkflowRepositories.Approvals.class);
    private final WorkflowRepositories.Decisions decisions =
            mock(WorkflowRepositories.Decisions.class);
    private final WorkflowRepositories.Inbox inbox = mock(WorkflowRepositories.Inbox.class);
    private final WorkflowRepositories.ScreenActions inventory =
            mock(WorkflowRepositories.ScreenActions.class);
    private final Authorization policy = mock(Authorization.class);
    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final SecondFactor secondFactor = mock(SecondFactor.class);
    private final RoleEquivalence roles = mock(RoleEquivalence.class);
    private final ApproverDirectory approvers = mock(ApproverDirectory.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ApprovalSubjects subjectOwner = mock(ApprovalSubjects.class);
    private final AccessContext maker = mock(AccessContext.class);
    private final AccessContext checker = mock(AccessContext.class);

    private ApprovalService service;
    private long nextItemId = 500;

    @BeforeEach
    void setUp() {
        service =
                new ApprovalService(
                        actions,
                        levels,
                        approvals,
                        decisions,
                        inbox,
                        inventory,
                        policy,
                        contexts,
                        org,
                        secondFactor,
                        roles,
                        approvers,
                        List.of(subjectOwner),
                        audit,
                        events,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        mock(ApiProperties.class),
                        attachments);
        when(contexts.today()).thenReturn(TODAY);
        when(secondFactor.required()).thenReturn(true);
        when(maker.userId()).thenReturn(1L);
        when(maker.loginId()).thenReturn("maker");
        when(maker.roleCodes()).thenReturn(Set.of("PAYROLL_MAKER"));
        when(maker.dataScope()).thenReturn(Set.of(OFFICE));
        when(checker.userId()).thenReturn(2L);
        when(checker.loginId()).thenReturn("checker");
        when(checker.roleCodes()).thenReturn(Set.of("PAYROLL_CHECKER"));
        when(checker.dataScope()).thenReturn(Set.of(OFFICE));
        when(roles.actsAs(anyString(), anyString()))
                .thenAnswer(i -> i.getArgument(0).equals(i.getArgument(1)));
        when(approvals.save(any()))
                .thenAnswer(
                        i -> {
                            ApprovalItem saved = i.getArgument(0);
                            if (saved.getId() == null) {
                                ReflectionTestUtils.setField(saved, "id", nextItemId++);
                            }
                            return saved;
                        });
        when(approvers.hasApprover(any(), any(), any())).thenReturn(true);
        when(subjectOwner.subjectUserId(any(), any())).thenReturn(Optional.empty());
    }

    private static ErrorCode code(Throwable t) {
        return ((FciException) t).getErrorCode();
    }

    private static void refused(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private WorkflowAction route(
            int levelCount, OfficeLevel firstApproverOffice, OtpGatedAction otp, Integer tat) {
        WorkflowAction a =
                WorkflowAction.propose(
                        "I20_CREATE",
                        "Off-cycle",
                        "GENERAL",
                        "I-20",
                        OfficeLevel.DO,
                        levelCount,
                        otp,
                        false,
                        tat,
                        1,
                        TODAY.minusDays(1));
        a.approve(TODAY.minusDays(1));
        ReflectionTestUtils.setField(a, "id", ACTION_ID);
        when(actions.findByActionCodeOrderByVersionNoDesc("I20_CREATE")).thenReturn(List.of(a));
        when(actions.findById(ACTION_ID)).thenReturn(Optional.of(a));
        List<WorkflowLevel> path = new java.util.ArrayList<>();
        path.add(WorkflowLevel.of(ACTION_ID, 1, "PAYROLL_MAKER", OfficeLevel.DO, false));
        path.add(WorkflowLevel.of(ACTION_ID, 2, "PAYROLL_CHECKER", firstApproverOffice, false));
        if (levelCount == 3) {
            path.add(WorkflowLevel.of(ACTION_ID, 3, "DDO", OfficeLevel.RO, false));
        }
        when(levels.findByWorkflowActionIdOrderByLevelNo(ACTION_ID)).thenReturn(path);
        return a;
    }

    private static Submission submission() {
        return new Submission(
                "I20_CREATE",
                "I-20",
                "CREATE",
                "off_cycle_payment",
                "OC-1",
                ActionType.CREATE,
                OFFICE,
                "{\"a\":1}",
                TODAY,
                "Monthly claims",
                new BigDecimal("1250.50"));
    }

    private ApprovalItem pending(int levelCount, int currentLevel) {
        ApprovalItem item =
                ApprovalItem.submit(
                        "I-20",
                        ACTION_ID,
                        levelCount,
                        "off_cycle_payment",
                        "OC-1",
                        ActionType.CREATE,
                        "{\"a\":1}",
                        TODAY,
                        "maker",
                        "Order 3",
                        new BigDecimal("99.00"),
                        null,
                        NOW.minus(Duration.ofDays(3)));
        ReflectionTestUtils.setField(item, "id", 5L);
        for (int i = 2; i < currentLevel; i++) {
            item.advance();
        }
        when(approvals.findById(5L)).thenReturn(Optional.of(item));
        return item;
    }

    private WorkflowInboxItem task(String role, Long office) {
        WorkflowInboxItem task = WorkflowInboxItem.open(5L, role, office, null);
        when(inbox.findByApprovalId(5L)).thenReturn(Optional.of(task));
        return task;
    }

    // ------------------------------------------------------- actionInForce

    @Test
    void SRS_22_15_theActionInForceIsTheNewestVersionActiveToday() {
        WorkflowAction expired = version(3, TODAY.minusDays(50), TODAY.minusDays(1));
        WorkflowAction future = version(2, TODAY.plusDays(1), null);
        WorkflowAction inForce = version(1, TODAY.minusDays(60), null);
        when(actions.findByActionCodeOrderByVersionNoDesc("X_Y"))
                .thenReturn(List.of(expired, future, inForce));
        assertThat(service.actionInForce("X_Y")).containsSame(inForce);
        when(actions.findByActionCodeOrderByVersionNoDesc("X_Z"))
                .thenReturn(List.of(expired, future));
        assertThat(service.actionInForce("X_Z")).isEmpty();
    }

    private WorkflowAction version(int no, LocalDate from, LocalDate to) {
        WorkflowAction a =
                WorkflowAction.propose(
                        "X_Y",
                        "n",
                        "GENERAL",
                        "I-20",
                        OfficeLevel.DO,
                        2,
                        null,
                        false,
                        null,
                        no,
                        from);
        ReflectionTestUtils.setField(a, "status", WorkflowAction.Status.ACTIVE);
        ReflectionTestUtils.setField(a, "effectiveTo", to);
        return a;
    }

    // -------------------------------------------------------------- submit

    @Test
    void R_MC_aSubmissionIsCheckedAtItsOfficeSavedRoutedToTheFirstCheckerAndAudited() {
        route(2, OfficeLevel.PAYING_UNIT, null, 48);

        SubmittedApproval submitted = service.submit(maker, submission(), null);

        verify(policy).requireAt(maker, "I-20", "CREATE", OFFICE);
        ArgumentCaptor<ApprovalItem> saved = ArgumentCaptor.forClass(ApprovalItem.class);
        verify(approvals).save(saved.capture());
        ApprovalItem item = saved.getValue();
        assertThat(item.getModuleCode()).isEqualTo("I-20");
        assertThat(item.getWorkflowActionId()).isEqualTo(ACTION_ID);
        assertThat(item.getLevelCount()).isEqualTo(2);
        assertThat(item.getEntityName()).isEqualTo("off_cycle_payment");
        assertThat(item.getEntityKey()).isEqualTo("OC-1");
        assertThat(item.getPayload()).isEqualTo("{\"a\":1}");
        assertThat(item.getSubmittedBy()).isEqualTo("maker");
        assertThat(item.getImpactValue()).isEqualByComparingTo("1250.50");
        assertThat(item.getSubmittedAt()).isEqualTo(NOW);
        assertThat(submitted.id()).isEqualTo(item.getId());
        assertThat(submitted.status()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);

        ArgumentCaptor<WorkflowInboxItem> task = ArgumentCaptor.forClass(WorkflowInboxItem.class);
        verify(inbox).save(task.capture());
        assertThat(task.getValue().getAssignedRoleCode()).isEqualTo("PAYROLL_CHECKER");
        assertThat(task.getValue().getOrgScopeId())
                .as("paying unit: the item's own")
                .isEqualTo(OFFICE);
        assertThat(task.getValue().getSlaDueAt()).isEqualTo(NOW.plus(Duration.ofHours(48)));
        assertThat(task.getValue().isNoApprover()).isFalse();

        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("maker"), entry.capture());
        assertThat(entry.getValue().entityName()).isEqualTo("off_cycle_payment");
        assertThat(entry.getValue().entityKey()).isEqualTo("OC-1");
        assertThat(entry.getValue().fieldName()).isEqualTo("CREATE");
        assertThat(entry.getValue().oldValue()).isNull();
        assertThat(entry.getValue().newValue()).isEqualTo("PENDING_APPROVAL");
        assertThat(entry.getValue().reason()).isEqualTo("Monthly claims");
        assertThat(entry.getValue().approvalId()).isEqualTo(item.getId());
        assertThat(entry.getValue().moduleCode()).isEqualTo("I-20");
    }

    @Test
    void BR_APPR_007_aTaskWithNobodyButTheMakerToDecideItIsFlaggedToTheHqAdministrator() {
        route(2, OfficeLevel.PAYING_UNIT, null, null);
        when(approvers.hasApprover("PAYROLL_CHECKER", OFFICE, "maker")).thenReturn(false);

        service.submit(maker, submission(), null);

        ArgumentCaptor<WorkflowInboxItem> task = ArgumentCaptor.forClass(WorkflowInboxItem.class);
        verify(inbox).save(task.capture());
        assertThat(task.getValue().isNoApprover()).isTrue();
        assertThat(task.getValue().getSlaDueAt()).as("no target, no due time").isNull();
        ArgumentCaptor<ApprovalItem> saved = ArgumentCaptor.forClass(ApprovalItem.class);
        verify(approvals, times(2)).save(saved.capture());
        assertThat(saved.getValue().isNoApproverInScope()).isTrue();
        assertThat(saved.getValue().getFlaggedToHqAdminAt()).isEqualTo(NOW);
    }

    @Test
    void BR_APPR_007_aTaskWithAnApproverIsNotFlaggedAndTheItemIsSavedOnce() {
        route(2, OfficeLevel.PAYING_UNIT, null, null);
        service.submit(maker, submission(), null);
        verify(approvals, times(1)).save(any());
        ArgumentCaptor<WorkflowInboxItem> task = ArgumentCaptor.forClass(WorkflowInboxItem.class);
        verify(inbox).save(task.capture());
        assertThat(task.getValue().isNoApprover()).isFalse();
    }

    @Test
    void R_MC_theOfficeOfTheFirstCheckerFollowsTheLevelsOfficeLevel() {
        OrgLocation hq = mock(OrgLocation.class);
        when(hq.getId()).thenReturn(1L);
        when(org.headquarters()).thenReturn(Optional.of(hq));
        OrgLocation zone = mock(OrgLocation.class);
        when(zone.getId()).thenReturn(11L);
        when(org.ancestorOrSelf(OFFICE, LocationType.ZO)).thenReturn(Optional.of(zone));
        OrgLocation region = mock(OrgLocation.class);
        when(region.getId()).thenReturn(22L);
        when(org.ancestorOrSelf(OFFICE, LocationType.RO)).thenReturn(Optional.of(region));
        OrgLocation district = mock(OrgLocation.class);
        when(district.getId()).thenReturn(33L);
        when(org.ancestorOrSelf(OFFICE, LocationType.DO)).thenReturn(Optional.of(district));

        assertThat(firstCheckerOffice(OfficeLevel.HQ)).isEqualTo(1L);
        assertThat(firstCheckerOffice(OfficeLevel.ZO)).isEqualTo(11L);
        assertThat(firstCheckerOffice(OfficeLevel.RO)).isEqualTo(22L);
        assertThat(firstCheckerOffice(OfficeLevel.DO)).isEqualTo(33L);
        assertThat(firstCheckerOffice(OfficeLevel.PAYING_UNIT)).isEqualTo(OFFICE);
        assertThat(firstCheckerOffice(OfficeLevel.OWN_CYCLE_INSTANCE)).isEqualTo(OFFICE);
    }

    @Test
    void R_MC_anAncestorThatIsNotFoundLeavesTheItemsOwnOffice() {
        when(org.headquarters()).thenReturn(Optional.empty());
        assertThat(firstCheckerOffice(OfficeLevel.HQ)).isEqualTo(OFFICE);
        assertThat(firstCheckerOffice(OfficeLevel.ZO)).isEqualTo(OFFICE);
    }

    private Long firstCheckerOffice(OfficeLevel level) {
        route(2, level, null, null);
        clearInvocations(inbox);
        service.submit(maker, submission(), null);
        ArgumentCaptor<WorkflowInboxItem> task = ArgumentCaptor.forClass(WorkflowInboxItem.class);
        verify(inbox).save(task.capture());
        return task.getValue().getOrgScopeId();
    }

    @Test
    void R_MC_aSubmitterWhoDoesNotHoldTheFirstLevelRoleFindsNoApprovalRule() {
        route(2, OfficeLevel.DO, null, null);
        when(maker.roleCodes()).thenReturn(Set.of("SOMETHING_ELSE"));
        refused(() -> service.submit(maker, submission(), null), ApiErrorCode.NO_APPROVAL_RULE);
        verify(approvals, never()).save(any());
        verify(inbox, never()).save(any());
    }

    @Test
    void R_MC_aSubmissionWithoutARouteInForceFindsNoApprovalRule() {
        when(actions.findByActionCodeOrderByVersionNoDesc("I20_CREATE")).thenReturn(List.of());
        refused(() -> service.submit(maker, submission(), null), ApiErrorCode.NO_APPROVAL_RULE);
    }

    @Test
    void SRS_15_29_aSecondSubmissionOfARecordWithAPendingChangeNamesTheFirstSubmitter() {
        route(2, OfficeLevel.DO, null, null);
        ApprovalItem first = pending(2, 1);
        when(approvals.findFirstByEntityNameAndEntityKeyAndStatusOrderBySubmittedAtDesc(
                        "off_cycle_payment", "OC-1", ApprovalStatus.PENDING_APPROVAL))
                .thenReturn(Optional.of(first));
        assertThatThrownBy(() -> service.submit(maker, submission(), null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.ALREADY_PENDING_BY);
                            assertThat(e.getMessageArgs())
                                    .containsExactly("maker", first.getSubmittedAt().toString());
                        });
        verify(approvals, never()).save(any());
    }

    // -------------------------------------------------------------- decide

    @Test
    void R_MC_aFinalApprovalWritesTheDecisionClosesTheTaskPublishesAndAudits() {
        route(2, OfficeLevel.DO, null, null);
        ApprovalItem item = pending(2, 2);
        WorkflowInboxItem task = task("PAYROLL_CHECKER", OFFICE);

        ApprovalItem decided =
                service.decide(checker, 5L, ApprovalService.Decision.APPROVE, null, null);

        assertThat(decided).isSameAs(item);
        assertThat(item.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(item.getDecidedBy()).isEqualTo("checker");
        assertThat(task.getStatus()).isEqualTo(WorkflowInboxItem.Status.COMPLETED);
        verify(inbox).save(task);
        verify(approvals).save(item);
        ArgumentCaptor<ApprovalDecision> decision = ArgumentCaptor.forClass(ApprovalDecision.class);
        verify(decisions).save(decision.capture());
        assertThat(decision.getValue().getDecidedBy()).isEqualTo("checker");
        verify(events)
                .publishEvent(
                        new ApprovalDeciding(
                                5L, "I-20", "off_cycle_payment", "OC-1", "{\"a\":1}", "checker"));
        verify(events)
                .publishEvent(
                        new ApprovalDecided(
                                5L,
                                "I-20",
                                "off_cycle_payment",
                                "OC-1",
                                ApprovalStatus.APPROVED,
                                null,
                                "{\"a\":1}",
                                "checker",
                                NOW,
                                null));
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("checker"), entry.capture());
        assertThat(entry.getValue().fieldName()).isEqualTo("DECISION");
        assertThat(entry.getValue().oldValue()).isEqualTo("PENDING_APPROVAL");
        assertThat(entry.getValue().newValue()).isEqualTo("APPROVED");
        assertThat(entry.getValue().reason()).isEqualTo("Approved");
        assertThat(entry.getValue().approvalId()).isEqualTo(5L);
        assertThat(entry.getValue().moduleCode()).isEqualTo("I-20");
    }

    @Test
    void R_MC_aRejectionAndAReturnCarryTheCheckersReasonAndCloseTheTaskAccordingly() {
        route(2, OfficeLevel.DO, null, null);
        ApprovalItem rejected = pending(2, 2);
        WorkflowInboxItem rejectedTask = task("PAYROLL_CHECKER", OFFICE);
        service.decide(checker, 5L, ApprovalService.Decision.REJECT, "Not eligible", null);
        assertThat(rejected.getStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(rejectedTask.getStatus()).isEqualTo(WorkflowInboxItem.Status.COMPLETED);
        verify(events, never()).publishEvent(any(ApprovalDeciding.class));
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("checker"), entry.capture());
        assertThat(entry.getValue().newValue()).isEqualTo("REJECTED");
        assertThat(entry.getValue().reason()).isEqualTo("Not eligible");

        ApprovalItem returned = pending(2, 2);
        WorkflowInboxItem returnedTask = task("PAYROLL_CHECKER", OFFICE);
        service.decide(checker, 5L, ApprovalService.Decision.RETURN, "Fix the order", null);
        assertThat(returned.getStatus()).isEqualTo(ApprovalStatus.RETURNED);
        assertThat(returnedTask.getStatus()).isEqualTo(WorkflowInboxItem.Status.RETURNED);
    }

    @Test
    void R_MC_aDecisionOnAnUnknownItemOrWithoutATaskIsNotFound() {
        when(approvals.findById(404L)).thenReturn(Optional.empty());
        refused(
                () -> service.decide(checker, 404L, ApprovalService.Decision.APPROVE, null, null),
                CommonErrorCode.NOT_FOUND);
        pending(2, 2);
        when(inbox.findByApprovalId(5L)).thenReturn(Optional.empty());
        refused(
                () -> service.decide(checker, 5L, ApprovalService.Decision.APPROVE, null, null),
                CommonErrorCode.NOT_FOUND);
        verify(decisions, never()).save(any());
    }

    @Test
    void SRS_22_16_aMiddleLevelApprovalMovesTheTaskOnFlagsItAndPublishesTheAdvance() {
        route(3, OfficeLevel.DO, null, 24);
        ApprovalItem item = pending(3, 2);
        WorkflowInboxItem task = task("PAYROLL_CHECKER", OFFICE);
        when(approvers.hasApprover("DDO", null, "maker")).thenReturn(false);
        OrgLocation region = mock(OrgLocation.class);
        when(region.getId()).thenReturn(22L);
        when(org.ancestorOrSelf(OFFICE, LocationType.RO)).thenReturn(Optional.of(region));
        when(approvers.hasApprover("DDO", 22L, "maker")).thenReturn(false);

        ApprovalItem result =
                service.decide(checker, 5L, ApprovalService.Decision.APPROVE, "Verified", null);

        assertThat(result).isSameAs(item);
        assertThat(item.getCurrentLevel()).isEqualTo(3);
        assertThat(item.getStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
        assertThat(task.getAssignedRoleCode()).isEqualTo("DDO");
        assertThat(task.getOrgScopeId()).isEqualTo(22L);
        assertThat(task.getSlaDueAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(task.getEscalationLevel()).isEqualTo(1);
        assertThat(task.isNoApprover()).as("nobody holds DDO there").isTrue();
        assertThat(item.isNoApproverInScope()).isTrue();
        verify(inbox).save(task);
        verify(events)
                .publishEvent(
                        new ApprovalAdvanced(
                                5L, "I-20", "off_cycle_payment", "OC-1", 2, "checker", null, NOW));
        verify(events, never()).publishEvent(any(ApprovalDecided.class));
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("checker"), entry.capture());
        assertThat(entry.getValue().fieldName()).isEqualTo("LEVEL");
        assertThat(entry.getValue().oldValue()).isEqualTo("2");
        assertThat(entry.getValue().newValue()).isEqualTo("3");
        assertThat(entry.getValue().reason()).isEqualTo("Verified");
    }

    @Test
    void R_MC_theOwnersPreconditionsAreAskedWhenTheRouteHasNoFurtherLevelEvenIfTheItemCountsMore() {
        // the item counts 3 levels, the route has 2: the decision at level 2 is the last
        route(2, OfficeLevel.DO, null, null);
        pending(3, 2);
        task("PAYROLL_CHECKER", OFFICE);
        service.decide(checker, 5L, ApprovalService.Decision.APPROVE, null, null);
        verify(events).publishEvent(any(ApprovalDeciding.class));
    }

    @Test
    void R_MC_aMiddleLevelOfAThreeLevelRouteIsNotAskedForThePreconditions() {
        route(3, OfficeLevel.DO, null, null);
        pending(3, 2);
        task("PAYROLL_CHECKER", OFFICE);
        service.decide(checker, 5L, ApprovalService.Decision.APPROVE, null, null);
        verify(events, never()).publishEvent(any(ApprovalDeciding.class));
    }

    // -------------------------------------------------------------- denial

    @Test
    void R_MC_denialChecksComeInTheDocumentedOrder() {
        route(3, OfficeLevel.DO, null, null);
        ApprovalItem item = pending(3, 2);
        WorkflowInboxItem task = task("PAYROLL_CHECKER", OFFICE);
        when(policy.denialAt(any(), anyString(), anyString(), any())).thenReturn(null);

        assertThat(service.denial(checker, item, task)).isNull();
        // the delegate gets the screen, the decision permission and the task's office
        verify(policy).denialAt(checker, "I-20", "APPROVE", OFFICE);

        // maker of the item
        assertThat(service.denial(maker, item, task)).isEqualTo(ApiErrorCode.MAKER_CHECKER);

        // the subject of the change
        when(subjectOwner.subjectUserId("off_cycle_payment", 5L)).thenReturn(Optional.of(2L));
        assertThat(service.denial(checker, item, task)).isEqualTo(ApiErrorCode.SELF_APPROVAL);
        when(subjectOwner.subjectUserId("off_cycle_payment", 5L)).thenReturn(Optional.of(77L));
        assertThat(service.denial(checker, item, task)).isNull();

        // the level's role
        WorkflowInboxItem other = WorkflowInboxItem.open(5L, "DDO", OFFICE, null);
        assertThat(service.denial(checker, item, other)).isEqualTo(ApiErrorCode.ROLE_NOT_HELD);

        // a closed item
        item.decide(ApprovalStatus.REJECTED, "x", "r", NOW);
        assertThat(service.denial(checker, item, task)).isEqualTo(ApiErrorCode.NOT_PENDING);
    }

    @Test
    void R_MC_aDeciderOfAnEarlierLevelMayNotDecideAThirdLevelButMayTheSecond() {
        route(3, OfficeLevel.DO, null, null);
        ApprovalItem atThree = pending(3, 3);
        WorkflowInboxItem task = task("PAYROLL_CHECKER", OFFICE);
        ApprovalDecision earlier = mock(ApprovalDecision.class);
        when(earlier.getDecidedBy()).thenReturn("checker");
        when(decisions.findByApprovalIdOrderByDecidedAt(5L)).thenReturn(List.of(earlier));
        assertThat(service.denial(checker, atThree, task)).isEqualTo(ApiErrorCode.MAKER_CHECKER);

        ApprovalItem atTwo = pending(3, 2);
        assertThat(service.denial(checker, atTwo, task)).isNull();
        verify(decisions, times(1)).findByApprovalIdOrderByDecidedAt(5L);
    }

    @Test
    void W_1_anItemOfHeadquartersOrOfNoOfficeIsDecidedAtHeadquartersOnly() {
        route(2, OfficeLevel.DO, null, null);
        ApprovalItem item = pending(2, 2);
        OrgLocation hq = mock(OrgLocation.class);
        when(hq.getId()).thenReturn(1L);
        when(org.headquarters()).thenReturn(Optional.of(hq));
        WorkflowInboxItem hqTask = WorkflowInboxItem.open(5L, "PAYROLL_CHECKER", 1L, null);
        when(policy.isAtHeadquarters(checker)).thenReturn(false);
        assertThat(service.denial(checker, item, hqTask)).isEqualTo(ApiErrorCode.HQ_ONLY);
        when(policy.isAtHeadquarters(checker)).thenReturn(true);
        assertThat(service.denial(checker, item, hqTask)).isNull();

        WorkflowInboxItem national = WorkflowInboxItem.open(5L, "PAYROLL_CHECKER", null, null);
        when(policy.isAtHeadquarters(checker)).thenReturn(false);
        assertThat(service.denial(checker, item, national)).isEqualTo(ApiErrorCode.OUT_OF_SCOPE);
        when(policy.isAtHeadquarters(checker)).thenReturn(true);
        assertThat(service.denial(checker, item, national)).isNull();

        // an office task away from Headquarters is not an HQ item
        when(policy.isAtHeadquarters(checker)).thenReturn(false);
        WorkflowInboxItem office = WorkflowInboxItem.open(5L, "PAYROLL_CHECKER", OFFICE, null);
        assertThat(service.denial(checker, item, office)).isNull();
    }

    @Test
    void R_MC_theScopeRefusalOfThePolicyIsReturnedAsIs() {
        route(2, OfficeLevel.DO, null, null);
        ApprovalItem item = pending(2, 2);
        WorkflowInboxItem task = task("PAYROLL_CHECKER", OFFICE);
        when(policy.denialAt(checker, "I-20", "APPROVE", OFFICE))
                .thenReturn(ApiErrorCode.OUT_OF_SCOPE);
        assertThat(service.denial(checker, item, task)).isEqualTo(ApiErrorCode.OUT_OF_SCOPE);
    }

    // ----------------------------------------------------------- documents

    @Test
    void SRS_G_31_theDocumentsAndOneDocumentAreReadForTheItemsRecord() {
        pending(2, 2);
        List<AttachmentPresence.AttachedFile> files =
                List.of(
                        new AttachmentPresence.AttachedFile(
                                9L, "o.pdf", "Order", "R/1", TODAY, "HQ", "maker", NOW));
        when(attachments.files("off_cycle_payment", "OC-1")).thenReturn(files);
        AttachmentPresence.FileContent content =
                new AttachmentPresence.FileContent("o.pdf", "application/pdf", new byte[] {1});
        when(attachments.content("maker", "10.1.1.1", "off_cycle_payment", "OC-1", 9L))
                .thenReturn(content);
        assertThat(service.documents(maker, 5L)).isSameAs(files);
        assertThat(service.document(maker, "10.1.1.1", 5L, 9L)).isSameAs(content);
    }

    @Test
    void SRS_G_31_aCheckerNotInTheItemsOfficeOrRoleFindsNothing() {
        pending(2, 2);
        task("PAYROLL_CHECKER", 99L);
        refused(() -> service.documents(checker, 5L), CommonErrorCode.NOT_FOUND);
        task("DDO", OFFICE);
        refused(() -> service.documents(checker, 5L), CommonErrorCode.NOT_FOUND);
        task("PAYROLL_CHECKER", OFFICE);
        assertThat(service.documents(checker, 5L)).isNotNull();
    }

    // --------------------------------------------------------- inbox views

    @Test
    void SRS_22_16_anInboxRowCarriesEveryFieldOfTheItemAndItsTask() {
        route(2, OfficeLevel.DO, OtpGatedAction.DISBURSEMENT_RELEASE, 72);
        ApprovalItem item = pending(2, 2);
        WorkflowInboxItem task =
                WorkflowInboxItem.open(5L, "PAYROLL_CHECKER", OFFICE, NOW.minusSeconds(60));
        task.flagNoApprover(true);
        OrgLocation location = mock(OrgLocation.class);
        when(location.displayLabel()).thenReturn("DO Noida");
        when(org.location(OFFICE)).thenReturn(Optional.of(location));
        when(inbox.findOpen(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(task), PageRequest.of(0, 20), 41));
        when(approvals.findAllById(anyList())).thenReturn(List.of(item));
        when(policy.denialAt(any(), any(), any(), any())).thenReturn(null);

        Paged<ApprovalService.ItemView> page = service.inbox(checker, new PageSpec(0, 20));

        assertThat(page.total()).isEqualTo(41);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.items()).hasSize(1);
        ApprovalService.ItemView v = page.items().get(0);
        assertThat(v.approvalId()).isEqualTo(5L);
        assertThat(v.moduleCode()).isEqualTo("I-20");
        assertThat(v.entityName()).isEqualTo("off_cycle_payment");
        assertThat(v.entityKey()).isEqualTo("OC-1");
        assertThat(v.actionType()).isEqualTo("CREATE");
        assertThat(v.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(v.submittedBy()).isEqualTo("maker");
        assertThat(v.submittedAt()).isEqualTo(NOW.minus(Duration.ofDays(3)));
        assertThat(v.reason()).isEqualTo("Order 3");
        assertThat(v.assignedRoleCode()).isEqualTo("PAYROLL_CHECKER");
        assertThat(v.locationLabel()).isEqualTo("DO Noida");
        assertThat(v.slaDueAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(v.slaBreached()).as("past its due time").isTrue();
        assertThat(v.level()).isEqualTo(2);
        assertThat(v.levelCount()).isEqualTo(2);
        assertThat(v.noApproverInScope()).isTrue();
        assertThat(v.canDecide()).isTrue();
        assertThat(v.denialKey()).isNull();
        assertThat(v.impactValue()).isEqualByComparingTo("99.00");
        assertThat(v.ageDays()).isEqualTo(3);
        assertThat(v.targetTatHours()).isEqualTo(72);
        assertThat(v.bulkApproval()).as("route does not allow bulk").isFalse();
        assertThat(v.returnReason()).isNull();
        assertThat(v.decidedBy()).isNull();
        assertThat(v.decidedAt()).isNull();
    }

    @Test
    void SRS_22_16_aRowWithADenialIsNotDecidableAndNamesTheMessageKey() {
        route(2, OfficeLevel.DO, null, null);
        ApprovalItem item = pending(2, 2);
        WorkflowInboxItem task =
                WorkflowInboxItem.open(5L, "PAYROLL_CHECKER", null, NOW.plusSeconds(60));
        when(inbox.findOpen(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(task), PageRequest.of(0, 20), 1));
        when(approvals.findAllById(anyList())).thenReturn(List.of(item));
        when(policy.isAtHeadquarters(checker)).thenReturn(false);

        ApprovalService.ItemView v = service.inbox(checker, new PageSpec(0, 20)).items().get(0);

        assertThat(v.canDecide()).isFalse();
        assertThat(v.denialKey()).isEqualTo("error." + ApiErrorCode.OUT_OF_SCOPE.code());
        assertThat(v.locationLabel()).as("a national item names no office").isNull();
        assertThat(v.slaBreached()).as("not yet due").isFalse();
        assertThat(v.bulkApproval()).isFalse();
        assertThat(v.targetTatHours()).isNull();
    }

    @Test
    void SRS_22_16_theInboxSkipsATaskWhoseItemIsMissingAndReturnsThePageNumbers() {
        route(2, OfficeLevel.DO, null, null);
        WorkflowInboxItem orphan = WorkflowInboxItem.open(404L, "PAYROLL_CHECKER", OFFICE, null);
        when(inbox.findOpen(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(orphan), PageRequest.of(2, 10), 21));
        when(approvals.findAllById(anyList())).thenReturn(List.of());
        Paged<ApprovalService.ItemView> page = service.inbox(checker, new PageSpec(2, 10));
        assertThat(page.items()).isEmpty();
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.total()).isEqualTo(21);
    }

    @Test
    void SRS_22_16_anInboxWithNoRolesOrNoScopeIsEmptyAndReadsNothing() {
        when(checker.roleCodes()).thenReturn(Set.of());
        assertThat(service.inbox(checker, new PageSpec(1, 20)).items()).isEmpty();
        when(checker.roleCodes()).thenReturn(Set.of("PAYROLL_CHECKER"));
        when(checker.dataScope()).thenReturn(Set.of());
        Paged<ApprovalService.ItemView> page = service.inbox(checker, new PageSpec(1, 20));
        assertThat(page.items()).isEmpty();
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.total()).isZero();
        verify(inbox, never()).findOpen(any(), any(), any(), any());
    }

    @Test
    void SRS_G_13_anItemOfARouteThatAllowsBulkAndAsksNoOtpIsEligibleOnlyWhilePending() {
        WorkflowAction a = route(2, OfficeLevel.DO, null, null);
        a.allowBulkApproval(true);
        ApprovalItem item = pending(2, 2);
        assertThat(service.bulkEligible(item)).isTrue();
        item.decide(ApprovalStatus.APPROVED, "checker", "ok", NOW);
        assertThat(service.bulkEligible(item)).isFalse();

        a.allowBulkApproval(false);
        assertThat(service.bulkEligible(pending(2, 2))).isFalse();
        a.allowBulkApproval(true);
        when(actions.findById(ACTION_ID)).thenReturn(Optional.empty());
        assertThat(service.bulkEligible(pending(2, 2))).isFalse();
    }

    @Test
    void SRS_G_13_aBrokenRouteIsNotEligibleForBulkInsteadOfFailing() {
        WorkflowAction a = route(2, OfficeLevel.DO, null, null);
        a.allowBulkApproval(true);
        when(levels.findByWorkflowActionIdOrderByLevelNo(ACTION_ID)).thenReturn(List.of());
        assertThat(service.bulkEligible(pending(2, 2))).isFalse();
    }

    @Test
    void SRS_G_13_anInboxRowOfABulkRouteIsBulkApprovableOnlyWhenTheCallerMayDecideIt() {
        WorkflowAction a = route(2, OfficeLevel.DO, null, null);
        a.allowBulkApproval(true);
        ApprovalItem item = pending(2, 2);
        WorkflowInboxItem task =
                WorkflowInboxItem.open(5L, "PAYROLL_CHECKER", OFFICE, NOW.plusSeconds(60));
        when(inbox.findOpen(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(task), PageRequest.of(0, 20), 1));
        when(approvals.findAllById(anyList())).thenReturn(List.of(item));
        when(policy.denialAt(any(), any(), any(), any())).thenReturn(null);

        ApprovalService.ItemView decidable =
                service.inbox(checker, new PageSpec(0, 20)).items().get(0);

        assertThat(decidable.canDecide()).isTrue();
        assertThat(decidable.bulkApproval()).as("allowed, no OTP, no denial").isTrue();

        // the same item for a caller outside its office: not decidable, so never bulk approvable
        WorkflowInboxItem national =
                WorkflowInboxItem.open(5L, "PAYROLL_CHECKER", null, NOW.plusSeconds(60));
        when(inbox.findOpen(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(national), PageRequest.of(0, 20), 1));
        when(policy.isAtHeadquarters(checker)).thenReturn(false);
        ApprovalService.ItemView denied =
                service.inbox(checker, new PageSpec(0, 20)).items().get(0);
        assertThat(denied.canDecide()).isFalse();
        assertThat(denied.bulkApproval()).isFalse();
    }
}
