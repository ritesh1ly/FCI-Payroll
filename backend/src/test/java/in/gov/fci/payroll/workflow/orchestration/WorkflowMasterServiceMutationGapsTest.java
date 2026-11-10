package in.gov.fci.payroll.workflow.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.persistence.InLists;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.workflow.api.ApproverDirectory;
import in.gov.fci.payroll.workflow.api.RoleEquivalence;
import in.gov.fci.payroll.workflow.api.ThirdLevelActions;
import in.gov.fci.payroll.workflow.business.WorkflowRules.ProposedLevel;
import in.gov.fci.payroll.workflow.business.WorkflowRules.ProposedRoute;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction.OfficeLevel;
import in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem;
import in.gov.fci.payroll.workflow.business.model.WorkflowLevel;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Kills the PIT survivors of {@link WorkflowMasterService} (A-08, SRS-22-15, SRS-22-19, SRS-22-12,
 * SRS-G-21, SRS-G-28, SRS-20-2): the list view, the whole of a proposal (checks in their order,
 * what is saved, submitted and audited) and the edges of the unstaffed list and the decision.
 */
class WorkflowMasterServiceMutationGapsTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private final WorkflowRepositories.Actions actions = mock(WorkflowRepositories.Actions.class);
    private final WorkflowRepositories.Levels levels = mock(WorkflowRepositories.Levels.class);
    private final WorkflowRepositories.Approvals approvals =
            mock(WorkflowRepositories.Approvals.class);
    private final WorkflowRepositories.Inbox inbox = mock(WorkflowRepositories.Inbox.class);
    private final RoleEquivalence roles = mock(RoleEquivalence.class);
    private final MakerChecker makerChecker = mock(MakerChecker.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final ApproverDirectory approvers = mock(ApproverDirectory.class);
    private final ThirdLevelActions thirdLevel = mock(ThirdLevelActions.class);

    private final WorkflowMasterService service =
            new WorkflowMasterService(
                    actions,
                    levels,
                    approvals,
                    inbox,
                    roles,
                    makerChecker,
                    policy,
                    mock(OrgDirectory.class),
                    audit,
                    contexts,
                    approvers,
                    thirdLevel);

    private final AccessContext admin =
            new AccessContext(
                    1L,
                    "sysadmin@x",
                    "Admin",
                    "s",
                    "P",
                    9L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(9L));

    private long nextId = 100;

    WorkflowMasterServiceMutationGapsTest() {
        when(contexts.today()).thenReturn(TODAY);
        when(policy.isAtHeadquarters(admin)).thenReturn(true);
        when(roles.isActiveRole(any(), any())).thenReturn(true);
        when(thirdLevel.named(any(), any())).thenReturn(true);
        when(actions.save(any()))
                .thenAnswer(
                        call -> {
                            WorkflowAction saved = call.getArgument(0);
                            if (saved.getId() == null) {
                                ReflectionTestUtils.setField(saved, "id", 77L);
                            }
                            return saved;
                        });
        when(makerChecker.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(555L, ApprovalStatus.PENDING_APPROVAL, null));
    }

    private static void refused(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private WorkflowAction version(
            String code,
            OfficeLevel level,
            int no,
            LocalDate from,
            LocalDate to,
            WorkflowAction.Status status,
            OtpGatedAction otp) {
        WorkflowAction a =
                WorkflowAction.propose(
                        code, "Name " + code, "PAYROLL", "C-13", level, 2, otp, true, 48, no, from);
        ReflectionTestUtils.setField(a, "id", nextId++);
        ReflectionTestUtils.setField(a, "status", status);
        ReflectionTestUtils.setField(a, "effectiveTo", to);
        return a;
    }

    private static List<ProposedLevel> twoLevels(boolean checkerOtp) {
        return List.of(
                new ProposedLevel(1, "PAYROLL_MAKER", OfficeLevel.DO, false),
                new ProposedLevel(2, "PAYROLL_CHECKER", OfficeLevel.DO, checkerOtp));
    }

    private static ProposedRoute route(OtpGatedAction otp, LocalDate from) {
        return new ProposedRoute(
                "C13_CREATE",
                "  Batch job  ",
                "PAYROLL",
                "C-13",
                OfficeLevel.DO,
                2,
                otp,
                true,
                24,
                from,
                twoLevels(otp != null),
                true);
    }

    // ------------------------------------------------------------------ list

    @Test
    void SRS_22_19_theListNeedsA08ViewAndGroupsVersionsByCodeAndOfficeLevel() {
        WorkflowAction current =
                version(
                        "C13_CREATE",
                        OfficeLevel.DO,
                        2,
                        TODAY.minusDays(10),
                        null,
                        WorkflowAction.Status.ACTIVE,
                        OtpGatedAction.DISBURSEMENT_RELEASE);
        WorkflowAction pending =
                version(
                        "C13_CREATE",
                        OfficeLevel.DO,
                        3,
                        TODAY.plusDays(5),
                        null,
                        WorkflowAction.Status.PENDING_APPROVAL,
                        null);
        WorkflowAction old =
                version(
                        "C13_CREATE",
                        OfficeLevel.DO,
                        1,
                        TODAY.minusDays(100),
                        TODAY.minusDays(11),
                        WorkflowAction.Status.SUPERSEDED,
                        null);
        WorkflowAction hq =
                version(
                        "C13_CREATE",
                        OfficeLevel.HQ,
                        1,
                        TODAY.minusDays(1),
                        null,
                        WorkflowAction.Status.ACTIVE,
                        null);
        WorkflowAction other =
                version(
                        "A01_EDIT",
                        OfficeLevel.RO,
                        1,
                        TODAY.plusDays(30),
                        null,
                        WorkflowAction.Status.DRAFT,
                        null);
        when(actions.findAll()).thenReturn(List.of(old, other, pending, hq, current));
        // the levels of the current version, handed over out of order
        WorkflowLevel l2 =
                WorkflowLevel.of(current.getId(), 2, "PAYROLL_CHECKER", OfficeLevel.DO, true);
        WorkflowLevel l1 =
                WorkflowLevel.of(current.getId(), 1, "PAYROLL_MAKER", OfficeLevel.RO, false);
        when(levels.findByWorkflowActionIdIn(any())).thenReturn(List.of(l2, l1));
        current.allowBulkApproval(true);
        current.linkApproval(31L);

        List<WorkflowMasterService.ActionView> view = service.list(admin);

        verify(policy).require(admin, "A-08", "VIEW");
        assertThat(view)
                .extracting(WorkflowMasterService.ActionView::actionCode)
                .containsExactly("A01_EDIT", "C13_CREATE", "C13_CREATE");
        assertThat(view)
                .extracting(WorkflowMasterService.ActionView::officeLevel)
                .containsExactly("RO", "DO", "HQ");

        WorkflowMasterService.ActionView a01 = view.get(0);
        assertThat(a01.current()).as("not yet in force").isNull();
        assertThat(a01.pending()).as("DRAFT is not pending approval").isNull();
        assertThat(a01.history())
                .extracting(WorkflowMasterService.VersionView::versionNo)
                .containsExactly(1);

        WorkflowMasterService.ActionView dos = view.get(1);
        assertThat(dos.current().versionNo()).isEqualTo(2);
        assertThat(dos.pending().versionNo()).isEqualTo(3);
        assertThat(dos.history())
                .extracting(WorkflowMasterService.VersionView::versionNo)
                .containsExactly(1);

        WorkflowMasterService.ActionView hqView = view.get(2);
        assertThat(hqView.current().versionNo()).isEqualTo(1);
        assertThat(hqView.pending()).isNull();
        assertThat(hqView.history()).isEmpty();

        WorkflowMasterService.VersionView v = dos.current();
        assertThat(v.id()).isEqualTo(current.getId());
        assertThat(v.status()).isEqualTo("ACTIVE");
        assertThat(v.effectiveFrom()).isEqualTo(TODAY.minusDays(10));
        assertThat(v.effectiveTo()).isNull();
        assertThat(v.actionName()).isEqualTo("Name C13_CREATE");
        assertThat(v.moduleCode()).isEqualTo("PAYROLL");
        assertThat(v.screenCode()).isEqualTo("C-13");
        assertThat(v.levelCount()).isEqualTo(2);
        assertThat(v.otpAction()).isEqualTo("DISBURSEMENT_RELEASE");
        assertThat(v.attachmentRequired()).isTrue();
        assertThat(v.targetTatHours()).isEqualTo(48);
        assertThat(v.approvalId()).isEqualTo(31L);
        assertThat(v.bulkApprovalAllowed()).isTrue();
        assertThat(v.levels())
                .containsExactly(
                        new WorkflowMasterService.LevelView(
                                1, "MAKER", "PAYROLL_MAKER", "RO", false),
                        new WorkflowMasterService.LevelView(
                                2, "CHECKER", "PAYROLL_CHECKER", "DO", true));
        assertThat(dos.pending().otpAction()).isNull();
        assertThat(dos.pending().levels()).isEmpty();
        assertThat(dos.pending().bulkApprovalAllowed()).isFalse();
    }

    @Test
    void SRS_22_19_anEmptyCatalogueGivesAnEmptyList() {
        when(actions.findAll()).thenReturn(List.of());
        when(levels.findByWorkflowActionIdIn(any())).thenReturn(List.of());
        assertThat(service.list(admin)).isEmpty();
    }

    @Test
    void SRS_22_15_aVersionEndedBeforeTodayIsNotCurrentButOneEndingTodayIs() {
        WorkflowAction endsToday =
                version(
                        "X01_A",
                        OfficeLevel.DO,
                        1,
                        TODAY.minusDays(9),
                        TODAY,
                        WorkflowAction.Status.ACTIVE,
                        null);
        WorkflowAction ended =
                version(
                        "X02_A",
                        OfficeLevel.DO,
                        1,
                        TODAY.minusDays(9),
                        TODAY.minusDays(1),
                        WorkflowAction.Status.ACTIVE,
                        null);
        when(actions.findAll()).thenReturn(List.of(endsToday, ended));
        when(levels.findByWorkflowActionIdIn(any())).thenReturn(List.of());
        List<WorkflowMasterService.ActionView> view = service.list(admin);
        assertThat(view.get(0).current()).isNotNull();
        assertThat(view.get(1).current()).isNull();
        assertThat(view.get(1).history()).hasSize(1);
    }

    // --------------------------------------------------------------- propose

    @Test
    void SRS_22_19_aNewRouteIsSavedWithItsLevelsSubmittedAndAudited() {
        ProposedRoute route = route(OtpGatedAction.DISBURSEMENT_RELEASE, TODAY.plusDays(3));
        when(levels.findByWorkflowActionIdOrderByLevelNo(77L))
                .thenReturn(
                        List.of(WorkflowLevel.of(77L, 1, "PAYROLL_MAKER", OfficeLevel.DO, false)));
        when(actions.findByActionCodeAndOfficeLevelOrderByVersionNoDesc(
                        "C13_CREATE", OfficeLevel.DO))
                .thenReturn(List.of());

        WorkflowMasterService.VersionView proposed = service.propose(admin, route, "  New route  ");

        verify(policy).require(admin, "A-08", "CREATE");
        ArgumentCaptor<WorkflowAction> saved = ArgumentCaptor.forClass(WorkflowAction.class);
        verify(actions, times(2)).save(saved.capture());
        WorkflowAction action = saved.getAllValues().get(0);
        assertThat(action).isSameAs(saved.getAllValues().get(1));
        assertThat(action.getActionCode()).isEqualTo("C13_CREATE");
        assertThat(action.getActionName()).as("stripped").isEqualTo("Batch job");
        assertThat(action.getModuleCode()).isEqualTo("PAYROLL");
        assertThat(action.getScreenCode()).isEqualTo("C-13");
        assertThat(action.getOfficeLevel()).isEqualTo(OfficeLevel.DO);
        assertThat(action.getLevelCount()).isEqualTo(2);
        assertThat(action.getOtpAction()).isEqualTo(OtpGatedAction.DISBURSEMENT_RELEASE);
        assertThat(action.isAttachmentRequired()).isTrue();
        assertThat(action.getTargetTatHours()).isEqualTo(24);
        assertThat(action.getVersionNo()).isEqualTo(1);
        assertThat(action.getEffectiveFrom()).isEqualTo(TODAY.plusDays(3));
        assertThat(action.isBulkApprovalAllowed()).isTrue();
        assertThat(action.getStatus()).isEqualTo(WorkflowAction.Status.PENDING_APPROVAL);
        assertThat(action.getApprovalId()).as("linked to the queue item").isEqualTo(555L);

        ArgumentCaptor<WorkflowLevel> level = ArgumentCaptor.forClass(WorkflowLevel.class);
        verify(levels, times(2)).save(level.capture());
        assertThat(level.getAllValues())
                .extracting(
                        WorkflowLevel::getWorkflowActionId,
                        WorkflowLevel::getLevelNo,
                        WorkflowLevel::getRoleCode,
                        WorkflowLevel::getOfficeLevel,
                        WorkflowLevel::isOtpAtLevel)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                77L, 1, "PAYROLL_MAKER", OfficeLevel.DO, false),
                        org.assertj.core.groups.Tuple.tuple(
                                77L, 2, "PAYROLL_CHECKER", OfficeLevel.DO, true));

        ArgumentCaptor<Submission> submission = ArgumentCaptor.forClass(Submission.class);
        verify(makerChecker).submit(eq(admin), submission.capture(), eq(null));
        Submission s = submission.getValue();
        assertThat(s.workflowAction()).isEqualTo("A08_CREATE");
        assertThat(s.moduleCode()).isEqualTo("A-08");
        assertThat(s.actionCode()).isEqualTo("CREATE");
        assertThat(s.entityName()).isEqualTo(WorkflowMasterService.ENTITY);
        assertThat(s.entityKey()).isEqualTo("77");
        assertThat(s.actionType()).isEqualTo(ActionType.CREATE);
        assertThat(s.locationId()).isEqualTo(9L);
        assertThat(s.payload())
                .isEqualTo("{\"actionCode\":\"C13_CREATE\",\"officeLevel\":\"DO\",\"version\":1}");
        assertThat(s.effectiveDate()).isEqualTo(TODAY.plusDays(3));
        assertThat(s.reason()).isEqualTo("New route");

        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("sysadmin@x"), entry.capture());
        assertThat(entry.getValue().entityName()).isEqualTo(WorkflowMasterService.ENTITY);
        assertThat(entry.getValue().entityKey()).isEqualTo("77");
        assertThat(entry.getValue().fieldName()).isEqualTo("action_status");
        assertThat(entry.getValue().oldValue()).isNull();
        assertThat(entry.getValue().newValue()).isEqualTo("PENDING_APPROVAL");
        assertThat(entry.getValue().reason()).isEqualTo("New route");
        assertThat(entry.getValue().approvalId()).isEqualTo(555L);
        assertThat(entry.getValue().moduleCode()).isEqualTo("A-08");

        assertThat(proposed.id()).isEqualTo(77L);
        assertThat(proposed.versionNo()).isEqualTo(1);
        assertThat(proposed.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(proposed.levels()).hasSize(1);
    }

    @Test
    void SRS_22_19_aNewVersionIsAnEditNumberedAfterTheHighestAndKeepsTheOtpCode() {
        WorkflowAction v3 =
                version(
                        "C13_CREATE",
                        OfficeLevel.DO,
                        3,
                        TODAY.minusDays(30),
                        null,
                        WorkflowAction.Status.ACTIVE,
                        OtpGatedAction.DISBURSEMENT_RELEASE);
        WorkflowAction v1 =
                version(
                        "C13_CREATE",
                        OfficeLevel.DO,
                        1,
                        TODAY.minusDays(90),
                        TODAY.minusDays(31),
                        WorkflowAction.Status.SUPERSEDED,
                        null);
        when(actions.findByActionCodeAndOfficeLevelOrderByVersionNoDesc(
                        "C13_CREATE", OfficeLevel.DO))
                .thenReturn(List.of(v3, v1));

        WorkflowMasterService.VersionView proposed =
                service.propose(admin, route(OtpGatedAction.DISBURSEMENT_RELEASE, TODAY), "Revise");

        verify(policy).require(admin, "A-08", "EDIT");
        assertThat(proposed.versionNo()).isEqualTo(4);
        ArgumentCaptor<Submission> submission = ArgumentCaptor.forClass(Submission.class);
        verify(makerChecker).submit(eq(admin), submission.capture(), eq(null));
        assertThat(submission.getValue().workflowAction()).isEqualTo("A08_EDIT");
        assertThat(submission.getValue().actionCode()).isEqualTo("EDIT");
        assertThat(submission.getValue().payload()).contains("\"version\":4");
        assertThat(submission.getValue().effectiveDate())
                .as("today is not in the past")
                .isEqualTo(TODAY);
    }

    @Test
    void SRS_G_21_aNewVersionCannotDropOrSwapTheOtpCodeOfARouteThatAsksOne() {
        WorkflowAction v1 =
                version(
                        "C13_CREATE",
                        OfficeLevel.DO,
                        1,
                        TODAY.minusDays(30),
                        null,
                        WorkflowAction.Status.ACTIVE,
                        OtpGatedAction.DISBURSEMENT_RELEASE);
        when(actions.findByActionCodeAndOfficeLevelOrderByVersionNoDesc(
                        "C13_CREATE", OfficeLevel.DO))
                .thenReturn(List.of(v1));
        for (OtpGatedAction replacement :
                new OtpGatedAction[] {null, OtpGatedAction.FNF_APPROVAL}) {
            assertThatThrownBy(() -> service.propose(admin, route(replacement, TODAY), "Revise"))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e -> {
                                assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.ROUTE_INVALID);
                                assertThat(e.getViolations())
                                        .extracting(v -> v.field() + ":" + v.code())
                                        .contains("otpAction:OTP_DROPPED");
                            });
        }
        verify(actions, never()).save(any());
        verify(makerChecker, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_G_21_aRouteWithoutAnOtpCodeMayGainOneInANewVersion() {
        WorkflowAction v1 =
                version(
                        "C13_CREATE",
                        OfficeLevel.DO,
                        1,
                        TODAY.minusDays(30),
                        null,
                        WorkflowAction.Status.ACTIVE,
                        null);
        when(actions.findByActionCodeAndOfficeLevelOrderByVersionNoDesc(
                        "C13_CREATE", OfficeLevel.DO))
                .thenReturn(List.of(v1));
        assertThat(
                        service.propose(admin, route(OtpGatedAction.FNF_APPROVAL, TODAY), "Gain")
                                .otpAction())
                .isEqualTo("FNF_APPROVAL");
    }

    @Test
    void SRS_22_19_aRouteIsRefusedWhileAnotherVersionAwaitsApproval() {
        WorkflowAction waiting =
                version(
                        "C13_CREATE",
                        OfficeLevel.DO,
                        2,
                        TODAY,
                        null,
                        WorkflowAction.Status.PENDING_APPROVAL,
                        null);
        when(actions.findByActionCodeAndOfficeLevelOrderByVersionNoDesc(
                        "C13_CREATE", OfficeLevel.DO))
                .thenReturn(List.of(waiting));
        refused(
                () -> service.propose(admin, route(null, TODAY), "Another"),
                ApiErrorCode.ROUTE_PENDING);
        verify(actions, never()).save(any());
    }

    @Test
    void SRS_22_19_theCodeAndLevelDecideWhetherTheRouteIsKnown() {
        // no action code: nothing is looked up, the route is new and invalid
        ProposedRoute noCode =
                new ProposedRoute(
                        null,
                        "n",
                        "PAYROLL",
                        "C-13",
                        OfficeLevel.DO,
                        2,
                        null,
                        false,
                        24,
                        TODAY,
                        twoLevels(false));
        refused(() -> service.propose(admin, noCode, "r"), ApiErrorCode.ROUTE_INVALID);
        verify(policy).require(admin, "A-08", "CREATE");
        // an action code but no office level: nothing is looked up either
        ProposedRoute noLevel =
                new ProposedRoute(
                        "C13_CREATE",
                        "n",
                        "PAYROLL",
                        "C-13",
                        null,
                        2,
                        null,
                        false,
                        24,
                        TODAY,
                        twoLevels(false));
        refused(() -> service.propose(admin, noLevel, "r"), ApiErrorCode.ROUTE_INVALID);
        verify(actions, never()).findByActionCodeAndOfficeLevelOrderByVersionNoDesc(any(), any());
    }

    @Test
    void SRS_22_19_aProposalAwayFromHeadquartersIsRefusedBeforeAnythingIsChecked() {
        when(policy.isAtHeadquarters(admin)).thenReturn(false);
        refused(() -> service.propose(admin, route(null, TODAY), ""), ApiErrorCode.ADMIN_HQ_ONLY);
        verify(actions, never()).save(any());
    }

    @Test
    void SRS_22_16_everyLevelRoleIsCheckedAgainstTheCatalogueOnTheDayOfTheProposal() {
        when(roles.isActiveRole("PAYROLL_CHECKER", TODAY)).thenReturn(false);
        assertThatThrownBy(() -> service.propose(admin, route(null, TODAY), "r"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.ROUTE_INVALID);
                            assertThat(e.getViolations())
                                    .extracting(v -> v.field() + ":" + v.code())
                                    .containsExactly("levels[1]:LEVEL_ROLE");
                        });
        verify(roles).isActiveRole("PAYROLL_MAKER", TODAY);
        verify(roles).isActiveRole("PAYROLL_CHECKER", TODAY);
    }

    @Test
    void SRS_22_12_aThirdLevelIsAcceptedOnlyWhereTheMatrixNamesItOnTheEffectiveDate() {
        List<ProposedLevel> three =
                List.of(
                        new ProposedLevel(1, "PAYROLL_MAKER", OfficeLevel.DO, false),
                        new ProposedLevel(2, "PAYROLL_CHECKER", OfficeLevel.DO, false),
                        new ProposedLevel(3, "PAYROLL_APPROVER", OfficeLevel.DO, false));
        ProposedRoute dated =
                new ProposedRoute(
                        "C13_CREATE",
                        "n",
                        "PAYROLL",
                        "C-13",
                        OfficeLevel.DO,
                        3,
                        null,
                        false,
                        24,
                        TODAY.plusDays(40),
                        three);
        when(thirdLevel.named("C13_CREATE", TODAY.plusDays(40))).thenReturn(false);
        assertThatThrownBy(() -> service.propose(admin, dated, "r"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getViolations())
                                        .extracting(v -> v.code())
                                        .containsExactly("THIRD_LEVEL_NOT_NAMED"));
        when(thirdLevel.named("C13_CREATE", TODAY.plusDays(40))).thenReturn(true);
        assertThat(service.propose(admin, dated, "r").levelCount()).isEqualTo(3);
    }

    // ----------------------------------------------------------- unstaffed

    private void flag(int count) {
        List<WorkflowInboxItem> items = new ArrayList<>();
        for (long i = 1; i <= count; i++) {
            WorkflowInboxItem item = WorkflowInboxItem.open(i, "PAYROLL_CHECKER", 40L, null);
            item.flagNoApprover(true);
            items.add(item);
        }
        when(inbox.findByStatus(WorkflowInboxItem.Status.PENDING)).thenReturn(items);
    }

    @Test
    void SRS_G_28_approvalsAreReadInChunksOfAtMostTheOracleInListLimit() {
        flag(InLists.MAX);
        when(approvals.findAllById(any())).thenReturn(List.of());
        assertThat(service.unstaffed(admin)).isEmpty();
        ArgumentCaptor<Iterable<Long>> ids = idCaptor();
        verify(approvals, times(1)).findAllById(ids.capture());
        assertThat(ids.getValue()).hasSize(InLists.MAX);
    }

    @Test
    void SRS_G_28_oneItemOverTheLimitIsReadInASecondChunkOfOne() {
        flag(InLists.MAX + 1);
        when(approvals.findAllById(any())).thenReturn(List.of());
        service.unstaffed(admin);
        ArgumentCaptor<Iterable<Long>> ids = idCaptor();
        verify(approvals, times(2)).findAllById(ids.capture());
        assertThat(ids.getAllValues().get(0)).hasSize(InLists.MAX);
        assertThat(ids.getAllValues().get(1)).hasSize(1);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Iterable<Long>> idCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Iterable.class);
    }

    @Test
    void SRS_G_28_aFlaggedTaskWhoseApprovalIsGoneIsNotListed() {
        flag(1);
        when(approvals.findAllById(any())).thenReturn(List.of());
        when(approvers.staffing()).thenReturn((r, l, m) -> false);
        assertThat(service.unstaffed(admin)).isEmpty();
    }

    @Test
    void SRS_G_28_aTaskThatIsNotFlaggedIsNeverListed() {
        WorkflowInboxItem plain = WorkflowInboxItem.open(5L, "PAYROLL_CHECKER", 40L, null);
        when(inbox.findByStatus(WorkflowInboxItem.Status.PENDING)).thenReturn(List.of(plain));
        assertThat(service.unstaffed(admin)).isEmpty();
        verify(approvals, never()).findAllById(any());
        verify(approvers, never()).staffing();
    }

    // ------------------------------------------------------------ decision

    @Test
    void SRS_22_15_theEndedVersionsAreSavedAndFlushedBeforeTheApprovedOneIsSaved() {
        WorkflowAction previous =
                version(
                        "C13_CREATE",
                        OfficeLevel.DO,
                        1,
                        TODAY.minusDays(60),
                        null,
                        WorkflowAction.Status.ACTIVE,
                        null);
        WorkflowAction proposed =
                version(
                        "C13_CREATE",
                        OfficeLevel.DO,
                        2,
                        TODAY.plusDays(2),
                        null,
                        WorkflowAction.Status.PENDING_APPROVAL,
                        null);
        when(actions.findById(proposed.getId())).thenReturn(java.util.Optional.of(proposed));
        when(actions.findByActionCodeAndOfficeLevelOrderByVersionNoDesc(
                        "C13_CREATE", OfficeLevel.DO))
                .thenReturn(List.of(proposed, previous));
        when(levels.findByWorkflowActionIdOrderByLevelNo(anyLong())).thenReturn(List.of());

        service.on(
                new ApprovalDecided(
                        5L,
                        "A-08",
                        WorkflowMasterService.ENTITY,
                        String.valueOf(proposed.getId()),
                        ApprovalStatus.APPROVED,
                        null,
                        null,
                        "checker.hq@x",
                        Instant.now()));

        var order = inOrder(actions);
        order.verify(actions).save(previous);
        order.verify(actions).flush();
        order.verify(actions).save(proposed);
        assertThat(previous.getEffectiveTo()).isEqualTo(TODAY.plusDays(1));
        assertThat(proposed.getStatus()).isEqualTo(WorkflowAction.Status.ACTIVE);
        assertThat(proposed.getEffectiveFrom()).isEqualTo(TODAY.plusDays(2));
    }

    @Test
    void SRS_22_19_theOfficeLevelsOfOneCodeAreListedInTheirOwnOrderNotByTheirNewestVersion() {
        WorkflowAction district =
                version(
                        "C13_CREATE",
                        OfficeLevel.DO,
                        1,
                        TODAY.minusDays(10),
                        null,
                        WorkflowAction.Status.ACTIVE,
                        null);
        WorkflowAction headquarters =
                version(
                        "C13_CREATE",
                        OfficeLevel.HQ,
                        5,
                        TODAY.minusDays(10),
                        null,
                        WorkflowAction.Status.ACTIVE,
                        null);
        WorkflowAction region =
                version(
                        "C13_CREATE",
                        OfficeLevel.RO,
                        3,
                        TODAY.minusDays(10),
                        null,
                        WorkflowAction.Status.ACTIVE,
                        null);
        when(actions.findAll()).thenReturn(List.of(headquarters, region, district));
        when(levels.findByWorkflowActionIdIn(any())).thenReturn(List.of());

        assertThat(service.list(admin))
                .extracting(WorkflowMasterService.ActionView::officeLevel)
                .containsExactly("DO", "HQ", "RO");
    }

    @Test
    void SRS_G_28_theUnstaffedListNeedsA08ViewAndReadsNothingWithoutIt() {
        org.mockito.Mockito.doThrow(new FciException(ApiErrorCode.PERMISSION_DENIED))
                .when(policy)
                .require(admin, "A-08", "VIEW");

        refused(() -> service.unstaffed(admin), ApiErrorCode.PERMISSION_DENIED);

        verify(inbox, never()).findByStatus(any());
    }

    @Test
    void SRS_G_28_theUnstaffedListAsksA08ViewAndAnEmptyInboxGivesAnEmptyList() {
        when(inbox.findByStatus(WorkflowInboxItem.Status.PENDING)).thenReturn(List.of());

        assertThat(service.unstaffed(admin)).isEmpty();

        verify(policy).require(admin, "A-08", "VIEW");
        verify(approvers, never()).staffing();
    }
}
