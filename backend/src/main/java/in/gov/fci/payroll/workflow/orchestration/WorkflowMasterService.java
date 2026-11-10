package in.gov.fci.payroll.workflow.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.error.Violation;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.InLists;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.workflow.api.ApproverDirectory;
import in.gov.fci.payroll.workflow.api.RoleEquivalence;
import in.gov.fci.payroll.workflow.api.ThirdLevelActions;
import in.gov.fci.payroll.workflow.business.WorkflowRules;
import in.gov.fci.payroll.workflow.business.WorkflowRules.ProposedRoute;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction;
import in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem;
import in.gov.fci.payroll.workflow.business.model.WorkflowLevel;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A-08 Approval Workflow Master (Module 02 F-1; screen X-01): every approval action with its
 * versions and levels; the HQ Administrator proposes a new action or a new version, a Payroll
 * Checker who is not the proposer approves it (SRS-22-19), and only then does it route submissions,
 * from its effective date; items already in progress keep the version they were submitted with
 * (BR-APPR-005). The rules are {@link WorkflowRules}. Also the HQ Administrator's list of items
 * with no approver in scope (SRS-G-28, BR-APPR-007): never auto-approved, fixed by configuration.
 */
@Service
@Loggable
public class WorkflowMasterService {

    static final String SCREEN = "A-08";
    public static final String ENTITY = "approval_workflow_action";

    /** One level of a version, as A-08 shows it. */
    public record LevelView(
            int levelNo, String name, String roleCode, String officeLevel, boolean otpAtLevel) {}

    /** One version of an approval action. */
    public record VersionView(
            Long id,
            int versionNo,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String actionName,
            String moduleCode,
            String screenCode,
            int levelCount,
            String otpAction,
            boolean attachmentRequired,
            Integer targetTatHours,
            Long approvalId,
            List<LevelView> levels,
            // SRS-G-13 (US-GAP-21): the action's items may be approved together on W-01.
            boolean bulkApprovalAllowed) {}

    /** An approval action at one office level: the version in force, a pending one, the history. */
    public record ActionView(
            String actionCode,
            String officeLevel,
            VersionView current,
            VersionView pending,
            List<VersionView> history) {}

    /** An item flagged to the HQ Administrator: nobody but its maker holds the level's role. */
    public record UnstaffedItem(
            Long approvalId,
            String moduleCode,
            String entityName,
            String entityKey,
            String submittedBy,
            Instant submittedAt,
            String waitingForRole,
            String office,
            int level,
            int levelCount) {}

    private final WorkflowRepositories.Actions actions;
    private final WorkflowRepositories.Levels levels;
    private final WorkflowRepositories.Approvals approvals;
    private final WorkflowRepositories.Inbox inbox;
    private final RoleEquivalence roles;
    private final MakerChecker makerChecker;
    private final Authorization policy;
    private final OrgDirectory org;
    private final AuditService audit;
    private final CurrentAccess contexts;
    private final ApproverDirectory approvers;
    private final ThirdLevelActions thirdLevel;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public WorkflowMasterService(
            WorkflowRepositories.Actions actions,
            WorkflowRepositories.Levels levels,
            WorkflowRepositories.Approvals approvals,
            WorkflowRepositories.Inbox inbox,
            RoleEquivalence roles,
            MakerChecker makerChecker,
            Authorization policy,
            OrgDirectory org,
            AuditService audit,
            CurrentAccess contexts,
            ApproverDirectory approvers,
            ThirdLevelActions thirdLevel) {
        this.approvers = approvers;
        this.thirdLevel = thirdLevel;
        this.actions = actions;
        this.levels = levels;
        this.approvals = approvals;
        this.inbox = inbox;
        this.roles = roles;
        this.makerChecker = makerChecker;
        this.policy = policy;
        this.org = org;
        this.audit = audit;
        this.contexts = contexts;
    }

