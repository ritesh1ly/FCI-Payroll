package in.gov.fci.payroll.workflow.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.workflow.api.ApproverDirectory;
import in.gov.fci.payroll.workflow.api.RoleEquivalence;
import in.gov.fci.payroll.workflow.api.SecondFactor;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.business.model.ScreenAction;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction;
import in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem;
import in.gov.fci.payroll.workflow.business.model.WorkflowLevel;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The maker-checker engine's guards that the demo data cannot reach through the API (R-MC; SRS-G-1,
 * SRS-G-21, SRS-22-16, BR-APPR-003 / 007): Headquarters never locks a unit's payroll, an HQ-only
 * route from another office, a missing reason, a malformed route master, every incomplete OTP
 * proof, an item without a route, and the inbox and submission views. The flows are {@code
 * ApprovalEngineIT} and {@code WorkflowMasterIT}.
 */
class ApprovalServiceTest {

    private static final PageSpec PAGE = new PageSpec(0, 20);

    private static final Instant NOW = Instant.parse("2026-10-23T05:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    private static final long ACTION_ID = 30L;
    private static final long OFFICE = 40L;

    private final in.gov.fci.payroll.platform.approval.AttachmentPresence attachments =
            mock(in.gov.fci.payroll.platform.approval.AttachmentPresence.class);
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
    private final AccessContext maker = mock(AccessContext.class);
    private final AccessContext checker = mock(AccessContext.class);

    private ApprovalService service;

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
                        List.of(),
                        audit,
                        events,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        mock(ApiProperties.class),
                        attachments);
        when(contexts.today()).thenReturn(TODAY);
        // UAT / production: the critical activities ask for an OTP (fci.api.otp.required = Y).
        when(secondFactor.required()).thenReturn(true);
        when(maker.userId()).thenReturn(1L);
        when(maker.loginId()).thenReturn("maker");
        when(maker.roleCodes()).thenReturn(Set.of("PAYROLL_MAKER"));
        when(checker.userId()).thenReturn(2L);
        when(checker.loginId()).thenReturn("checker");
        when(checker.roleCodes()).thenReturn(Set.of("PAYROLL_CHECKER"));
        when(checker.dataScope()).thenReturn(Set.of(OFFICE));
        when(roles.actsAs(anyString(), anyString()))
                .thenAnswer(i -> i.getArgument(0).equals(i.getArgument(1)));
        when(approvals.save(any())).thenAnswer(i -> i.getArgument(0));
        when(approvers.hasApprover(any(), any(), any())).thenReturn(true);
    }

    @Test
    void SRS_22_16_anActionWithAMandatoryAttachmentIsRefusedWithoutOneAndTheBlockIsAudited() {
        WorkflowAction a = route(WorkflowAction.OfficeLevel.DO, null, null);
        ReflectionTestUtils.setField(a, "attachmentRequired", Boolean.TRUE);
        when(attachments.countActive("off_cycle_payment", "OC-1")).thenReturn(0);
        assertThatThrownBy(() -> service.submitItem(maker, submission("CREATE", "Order 3"), null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.ATTACHMENT_REQUIRED));
        // SRS-20-27: the blocked submission is in the audit trail, kept although it fails.
        ArgumentCaptor<AuditService.Entry> blocked =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).recordIndependently(eq("maker"), blocked.capture());
        assertThat(blocked.getValue().fieldName()).isEqualTo("SUBMISSION_BLOCKED");
        assertThat(blocked.getValue().newValue()).isEqualTo("ATTACHMENT_MISSING");
        assertThat(blocked.getValue().entityKey()).isEqualTo("OC-1");
        verify(approvals, never()).save(any());
        // No OTP is sent for a submission that cannot go ahead.
        verify(secondFactor, never()).issue(anyLong(), any(), anyString(), anyString());

        // Attached: it goes ahead.
        when(attachments.countActive("off_cycle_payment", "OC-1")).thenReturn(1);
        assertThat(service.submitItem(maker, submission("CREATE", "Order 3"), null)).isNotNull();
    }

    @Test
    void SRS_22_16_anActionWithoutTheFlagNeverAsksForAnAttachment() {
        route(WorkflowAction.OfficeLevel.DO, null, null);
        assertThat(service.submitItem(maker, submission("CREATE", "Order 3"), null)).isNotNull();
        verify(attachments, never()).countActive(any(), any());
    }

    private static ErrorCode code(Throwable t) {
        return ((FciException) t).getErrorCode();
    }

    private WorkflowAction route(
            WorkflowAction.OfficeLevel office, OtpGatedAction otp, Integer tat) {
        WorkflowAction a =
                WorkflowAction.propose(
                        "I20_CREATE",
                        "Off-cycle",
                        "GENERAL",
                        "I-20",
                        office,
                        2,
                        otp,
                        false,
                        tat,
                        1,
                        TODAY);
        a.approve(TODAY);
        ReflectionTestUtils.setField(a, "id", ACTION_ID);
        when(actions.findByActionCodeOrderByVersionNoDesc("I20_CREATE")).thenReturn(List.of(a));
        when(actions.findById(ACTION_ID)).thenReturn(Optional.of(a));
        when(levels.findByWorkflowActionIdOrderByLevelNo(ACTION_ID))
                .thenReturn(
                        List.of(
                                WorkflowLevel.of(
                                        ACTION_ID,
                                        1,
                                        "PAYROLL_MAKER",
                                        WorkflowAction.OfficeLevel.DO,
                                        false),
                                WorkflowLevel.of(
                                        ACTION_ID,
                                        2,
                                        "PAYROLL_CHECKER",
                                        WorkflowAction.OfficeLevel.DO,
                                        otp != null)));
        return a;
    }

    private static Submission submission(String actionCode, String reason) {
        return new Submission(
                "I20_CREATE",
                "I-20",
                actionCode,
                "off_cycle_payment",
                "OC-1",
                ActionType.CREATE,
                OFFICE,
                "{}",
                TODAY,
                reason);
    }

    private ApprovalItem pendingOn(Long route) {
        return ApprovalItem.submit(
                "I-20",
                route,
                2,
                "off_cycle_payment",
                "OC-9",
                ActionType.CREATE,
                "{}",
                TODAY,
                "maker",
                "Monthly claims",
                null,
                null,
                NOW);
    }

    @Test
    void SRS_G_13_onlyAnActionThatAllowsBulkApprovalAndAsksNoOtpIsApprovedInBulk() {
        WorkflowAction plain = route(WorkflowAction.OfficeLevel.DO, null, null);
        assertThat(service.bulkEligible(pendingOn(ACTION_ID)))
                .as("the action does not allow bulk approval")
                .isFalse();
        ReflectionTestUtils.setField(plain, "bulkApprovalAllowed", Boolean.TRUE);
        assertThat(service.bulkEligible(pendingOn(ACTION_ID))).isTrue();
        assertThat(service.bulkEligible(pendingOn(null))).as("no route").isFalse();

        // UC-07 A2: an item whose level needs an OTP is approved on its own, with its OTP.
        WorkflowAction gated =
                route(WorkflowAction.OfficeLevel.DO, OtpGatedAction.UNDO_TRANSACTION, null);
        ReflectionTestUtils.setField(gated, "bulkApprovalAllowed", Boolean.TRUE);
        assertThat(service.bulkEligible(pendingOn(ACTION_ID))).isFalse();

        // A route whose levels do not match its definition is decided on its own.
        when(actions.findById(ACTION_ID)).thenReturn(Optional.of(plain));
        when(levels.findByWorkflowActionIdOrderByLevelNo(ACTION_ID)).thenReturn(List.of());
        assertThat(service.bulkEligible(pendingOn(ACTION_ID))).isFalse();
        when(actions.findById(ACTION_ID)).thenReturn(Optional.empty());
        assertThat(service.bulkEligible(pendingOn(ACTION_ID))).isFalse();
    }

    @Test
    void SRS_G_13_anItemAlreadyDecidedIsNeverInABulkApproval() {
        WorkflowAction plain = route(WorkflowAction.OfficeLevel.DO, null, null);
        ReflectionTestUtils.setField(plain, "bulkApprovalAllowed", Boolean.TRUE);
        ApprovalItem decided = pendingOn(ACTION_ID);
        ReflectionTestUtils.setField(decided, "status", ApprovalStatus.REJECTED);
        assertThat(service.bulkEligible(decided)).isFalse();
        verify(actions, never()).findById(ACTION_ID);
    }

    // ---------------------------------------------------------------- the item's documents

    private ApprovalItem visible(long id, String submittedBy) {
        ApprovalItem item = pendingOn(ACTION_ID);
        ReflectionTestUtils.setField(item, "id", id);
        ReflectionTestUtils.setField(item, "submittedBy", submittedBy);
        when(approvals.findById(id)).thenReturn(Optional.of(item));
        return item;
    }

    @Test
    void SRS_G_31_theSubmitterSeesTheDocumentsOfTheOwnItem() {
        visible(5L, "maker");
        when(attachments.files("off_cycle_payment", "OC-9")).thenReturn(List.of());
        assertThat(service.documents(maker, 5L)).isEmpty();
        verify(inbox, never()).findByApprovalId(5L);
    }

    @Test
    void SRS_13_18_theRoleTheItemWaitsForSeesItsDocumentsWithinItsOffice() {
        visible(5L, "someone.else");
        when(inbox.findByApprovalId(5L))
                .thenReturn(
                        Optional.of(WorkflowInboxItem.open(5L, "PAYROLL_CHECKER", OFFICE, null)));
        when(attachments.files("off_cycle_payment", "OC-9")).thenReturn(List.of());
        assertThat(service.documents(checker, 5L)).isEmpty();
        // A task without an office is in no office's scope: Headquarters only (deny by default,
        // security review 2026-11-04, W-1).
        when(inbox.findByApprovalId(5L))
                .thenReturn(Optional.of(WorkflowInboxItem.open(5L, "PAYROLL_CHECKER", null, null)));
        assertThatThrownBy(() -> service.documents(checker, 5L))
                .satisfies(t -> assertThat(code(t)).isEqualTo(CommonErrorCode.NOT_FOUND));
        when(policy.isAtHeadquarters(checker)).thenReturn(true);
        assertThat(service.documents(checker, 5L)).isEmpty();
    }

    @Test
    void SRS_G_31_anyoneElseFindsNoItemAndNoDocuments() {
        visible(5L, "someone.else");
        // Another role at the item's office.
        when(inbox.findByApprovalId(5L))
                .thenReturn(
                        Optional.of(WorkflowInboxItem.open(5L, "PAYROLL_CHECKER", OFFICE, null)));
        when(maker.dataScope()).thenReturn(Set.of(OFFICE));
        assertThatThrownBy(() -> service.documents(maker, 5L))
                .satisfies(t -> assertThat(code(t)).isEqualTo(CommonErrorCode.NOT_FOUND));
        // The right role, another office.
        when(inbox.findByApprovalId(5L))
                .thenReturn(Optional.of(WorkflowInboxItem.open(5L, "PAYROLL_CHECKER", 99L, null)));
        assertThatThrownBy(() -> service.document(checker, "10.0.0.1", 5L, 1L))
                .satisfies(t -> assertThat(code(t)).isEqualTo(CommonErrorCode.NOT_FOUND));
        // A task without a role, an item without a task, and no item at all.
        when(inbox.findByApprovalId(5L))
                .thenReturn(Optional.of(WorkflowInboxItem.open(5L, null, OFFICE, null)));
        assertThatThrownBy(() -> service.documents(checker, 5L))
                .satisfies(t -> assertThat(code(t)).isEqualTo(CommonErrorCode.NOT_FOUND));
        when(inbox.findByApprovalId(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.documents(checker, 5L))
                .satisfies(t -> assertThat(code(t)).isEqualTo(CommonErrorCode.NOT_FOUND));
        when(approvals.findById(6L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.documents(checker, 6L))
                .satisfies(t -> assertThat(code(t)).isEqualTo(CommonErrorCode.NOT_FOUND));
        verify(attachments, never()).files(anyString(), anyString());
        verify(attachments, never())
                .content(anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    // ---------------------------------------------------------------- the decision permission

    private ApprovalItem item(String module, Long route) {
        return ApprovalItem.submit(
                module,
                route,
                3,
                "undo_reversal_register",
                "it_payment:P-1",
                ActionType.UPDATE,
                "{}",
                TODAY,
                "maker",
                "Twice",
                null,
                null,
                NOW);
    }

    private void routeCode(long id, String code) {
        WorkflowAction a =
                WorkflowAction.propose(
                        code,
                        "Undo",
                        "PAYROLL",
                        "C-02",
                        WorkflowAction.OfficeLevel.PAYING_UNIT,
                        3,
                        OtpGatedAction.UNDO_TRANSACTION,
                        false,
                        null,
                        1,
                        TODAY);
        ReflectionTestUtils.setField(a, "id", id);
        when(actions.findById(id)).thenReturn(Optional.of(a));
    }

    /**
     * Plan 05 D-11 (owner, 2026-10-25): a route whose screen inventory lists {@code
     * <ACTION>_AUTHORISE} is decided with that permission, not the screen's APPROVE (C-02 APPROVE
     * verifies payroll results); every other route, and an item without one, with APPROVE.
     */
    @Test
    void SRS_G_29_aRouteWithItsOwnDecisionPermissionIsDecidedWithItAndOnlyIt() {
        routeCode(71L, "C02_UNDO");
        when(inventory.findByScreenCodeAndActionCode("C-02", "UNDO_AUTHORISE"))
                .thenReturn(Optional.of(mock(ScreenAction.class)));
        assertThat(service.decisionAction(item("C-02", 71L), "C-02")).isEqualTo("UNDO_AUTHORISE");
        // Asked once per route: the inbox lists many items of one route.
        assertThat(service.decisionAction(item("C-02", 71L), "C-02")).isEqualTo("UNDO_AUTHORISE");
        verify(actions, times(1)).findById(71L);

        routeCode(72L, "C02_UNFREEZE");
        assertThat(service.decisionAction(item("C-02", 72L), "C-02")).isEqualTo("APPROVE");
        routeCode(73L, "I20_CREATE");
        assertThat(service.decisionAction(item("C-02", 73L), "C-02")).isEqualTo("APPROVE");
        assertThat(service.decisionAction(item("C-02", 74L), "C-02")).isEqualTo("APPROVE");
        assertThat(service.decisionAction(item("C-02", null), "C-02")).isEqualTo("APPROVE");
    }

    // ---------------------------------------------------------------- submission guards

    @Test
    void SRS_G_1_headquartersNeverLocksAUnitsPayrollAndAnHqRouteNeedsHeadquarters() {
        route(WorkflowAction.OfficeLevel.DO, null, null);
        when(policy.isAtHeadquarters(maker)).thenReturn(true);
        assertThatThrownBy(() -> service.submitItem(maker, submission("LOCK", "Month end"), null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.HQ_MAY_NOT_LOCK));
        route(WorkflowAction.OfficeLevel.HQ, null, null);
        when(policy.isAtHeadquarters(maker)).thenReturn(false);
        assertThatThrownBy(() -> service.submitItem(maker, submission("CREATE", "Order 3"), null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.HQ_ONLY));
        verify(approvals, never()).save(any());
    }

    @Test
    void SRS_G_1_aSubmissionWithoutAReasonIsRefusedBeforeAnythingIsWritten() {
        route(WorkflowAction.OfficeLevel.DO, null, null);
        for (String reason : new String[] {null, " "}) {
            assertThatThrownBy(() -> service.submitItem(maker, submission("CREATE", reason), null))
                    .as(String.valueOf(reason))
                    .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.REASON_REQUIRED));
        }
        verify(approvals, never()).save(any());
    }

    @Test
    void R_MC_aRouteMasterWithMissingLevelsOrNoMakerFirstIsAServerFault() {
        route(WorkflowAction.OfficeLevel.DO, null, null);
        when(levels.findByWorkflowActionIdOrderByLevelNo(ACTION_ID))
                .thenReturn(
                        List.of(
                                WorkflowLevel.of(
                                        ACTION_ID,
                                        1,
                                        "PAYROLL_MAKER",
                                        WorkflowAction.OfficeLevel.DO,
                                        false)));
        assertThatThrownBy(() -> service.submitItem(maker, submission("CREATE", "Order 3"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has 1 level(s), expected 2");
        when(levels.findByWorkflowActionIdOrderByLevelNo(ACTION_ID))
                .thenReturn(
                        List.of(
                                WorkflowLevel.of(
                                        ACTION_ID,
                                        2,
                                        "PAYROLL_MAKER",
                                        WorkflowAction.OfficeLevel.DO,
                                        false),
                                WorkflowLevel.of(
                                        ACTION_ID,
                                        2,
                                        "PAYROLL_CHECKER",
                                        WorkflowAction.OfficeLevel.DO,
                                        false)));
        assertThatThrownBy(() -> service.submitItem(maker, submission("CREATE", "Order 3"), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void SRS_G_21_anOtpGatedSubmissionAsksForTheCodeUntilEveryPartOfTheProofIsThere() {
        route(WorkflowAction.OfficeLevel.DO, OtpGatedAction.CYCLE_FREEZE_LOCK, null);
        when(secondFactor.issue(
                        eq(1L), eq(OtpGatedAction.CYCLE_FREEZE_LOCK), anyString(), anyString()))
                .thenReturn(77L);
        for (OtpProof partial :
                new OtpProof[] {null, new OtpProof(null, "123456"), new OtpProof(77L, null)}) {
            assertThatThrownBy(
                            () ->
                                    service.submitItem(
                                            maker, submission("CREATE", "Order 3"), partial))
                    .as(String.valueOf(partial))
                    .satisfies(
                            t -> {
                                assertThat(code(t)).isEqualTo(ApiErrorCode.OTP_REQUIRED);
                                assertThat(((FciException) t).getMessageArgs())
                                        .containsExactly(77L);
                            });
        }
        when(secondFactor.verify(1L, OtpGatedAction.CYCLE_FREEZE_LOCK, 77L, "123456"))
                .thenReturn(77L);
        ApprovalItem item =
                service.submitItem(
                        maker, submission("CREATE", "Order 3"), new OtpProof(77L, "123456"));
        assertThat(item.getOtpChallengeId()).isEqualTo(77L);
        // A session without a user (a system context) never gets a code.
        AccessContext system = mock(AccessContext.class);
        when(system.userId()).thenReturn(null);
        when(system.roleCodes()).thenReturn(Set.of("PAYROLL_MAKER"));
        assertThatThrownBy(() -> service.submitItem(system, submission("CREATE", "Order 3"), null))
                .satisfies(
                        t ->
                                assertThat(code(t))
                                        .isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED));
    }

    /**
     * SRS-G-21 (project owner, 2026-10-23): with OTP verification switched off (development and
     * local machines) the gated submission goes through without a code, and still records the
     * challenge that confirmed it.
     */
    @Test
    void SRS_G_21_withOtpVerificationOffAGatedSubmissionRecordsAChallengeConfirmedWithoutACode() {
        route(WorkflowAction.OfficeLevel.DO, OtpGatedAction.CYCLE_FREEZE_LOCK, null);
        when(secondFactor.required()).thenReturn(false);
        when(secondFactor.confirmWithoutCode(
                        eq(1L), eq(OtpGatedAction.CYCLE_FREEZE_LOCK), anyString(), anyString()))
                .thenReturn(88L);
        ApprovalItem item = service.submitItem(maker, submission("CREATE", "Order 3"), null);
        assertThat(item.getOtpChallengeId()).isEqualTo(88L);
        verify(secondFactor, never()).issue(anyLong(), any(), anyString(), anyString());
        verify(secondFactor, never()).verify(anyLong(), any(), anyLong(), anyString());
    }

    @Test
    void SRS_22_16_aRouteWithATargetSetsTheDueTimeAndOneWithoutSetsNone() {
        route(WorkflowAction.OfficeLevel.DO, null, 48);
        when(org.ancestorOrSelf(anyLong(), any())).thenReturn(Optional.empty());
        ApprovalItem item = service.submitItem(maker, submission("CREATE", "Order 3"), null);
        assertThat(item.getStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
        verify(inbox)
                .save(
                        argThat(
                                (WorkflowInboxItem t) ->
                                        NOW.plusSeconds(48 * 3600L).equals(t.getSlaDueAt())));
    }

    // ---------------------------------------------------------------- decisions

    private ApprovalItem pending(Long actionId) {
        ApprovalItem item =
                ApprovalItem.submit(
                        "I-20",
                        actionId,
                        2,
                        "off_cycle_payment",
                        "OC-1",
                        ActionType.CREATE,
                        "{}",
                        TODAY,
                        "maker",
                        "Order 3",
                        null,
                        null,
                        NOW.minusSeconds(86_400L * 3));
        ReflectionTestUtils.setField(item, "id", 5L);
        when(approvals.findById(5L)).thenReturn(Optional.of(item));
        return item;
    }

    private WorkflowInboxItem task(String role) {
        WorkflowInboxItem task = WorkflowInboxItem.open(5L, role, OFFICE, null);
        when(inbox.findByApprovalId(5L)).thenReturn(Optional.of(task));
        return task;
    }

    @Test
    void SRS_G_1_aReturnOrRejectionNeedsTheCheckersReason() {
        pending(ACTION_ID);
        task("PAYROLL_CHECKER");
        for (ApprovalService.Decision d :
                new ApprovalService.Decision[] {
                    ApprovalService.Decision.REJECT, ApprovalService.Decision.RETURN
                }) {
            for (String reason : new String[] {null, "  "}) {
                assertThatThrownBy(() -> service.decide(checker, 5L, d, reason, null))
                        .as(d + " " + reason)
                        .satisfies(
                                t -> assertThat(code(t)).isEqualTo(ApiErrorCode.REASON_REQUIRED));
            }
        }
    }

    @Test
    void R_MC_anItemWithoutARouteOrRoleIsDecidedFinallyByTheChecker() {
        // A legacy item (no workflow action) assigned to no particular role.
        ApprovalItem item = pending(null);
        task(null);
        when(policy.denialAt(checker, "I-20", "APPROVE", OFFICE)).thenReturn(null);
        service.decide(checker, 5L, ApprovalService.Decision.APPROVE, null, null);
        assertThat(item.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        verify(decisions).save(argThat(d -> "CHECKER".equals(d.getActingRoleCode())));
        verify(secondFactor, never()).issue(anyLong(), any(), anyString(), anyString());
    }

    /**
     * SRS-22-6 with SRS-G-29 (US-GAP-127): the decider's authority is checked when it decides, not
     * when the item was submitted - a Checker whose Access Group ended after submission (the role
     * is no longer in the session) or whose scope moved off the item's office cannot decide it, and
     * the item stays pending.
     */
    @Test
    void SRS_22_6_anApproverWhoseGroupEndedOrMovedOutOfScopeAfterSubmissionCannotDecide() {
        ApprovalItem item = pending(ACTION_ID);
        task("PAYROLL_CHECKER");
        AccessContext ended = mock(AccessContext.class);
        when(ended.loginId()).thenReturn("checker");
        when(ended.userId()).thenReturn(2L);
        when(ended.roleCodes()).thenReturn(Set.of());
        assertThatThrownBy(
                        () ->
                                service.decide(
                                        ended, 5L, ApprovalService.Decision.APPROVE, null, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.ROLE_NOT_HELD));
        // The role still held, the office no longer in its scope: the office check refuses it.
        when(policy.denialAt(checker, "I-20", "APPROVE", OFFICE))
                .thenReturn(ApiErrorCode.OUT_OF_SCOPE);
        assertThatThrownBy(
                        () ->
                                service.decide(
                                        checker, 5L, ApprovalService.Decision.APPROVE, null, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.OUT_OF_SCOPE))
                // OD-130: forging a decision outside the scope is an access offence ...
                .satisfies(t -> assertThat(((FciException) t).isAccessOffence()).isTrue());
        // ... but the session's own office names nothing the user chose: no offence.
        when(checker.locationId()).thenReturn(OFFICE);
        assertThatThrownBy(
                        () ->
                                service.decide(
                                        checker, 5L, ApprovalService.Decision.APPROVE, null, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.OUT_OF_SCOPE))
                .satisfies(t -> assertThat(((FciException) t).isAccessOffence()).isFalse());
        // Any other denial of the decision is a business refusal.
        when(ended.locationId()).thenReturn(1L);
        assertThatThrownBy(
                        () ->
                                service.decide(
                                        ended, 5L, ApprovalService.Decision.APPROVE, null, null))
                .satisfies(t -> assertThat(((FciException) t).isAccessOffence()).isFalse());
        assertThat(item.getStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
        verify(decisions, never()).save(any());
    }

    // ---------------------------------------------------------------- views

    // SC-01-0059: a Checker sees no item outside the Access Group scope.
    @Test
    void SRS_G_1_noRoleOrNoScopeMeansAnEmptyInboxAndAClosedItemIsNotListed() {
        AccessContext none = mock(AccessContext.class);
        when(none.roleCodes()).thenReturn(Set.of());
        assertThat(service.inbox(none, PAGE).items()).isEmpty();
        AccessContext unscoped = mock(AccessContext.class);
        when(unscoped.roleCodes()).thenReturn(Set.of("PAYROLL_CHECKER"));
        when(unscoped.dataScope()).thenReturn(Set.of());
        assertThat(service.inbox(unscoped, PAGE).total()).isZero();
        verify(inbox, never()).findOpen(any(), any(), any(), any());
        // An open task whose item is gone or already decided is skipped.
        WorkflowInboxItem orphan = WorkflowInboxItem.open(6L, "PAYROLL_CHECKER", OFFICE, null);
        ApprovalItem decided = pending(ACTION_ID);
        decided.decide(ApprovalStatus.APPROVED, "someone", null, NOW);
        WorkflowInboxItem closedTask = WorkflowInboxItem.open(5L, "PAYROLL_CHECKER", OFFICE, null);
        when(inbox.findOpen(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(orphan, closedTask)));
        when(approvals.findAllById(any())).thenReturn(List.of(decided));
        assertThat(service.inbox(checker, PAGE).items()).isEmpty();
    }

    @Test
    void SRS_22_16_mySubmissionsFollowTheOfficeAndANationalItemIsAlwaysShown() {
        ApprovalItem here = pending(ACTION_ID);
        ApprovalItem elsewhere =
                ApprovalItem.submit(
                        "I-20",
                        ACTION_ID,
                        2,
                        "off_cycle_payment",
                        "OC-2",
                        ActionType.CREATE,
                        "{}",
                        TODAY,
                        "maker",
                        "Order 4",
                        null,
                        null,
                        NOW);
        ReflectionTestUtils.setField(elsewhere, "id", 6L);
        ApprovalItem national =
                ApprovalItem.submit(
                        "A-07",
                        null,
                        2,
                        "rule_version",
                        "SESSION_IDLE",
                        ActionType.UPDATE,
                        "{}",
                        TODAY,
                        "maker",
                        "Setting",
                        null,
                        null,
                        NOW);
        ReflectionTestUtils.setField(national, "id", 7L);
        // The office filter is the query's (WorkflowIT proves it on the database); the service
        // asks for the maker's office scope, or nationally at Headquarters.
        when(approvals.findMine(eq("maker"), eq(false), eq(Set.of(OFFICE)), any()))
                .thenReturn(new PageImpl<>(List.of(here, national)));
        when(approvals.findMine(eq("maker"), eq(true), any(), any()))
                .thenReturn(new PageImpl<>(List.of(here, elsewhere, national)));
        // The first task is breached; the second is late by its due time.
        WorkflowInboxItem hereTask =
                WorkflowInboxItem.open(5L, "PAYROLL_CHECKER", OFFICE, NOW.minusSeconds(60));
        when(inbox.findByApprovalId(5L)).thenReturn(Optional.of(hereTask));
        when(inbox.findByApprovalId(6L))
                .thenReturn(Optional.of(WorkflowInboxItem.open(6L, "PAYROLL_CHECKER", 99L, null)));
        when(inbox.findByApprovalId(7L)).thenReturn(Optional.empty());
        when(maker.dataScope()).thenReturn(Set.of(OFFICE));
        when(org.location(OFFICE)).thenReturn(Optional.empty());
        List<ApprovalService.ItemView> mine = service.submissions(maker, PAGE).items();
        assertThat(mine).extracting(ApprovalService.ItemView::approvalId).containsExactly(5L, 7L);
        ApprovalService.ItemView first = mine.get(0);
        assertThat(first.slaBreached()).isTrue();
        assertThat(first.canDecide()).isFalse();
        assertThat(first.ageDays()).isEqualTo(3);
        ApprovalService.ItemView nationalView = mine.get(1);
        assertThat(nationalView.assignedRoleCode()).isNull();
        assertThat(nationalView.locationLabel()).isNull();
        assertThat(nationalView.slaDueAt()).isNull();
        assertThat(nationalView.slaBreached()).isFalse();
        assertThat(nationalView.targetTatHours()).isNull();
        // At Headquarters every submission is shown.
        when(policy.isAtHeadquarters(maker)).thenReturn(true);
        assertThat(service.submissions(maker, PAGE).total()).isEqualTo(3);
    }

    private ApprovalItem pendingAt(String module, int levels) {
        ApprovalItem item =
                ApprovalItem.submit(
                        module,
                        ACTION_ID,
                        levels,
                        "off_cycle_payment",
                        "OC-1",
                        ActionType.CREATE,
                        "{}",
                        TODAY,
                        "maker",
                        "Order 3",
                        null,
                        null,
                        NOW);
        ReflectionTestUtils.setField(item, "id", 5L);
        when(approvals.findById(5L)).thenReturn(Optional.of(item));
        return item;
    }

    /** SRS-22-16: an approval at a middle level without a reason is audited as such. */
    @Test
    void SRS_22_16_aMiddleLevelApprovedWithoutAReasonMovesOnAsApprovedAtItsLevel() {
        WorkflowAction threeLevels =
                WorkflowAction.propose(
                        "I20_CREATE",
                        "Off-cycle",
                        "GENERAL",
                        "I-20",
                        WorkflowAction.OfficeLevel.DO,
                        3,
                        null,
                        false,
                        null,
                        1,
                        TODAY);
        threeLevels.approve(TODAY);
        ReflectionTestUtils.setField(threeLevels, "id", ACTION_ID);
        when(actions.findById(ACTION_ID)).thenReturn(Optional.of(threeLevels));
        when(levels.findByWorkflowActionIdOrderByLevelNo(ACTION_ID))
                .thenReturn(
                        List.of(
                                WorkflowLevel.of(
                                        ACTION_ID,
                                        1,
                                        "PAYROLL_MAKER",
                                        WorkflowAction.OfficeLevel.DO,
                                        false),
                                WorkflowLevel.of(
                                        ACTION_ID,
                                        2,
                                        "PAYROLL_CHECKER",
                                        WorkflowAction.OfficeLevel.DO,
                                        false),
                                WorkflowLevel.of(
                                        ACTION_ID,
                                        3,
                                        "DDO",
                                        WorkflowAction.OfficeLevel.DO,
                                        false)));
        ApprovalItem item = pendingAt("I-20", 3);
        task("PAYROLL_CHECKER");
        service.decide(checker, 5L, ApprovalService.Decision.APPROVE, "  ", null);
        assertThat(item.getCurrentLevel()).isEqualTo(3);
        assertThat(item.getStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
        verify(audit).record(eq("checker"), argThat(e -> "Approved at level 2".equals(e.reason())));
        // The owner's preconditions are asked only at the final level: a middle level is no
        // approval of the record (Wave B, ApprovalDeciding), so nothing is refused mid-chain.
        verify(events, never())
                .publishEvent(any(in.gov.fci.payroll.platform.approval.ApprovalDeciding.class));
    }

    /**
     * SRS-G-1 (l.517, "Maker and Checker are always different persons"; SC-02-0049): on a
     * three-level route nobody decides the own submission at any level, and whoever decided an
     * earlier level does not decide a later one; another holder of the level's role does. Each
     * refusal is MAKER_CHECKER and is reached before the role, office and scope checks.
     */
    @Test
    void SRS_G_1_selfApprovalIsRefusedAtEveryLevelOfAThreeLevelRoute() {
        ApprovalItem item = pendingAt("I-20", 3);
        WorkflowInboxItem task = task("PAYROLL_CHECKER");
        AccessContext submitter = mock(AccessContext.class);
        when(submitter.loginId()).thenReturn("maker");
        when(submitter.userId()).thenReturn(1L);
        when(submitter.roleCodes()).thenReturn(Set.of("PAYROLL_CHECKER", "DDO"));
        // Level 2: the submitter, whatever roles it holds, is refused; a checker is not.
        assertThat(item.getCurrentLevel()).isEqualTo(2);
        assertThat(service.denial(submitter, item, task)).isEqualTo(ApiErrorCode.MAKER_CHECKER);
        assertThat(service.denial(checker, item, task)).isNull();

        // Level 3, after the checker approved level 2.
        item.advance();
        WorkflowLevel second =
                WorkflowLevel.of(
                        ACTION_ID, 2, "PAYROLL_CHECKER", WorkflowAction.OfficeLevel.DO, false);
        when(decisions.findByApprovalIdOrderByDecidedAt(5L))
                .thenReturn(
                        List.of(
                                in.gov.fci.payroll.workflow.business.model.ApprovalDecision.record(
                                        item,
                                        second,
                                        "checker",
                                        "PAYROLL_CHECKER",
                                        in.gov.fci.payroll.workflow.business.model.ApprovalDecision
                                                .Kind.APPROVE,
                                        null,
                                        null,
                                        NOW)));
        WorkflowInboxItem third = task("DDO");
        AccessContext ddo = mock(AccessContext.class);
        when(ddo.loginId()).thenReturn("ddo");
        when(ddo.userId()).thenReturn(3L);
        when(ddo.roleCodes()).thenReturn(Set.of("DDO"));
        AccessContext checkerAsDdo = mock(AccessContext.class);
        when(checkerAsDdo.loginId()).thenReturn("checker");
        when(checkerAsDdo.userId()).thenReturn(2L);
        when(checkerAsDdo.roleCodes()).thenReturn(Set.of("PAYROLL_CHECKER", "DDO"));
        assertThat(service.denial(submitter, item, third))
                .as("the submitter at the last level")
                .isEqualTo(ApiErrorCode.MAKER_CHECKER);
        assertThat(service.denial(checkerAsDdo, item, third))
                .as("the level-2 decider at level 3, even holding the level's role")
                .isEqualTo(ApiErrorCode.MAKER_CHECKER);
        assertThat(service.denial(ddo, item, third)).as("a third person").isNull();
        // The refusal comes first: the role check would not have refused the decider.
        verify(policy, never()).denialAt(eq(checkerAsDdo), anyString(), anyString(), any());
        verify(policy, never()).denialAt(eq(submitter), anyString(), anyString(), any());
    }

    /** R-MC: an item whose route has fewer levels than it counts is decided at the last one. */
    @Test
    void R_MC_anItemCountingMoreLevelsThanItsRouteIsDecidedAtTheRoutesLast() {
        route(WorkflowAction.OfficeLevel.DO, null, null);
        ApprovalItem item = pendingAt("I-20", 3);
        task("PAYROLL_CHECKER");
        service.decide(checker, 5L, ApprovalService.Decision.APPROVE, null, null);
        assertThat(item.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }

    /** SRS-G-21: an OTP level of a route that names no OTP action asks for no code. */
    @Test
    void SRS_G_21_anOtpLevelOfARouteWithoutAnOtpActionAsksNoCode() {
        route(WorkflowAction.OfficeLevel.DO, null, null);
        when(levels.findByWorkflowActionIdOrderByLevelNo(ACTION_ID))
                .thenReturn(
                        List.of(
                                WorkflowLevel.of(
                                        ACTION_ID,
                                        1,
                                        "PAYROLL_MAKER",
                                        WorkflowAction.OfficeLevel.DO,
                                        false),
                                WorkflowLevel.of(
                                        ACTION_ID,
                                        2,
                                        "PAYROLL_CHECKER",
                                        WorkflowAction.OfficeLevel.DO,
                                        true)));
        ApprovalItem item = pendingAt("I-20", 2);
        task("PAYROLL_CHECKER");
        service.decide(checker, 5L, ApprovalService.Decision.APPROVE, null, null);
        assertThat(item.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        verify(secondFactor, never()).issue(anyLong(), any(), anyString(), anyString());
    }

    /**
     * SRS-G-21 with R-MC (Wave A review): the owner's preconditions are asked (ApprovalDeciding)
     * before the OTP step of a final approval, so an approval its owner refuses neither issues nor
     * consumes the Checker's code and the item stays pending; a precondition met goes on to the
     * OTP. A return or rejection asks no precondition.
     */
    @Test
    void SRS_G_21_theOwnersPreconditionsAreCheckedBeforeTheCheckersOtp() {
        route(WorkflowAction.OfficeLevel.DO, OtpGatedAction.CYCLE_FREEZE_LOCK, null);
        ApprovalItem item = pendingAt("I-20", 2);
        task("PAYROLL_CHECKER");
        FciException refusal = new FciException(CommonErrorCode.VALIDATION_FAILED);
        Mockito.doThrow(refusal)
                .when(events)
                .publishEvent(
                        argThat(
                                (Object e) ->
                                        e
                                                        instanceof
                                                        in.gov.fci.payroll.platform.approval
                                                                        .ApprovalDeciding
                                                                d
                                                && d.approvalId() == 5L
                                                && "off_cycle_payment".equals(d.entityName())
                                                && "OC-1".equals(d.entityKey())
                                                && "{}".equals(d.payload())
                                                && "checker".equals(d.checkerLoginId())));
        assertThatThrownBy(
                        () ->
                                service.decide(
                                        checker,
                                        5L,
                                        ApprovalService.Decision.APPROVE,
                                        null,
                                        new OtpProof(77L, "123456")))
                .isSameAs(refusal);
        verify(secondFactor, never()).issue(anyLong(), any(), anyString(), anyString());
        verify(secondFactor, never())
                .verify(anyLong(), any(), anyLong(), anyString(), anyString(), anyString());
        assertThat(item.getStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
        verify(decisions, never()).save(any());

        // A return is not an approval: no precondition is asked and it is recorded.
        Mockito.reset(events);
        service.decide(checker, 5L, ApprovalService.Decision.RETURN, "Recheck", null);
        verify(events, never())
                .publishEvent(
                        argThat(
                                (Object e) ->
                                        e
                                                instanceof
                                                in.gov.fci.payroll.platform.approval
                                                        .ApprovalDeciding));
        assertThat(item.getStatus()).isEqualTo(ApprovalStatus.RETURNED);
    }

    /**
     * SRS-1-62 with SRS-G-1 (US-GAP-284): an M-04 staff rate revision is never approved by the user
     * who proposed it, even one who also holds the Payroll Checker role; nothing is decided.
     */
    @Test
    void SRS_1_62_aStaffRateRevisionIsNeverApprovedByItsProposer() {
        ApprovalItem item = pendingAt("M-04", 2);
        task("PAYROLL_CHECKER");
        AccessContext both = mock(AccessContext.class);
        when(both.loginId()).thenReturn("maker");
        when(both.userId()).thenReturn(1L);
        when(both.roleCodes()).thenReturn(Set.of("RULE_ADMINISTRATOR", "PAYROLL_CHECKER"));
        when(both.dataScope()).thenReturn(Set.of(OFFICE));
        assertThatThrownBy(
                        () ->
                                service.decide(
                                        both, 5L, ApprovalService.Decision.APPROVE, null, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.MAKER_CHECKER));
        assertThat(item.getStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
        verify(decisions, never()).save(any());
    }

    /** SRS-G-21: the owner's preconditions met, the final approval goes on to the OTP step. */
    @Test
    void SRS_G_21_aPreconditionMetGoesOnToTheOtpStep() {
        route(WorkflowAction.OfficeLevel.DO, OtpGatedAction.CYCLE_FREEZE_LOCK, null);
        pendingAt("I-20", 2);
        task("PAYROLL_CHECKER");
        when(secondFactor.issue(
                        eq(2L), eq(OtpGatedAction.CYCLE_FREEZE_LOCK), anyString(), anyString()))
                .thenReturn(78L);
        assertThatThrownBy(
                        () ->
                                service.decide(
                                        checker, 5L, ApprovalService.Decision.APPROVE, null, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.OTP_REQUIRED));
        verify(events)
                .publishEvent(
                        argThat(
                                (Object e) ->
                                        e
                                                instanceof
                                                in.gov.fci.payroll.platform.approval
                                                        .ApprovalDeciding));
    }

    /** X-02: an item without a module is decided with the inbox's own permission. */
    @Test
    void R_MC_anItemWithoutAModuleIsDecidedWithTheInboxPermission() {
        route(WorkflowAction.OfficeLevel.DO, null, null);
        ApprovalItem item = pendingAt(null, 2);
        task("PAYROLL_CHECKER");
        service.decide(checker, 5L, ApprovalService.Decision.APPROVE, null, null);
        assertThat(item.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        verify(policy).denialAt(eq(checker), eq("X-02"), anyString(), eq(OFFICE));
    }

    /** SRS-22-16: a task of no office and not yet due is neither placed nor late. */
    @Test
    void SRS_22_16_aTaskOfNoOfficeNotYetDueIsNeitherPlacedNorLate() {
        ApprovalItem item = pendingAt("A-07", 2);
        when(approvals.findMine(eq("maker"), eq(false), any(), any()))
                .thenReturn(new PageImpl<>(List.of(item)));
        when(inbox.findByApprovalId(5L))
                .thenReturn(
                        Optional.of(
                                WorkflowInboxItem.open(
                                        5L, "RULE_APPROVER", null, NOW.plusSeconds(3600))));
        when(maker.dataScope()).thenReturn(Set.of(OFFICE));
        ApprovalService.ItemView view = service.submissions(maker, PAGE).items().get(0);
        assertThat(view.locationLabel()).isNull();
        assertThat(view.slaBreached()).isFalse();
    }

    /**
     * SRS-G-29, SRS l.596: W-02 shows a returned item with the Checker's return reason and who
     * returned it; the maker's own text stays in reason.
     */
    @Test
    void SRS_G_29_aReturnedItemCarriesTheCheckersReasonBesideTheMakersText() {
        ApprovalItem item = pendingAt("A-07", 2);
        item.decide(ApprovalStatus.RETURNED, "checker", "Wrong period", NOW);
        when(approvals.findMine(eq("maker"), eq(false), any(), any()))
                .thenReturn(new PageImpl<>(List.of(item)));
        when(inbox.findByApprovalId(5L)).thenReturn(Optional.empty());
        when(maker.dataScope()).thenReturn(Set.of(OFFICE));
        ApprovalService.ItemView view = service.submissions(maker, PAGE).items().get(0);
        assertThat(view.status()).isEqualTo("RETURNED");
        assertThat(view.reason()).isEqualTo("Order 3");
        assertThat(view.returnReason()).isEqualTo("Wrong period");
        assertThat(view.decidedBy()).isEqualTo("checker");
        assertThat(view.decidedAt()).isEqualTo(NOW);
    }

    /** SRS-G-29 edge: a pending item has no decider and no return reason. */
    @Test
    void SRS_G_29_aPendingItemHasNoDeciderAndNoReturnReason() {
        ApprovalItem item = pendingAt("A-07", 2);
        when(approvals.findMine(eq("maker"), eq(false), any(), any()))
                .thenReturn(new PageImpl<>(List.of(item)));
        when(inbox.findByApprovalId(5L)).thenReturn(Optional.empty());
        when(maker.dataScope()).thenReturn(Set.of(OFFICE));
        ApprovalService.ItemView view = service.submissions(maker, PAGE).items().get(0);
        assertThat(view.returnReason()).isNull();
        assertThat(view.decidedBy()).isNull();
        assertThat(view.decidedAt()).isNull();
    }

    /** SRS-G-29 edge: a rejection keeps no return reason; its reason is the Checker's text. */
    @Test
    void SRS_G_29_aRejectedItemHasNoReturnReason() {
        ApprovalItem item = pendingAt("A-07", 2);
        item.decide(ApprovalStatus.REJECTED, "checker", "Not admissible", NOW);
        when(approvals.findMine(eq("maker"), eq(false), any(), any()))
                .thenReturn(new PageImpl<>(List.of(item)));
        when(inbox.findByApprovalId(5L)).thenReturn(Optional.empty());
        when(maker.dataScope()).thenReturn(Set.of(OFFICE));
        ApprovalService.ItemView view = service.submissions(maker, PAGE).items().get(0);
        assertThat(view.returnReason()).isNull();
        assertThat(view.reason()).isEqualTo("Not admissible");
        assertThat(view.decidedBy()).isEqualTo("checker");
    }

    /** SRS-22-16: a task the SLA monitor flagged as breached is late whatever its due time. */
    @Test
    void SRS_22_16_aFlaggedBreachIsLate() {
        ApprovalItem item = pendingAt("A-07", 2);
        when(approvals.findMine(eq("maker"), eq(false), any(), any()))
                .thenReturn(new PageImpl<>(List.of(item)));
        WorkflowInboxItem task =
                WorkflowInboxItem.open(5L, "RULE_APPROVER", null, NOW.minusSeconds(60));
        assertThat(task.flagIfBreached(NOW)).isTrue();
        when(inbox.findByApprovalId(5L)).thenReturn(Optional.of(task));
        when(maker.dataScope()).thenReturn(Set.of(OFFICE));
        assertThat(service.submissions(maker, PAGE).items().get(0).slaBreached()).isTrue();
    }
}