    /** Every approval action by code and office level (A-08 VIEW). */
    @Transactional(readOnly = true)
    public List<ActionView> list(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        LocalDate today = today();
        List<WorkflowAction> all = actions.findAll();
        Map<Long, List<WorkflowLevel>> levelsOf =
                levels
                        .findByWorkflowActionIdIn(all.stream().map(WorkflowAction::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(WorkflowLevel::getWorkflowActionId));
        Map<String, List<WorkflowAction>> byKey =
                all.stream()
                        .sorted(
                                Comparator.comparing(WorkflowAction::getActionCode)
                                        .thenComparing(a -> a.getOfficeLevel().name())
                                        .thenComparing(
                                                WorkflowAction::getVersionNo,
                                                Comparator.reverseOrder()))
                        .collect(
                                Collectors.groupingBy(
                                        a -> a.getActionCode() + "@" + a.getOfficeLevel(),
                                        LinkedHashMap::new,
                                        Collectors.toList()));
        return byKey.values().stream()
                .map(
                        versions -> {
                            WorkflowAction current =
                                    versions.stream()
                                            .filter(a -> a.isActiveOn(today))
                                            .findFirst()
                                            .orElse(null);
                            WorkflowAction pending =
                                    versions.stream()
                                            .filter(
                                                    a ->
                                                            a.getStatus()
                                                                    == WorkflowAction.Status
                                                                            .PENDING_APPROVAL)
                                            .findFirst()
                                            .orElse(null);
                            return new ActionView(
                                    versions.get(0).getActionCode(),
                                    versions.get(0).getOfficeLevel().name(),
                                    current == null ? null : view(current, levelsOf),
                                    pending == null ? null : view(pending, levelsOf),
                                    versions.stream()
                                            .filter(a -> a != current && a != pending)
                                            .map(a -> view(a, levelsOf))
                                            .toList());
                        })
                .toList();
    }

    /**
     * Proposes a new approval action (A-08 CREATE) or a new version of one (A-08 EDIT), at
     * Headquarters only (SRS-22-19). Nothing routes by it until a Payroll Checker approves it.
     */
    @Transactional
    public VersionView propose(AccessContext context, ProposedRoute route, String reason) {
        List<WorkflowAction> existing =
                route.actionCode() == null || route.officeLevel() == null
                        ? List.of()
                        : actions.findByActionCodeAndOfficeLevelOrderByVersionNoDesc(
                                route.actionCode(), route.officeLevel());
        String action = existing.isEmpty() ? "CREATE" : "EDIT";
        policy.require(context, SCREEN, action);
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(ApiErrorCode.ADMIN_HQ_ONLY);
        }
        if (reason == null || reason.isBlank()) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
        if (existing.stream()
                .anyMatch(a -> a.getStatus() == WorkflowAction.Status.PENDING_APPROVAL)) {
            throw new FciException(ApiErrorCode.ROUTE_PENDING);
        }
        LocalDate today = today();
        List<Violation> broken =
                WorkflowRules.violations(
                        route,
                        today,
                        role -> roles.isActiveRole(role, today),
                        // SRS-22-12: the matrix in force when the route takes effect.
                        code ->
                                thirdLevel.named(
                                        code,
                                        route.effectiveFrom() == null
                                                ? today
                                                : route.effectiveFrom()),
                        existing.stream().map(WorkflowAction::getEffectiveFrom).toList());
        // SRS-G-21 / SRS-G-3 (R-OTP; security review 2026-11-04, W-2): exactly the eighteen
        // actions ask an OTP, whatever the configuration: a new version of a route that asks one
        // keeps that OTP code; it can neither drop it nor swap it for another.
        java.util.Optional<in.gov.fci.payroll.platform.security.OtpGatedAction> gated =
                existing.stream()
                        .map(WorkflowAction::getOtpAction)
                        .filter(java.util.Objects::nonNull)
                        .findFirst();
        if (gated.isPresent() && gated.get() != route.otpAction()) {
            broken = new java.util.ArrayList<>(broken);
            broken.add(new Violation("otpAction", "OTP_DROPPED", "a08.rule.OTP_DROPPED"));
        }
        if (!broken.isEmpty()) {
            throw new FciException(ApiErrorCode.ROUTE_INVALID, null, broken, null);
        }
        WorkflowAction proposed =
                actions.save(
                        WorkflowAction.propose(
                                        route.actionCode(),
                                        route.actionName().strip(),
                                        route.moduleCode(),
                                        route.screenCode(),
                                        route.officeLevel(),
                                        route.levelCount(),
                                        route.otpAction(),
                                        route.attachmentRequired(),
                                        route.targetTatHours(),
                                        WorkflowRules.nextVersion(
                                                existing.stream()
                                                        .map(WorkflowAction::getVersionNo)
                                                        .toList()),
                                        route.effectiveFrom())
                                .allowBulkApproval(route.bulkApprovalAllowed()));
        for (WorkflowRules.ProposedLevel l : route.levels()) {
            levels.save(
                    WorkflowLevel.of(
                            proposed.getId(),
                            l.levelNo(),
                            l.roleCode(),
                            l.officeLevel(),
                            l.otpAtLevel()));
        }
        SubmittedApproval item =
                makerChecker.submit(
                        context,
                        new Submission(
                                SCREEN,
                                action,
                                ENTITY,
                                String.valueOf(proposed.getId()),
                                ActionType.CREATE,
                                context.locationId(),
                                "{\"actionCode\":\""
                                        + route.actionCode()
                                        + "\",\"officeLevel\":\""
                                        + route.officeLevel()
                                        + "\",\"version\":"
                                        + proposed.getVersionNo()
                                        + "}",
                                route.effectiveFrom(),
                                reason.strip()),
                        null);
        proposed.linkApproval(item.id());
        actions.save(proposed);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(proposed.getId()),
                                "action_status",
                                null,
                                proposed.getStatus().name(),
                                reason.strip())
                        .approval(item.id())
                        .module(SCREEN));
        return view(
                proposed,
                Map.of(
                        proposed.getId(),
                        levels.findByWorkflowActionIdOrderByLevelNo(proposed.getId())));
    }

    /**
     * SRS-G-28 / BR-APPR-007 with UC-07 (l.588): items waiting with nobody but their maker to
     * decide them, oldest first (their age from the submission time). An item stays on the list
     * "until a Checker is assigned": one whose role now has a holder in scope other than its maker
     * is no longer listed (US-GAP-103), and is in that holder's inbox.
     */
    @Transactional(readOnly = true)
    public List<UnstaffedItem> unstaffed(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        List<WorkflowInboxItem> flagged =
                inbox.findByStatus(WorkflowInboxItem.Status.PENDING).stream()
                        .filter(WorkflowInboxItem::isNoApprover)
                        .toList();
        // Approval items are read once for the flagged tasks (no query per row), in chunks of at
        // most 1,000 ids (Oracle's IN-list limit; Wave C review).
        Map<Long, ApprovalItem> items = new LinkedHashMap<>();
        List<Long> ids = flagged.stream().map(WorkflowInboxItem::getApprovalId).toList();
        for (int from = 0; from < ids.size(); from += InLists.MAX) {
            approvals
                    .findAllById(ids.subList(from, Math.min(ids.size(), from + InLists.MAX)))
                    .forEach(i -> items.put(i.getId(), i));
        }
        // Today's approvers are read once and every item answered in memory (A-08 performance).
        ApproverDirectory.Staffing staffing =
                flagged.isEmpty() ? (r, l, m) -> false : approvers.staffing();
        return flagged.stream()
                .filter(t -> items.containsKey(t.getApprovalId()))
                .filter(
                        t ->
                                !staffing.hasApprover(
                                        t.getAssignedRoleCode(),
                                        t.getOrgScopeId(),
                                        items.get(t.getApprovalId()).getSubmittedBy()))
                .map(
                        t -> {
                            ApprovalItem i = items.get(t.getApprovalId());
                            return new UnstaffedItem(
                                    i.getId(),
                                    i.getModuleCode(),
                                    i.getEntityName(),
                                    i.getEntityKey(),
                                    i.getSubmittedBy(),
                                    i.getSubmittedAt(),
                                    t.getAssignedRoleCode(),
                                    t.getOrgScopeId() == null
                                            ? null
                                            : org.location(t.getOrgScopeId())
                                                    .map(OrgLocation::displayLabel)
                                                    .orElse(null),
                                    i.getCurrentLevel(),
                                    i.getLevelCount());
                        })
                .sorted(
                        Comparator.comparing(UnstaffedItem::submittedAt)
                                .thenComparing(UnstaffedItem::approvalId))
                .toList();
    }

    /**
     * The checker's decision on a proposed version (same transaction as the decision): approved, it
     * is in force from its date (today at the earliest), every version still open on that date ends
     * the day before (one that has not started by then is SUPERSEDED), and the audit compares it
     * with the version it replaces on that date; rejected or returned, it is cancelled and never
     * routes anything.
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY)) {
            return;
        }
        WorkflowAction proposed =
                actions.findById(Long.valueOf(decision.entityKey()))
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (!decision.approved()) {
            proposed.cancel();
            actions.save(proposed);
            return;
        }
        LocalDate today = today();
        LocalDate from =
                proposed.getEffectiveFrom().isBefore(today) ? today : proposed.getEffectiveFrom();
        Map<String, String> before = null;
        List<AuditService.Entry> ended = new java.util.ArrayList<>();
        for (WorkflowAction previous :
                actions.findByActionCodeAndOfficeLevelOrderByVersionNoDesc(
                        proposed.getActionCode(), proposed.getOfficeLevel())) {
            if (!Objects.equals(previous.getId(), proposed.getId())
                    && previous.getStatus() == WorkflowAction.Status.ACTIVE
                    && (previous.getEffectiveTo() == null
                            || !previous.getEffectiveTo().isBefore(from))) {
                // The old values are the route this one replaces on its date (SRS-20-2): the
                // version in force that day, which for a future date may itself be a future one.
                if (before == null && previous.isActiveOn(from)) {
                    before =
                            WorkflowHistory.image(
                                    previous,
                                    levels.findByWorkflowActionIdOrderByLevelNo(previous.getId()));
                }
                String oldTo =
                        previous.getEffectiveTo() == null
                                ? null
                                : previous.getEffectiveTo().toString();
                boolean dateMoved = previous.endBefore(from, today);
                actions.save(previous);
                ended.add(closure(previous, oldTo, dateMoved));
            }
        }
        actions.flush();
        proposed.approve(from);
        actions.save(proposed);
        // SRS-20-2: the applied route, field by field, in the decision's transaction.
        List<AuditService.Entry> entries = new java.util.ArrayList<>(ended);
        entries.addAll(
                in.gov.fci.payroll.audit.api.FieldChanges.between(
                        ENTITY,
                        WorkflowHistory.key(proposed),
                        before,
                        WorkflowHistory.image(
                                proposed,
                                levels.findByWorkflowActionIdOrderByLevelNo(proposed.getId())),
                        decision.reason() == null || decision.reason().isBlank()
                                ? "Approved"
                                : decision.reason()));
        audit.recordAll(
                decision.checkerLoginId(),
                entries.stream()
                        .map(e -> e.approval(decision.approvalId()).module(SCREEN).effective(from))
                        .toList());
    }

    private LocalDate today() {
        return contexts.today();
    }

    /**
     * The audit row of a version the approved one replaces (SRS-20-2): its new end date, or, for a
     * version replaced before it started, its status (it never came into force; its dates stay).
     */
    private static AuditService.Entry closure(
            WorkflowAction previous, String oldTo, boolean dateMoved) {
        String version = "Version " + previous.getVersionNo();
        AuditService.Entry entry =
                dateMoved
                        ? AuditService.Entry.change(
                                ENTITY,
                                WorkflowHistory.key(previous),
                                "effective_to",
                                oldTo,
                                String.valueOf(previous.getEffectiveTo()),
                                version + " closed")
                        : AuditService.Entry.change(
                                ENTITY,
                                WorkflowHistory.key(previous),
                                "action_status",
                                WorkflowAction.Status.ACTIVE.name(),
                                previous.getStatus().name(),
                                version + " replaced before it started");
        return entry.type(in.gov.fci.payroll.audit.api.ChangeType.END_DATE);
    }

    private static VersionView view(WorkflowAction a, Map<Long, List<WorkflowLevel>> levelsOf) {
        return new VersionView(
                a.getId(),
                a.getVersionNo(),
                a.getStatus().name(),
                a.getEffectiveFrom(),
                a.getEffectiveTo(),
                a.getActionName(),
                a.getModuleCode(),
                a.getScreenCode(),
                a.getLevelCount(),
                a.getOtpAction() == null ? null : a.getOtpAction().name(),
                a.isAttachmentRequired(),
                a.getTargetTatHours(),
                a.getApprovalId(),
                levelsOf.getOrDefault(a.getId(), List.of()).stream()
                        .sorted(Comparator.comparingInt(WorkflowLevel::getLevelNo))
                        .map(
                                l ->
                                        new LevelView(
                                                l.getLevelNo(),
                                                l.getName().name(),
                                                l.getRoleCode(),
                                                l.getOfficeLevel().name(),
                                                l.isOtpAtLevel()))
                        .toList(),
                a.isBulkApprovalAllowed());
    }
}
