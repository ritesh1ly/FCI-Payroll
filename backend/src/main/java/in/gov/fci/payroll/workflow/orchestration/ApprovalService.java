package in.gov.fci.payroll.workflow.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalAdvanced;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalDeciding;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.AttachmentPresence;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.error.ProblemDetailFactory;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
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
import in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem;
import in.gov.fci.payroll.workflow.business.model.WorkflowLevel;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maker-checker on {@code approval_queue}, {@code approval_decision} and {@code
 * workflow_inbox_item}, routed by the Approval Workflow Master ({@code approval_workflow_action}
 * and its 2–3 {@code approval_workflow_level} rows, target DDL v4.0), never by code:
 *
 * <ul>
 *   <li>the maker holds the level-1 role (or a module role of the same base role) and the screen
 *       permission in scope;
 *   <li>each further level is decided by its role with APPROVE on the module at the level's office:
 *       PAYING_UNIT is the item's own unit (no Region approves for another), HQ the Headquarters
 *       office (the decider signs in at HQ), ZO / RO / DO the item's office of that type;
 *   <li>an approval below the last level hands the item to the next level; the last level's
 *       approval (or a rejection or return at any level) is final and is the only one that takes
 *       effect;
 *   <li>the decider is never the submitter, nor the subject of an access assignment, nor someone
 *       who already decided the item at an earlier level;
 *   <li>Headquarters never locks a Region's payroll (LOCK from an HQ login is refused);
 *   <li>an action with {@code otp_required} needs a fresh OTP of the maker for its gated action,
 *       and a level with {@code otp_at_level} one of the decider: the first call answers 428 with a
 *       challenge id, the repeat call carries the code.
 * </ul>
 *
 * <p>A final decision's effect belongs to the module that owns the entity: it is published as
 * {@link ApprovalDecided} in the decision's transaction, never applied here.
 */
@Service
@Loggable
public class ApprovalService implements MakerChecker {

    /** {@code entity_name} of a C-02 month-end lock request. */
    public static final String LOCK_ENTITY = "payroll_period_lock";

    public enum Decision {
        APPROVE,
        REJECT,
        RETURN
    }

    /** One inbox or submission row. */
    public record ItemView(
            Long approvalId,
            String moduleCode,
            String entityName,
            String entityKey,
            String actionType,
            String status,
            String submittedBy,
            Instant submittedAt,
            String reason,
            String assignedRoleCode,
            String locationLabel,
            Instant slaDueAt,
            boolean slaBreached,
            int level,
            int levelCount,
            boolean noApproverInScope,
            boolean canDecide,
            String denialKey,
            BigDecimal impactValue,
            long ageDays,
            Integer targetTatHours,
            // SRS-G-13 (US-GAP-21): may be approved in a bulk approval (route allows it, no OTP).
            boolean bulkApproval,
            // SRS-G-29, SRS l.596: the Checker's return reason of a RETURNED item (the maker's own
            // text stays in reason), and who decided the item and when.
            String returnReason,
            String decidedBy,
            Instant decidedAt) {}

    private final WorkflowRepositories.Actions actions;
    private final WorkflowRepositories.Levels levels;
    private final WorkflowRepositories.Approvals approvals;
    private final WorkflowRepositories.Decisions decisions;
    private final WorkflowRepositories.Inbox inbox;
    private final Authorization policy;
    private final CurrentAccess contexts;
    private final OrgDirectory org;
    private final SecondFactor secondFactor;
    private final RoleEquivalence roles;
    private final ApproverDirectory approvers;
    private final List<ApprovalSubjects> subjects;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ApiProperties properties;
    private final AttachmentPresence attachments;

    private final WorkflowRepositories.ScreenActions inventory;

    /**
     * Route id -> the permission its deciders need (routes and the inventory change by release).
     */
    private final java.util.Map<Long, String> decisionActions =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ApprovalService(
            WorkflowRepositories.Actions actions,
            WorkflowRepositories.Levels levels,
            WorkflowRepositories.Approvals approvals,
            WorkflowRepositories.Decisions decisions,
            WorkflowRepositories.Inbox inbox,
            WorkflowRepositories.ScreenActions inventory,
            Authorization policy,
            CurrentAccess contexts,
            OrgDirectory org,
            SecondFactor secondFactor,
            RoleEquivalence roles,
            ApproverDirectory approvers,
            List<ApprovalSubjects> subjects,
            AuditService audit,
            ApplicationEventPublisher events,
            Clock clock,
            ApiProperties properties,
            AttachmentPresence attachments) {
        this.actions = actions;
        this.levels = levels;
        this.approvals = approvals;
        this.decisions = decisions;
        this.inbox = inbox;
        this.inventory = inventory;
        this.policy = policy;
        this.contexts = contexts;
        this.org = org;
        this.secondFactor = secondFactor;
        this.roles = roles;
        this.approvers = approvers;
        this.subjects = subjects;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.properties = properties;
        this.attachments = attachments;
    }

    /**
     * The approval action in force today for {@code actionCode} (newest active version), if any.
     */
    public Optional<WorkflowAction> actionInForce(String actionCode) {
        LocalDate today = contexts.today();
        return actions.findByActionCodeOrderByVersionNoDesc(actionCode).stream()
                .filter(a -> a.isActiveOn(today))
                .findFirst();
    }

    @Override
    @Transactional
    public SubmittedApproval submit(AccessContext context, Submission submission, OtpProof otp) {
        ApprovalItem item = submitItem(context, submission, otp);
        return new SubmittedApproval(item.getId(), item.getStatus(), item.getOtpChallengeId());
    }

    /** Submits a change for checking. In the caller's transaction (or its own). */
    @Transactional
    public ApprovalItem submitItem(AccessContext context, Submission submission, OtpProof otp) {
        policy.requireAt(
                context, submission.moduleCode(), submission.actionCode(), submission.locationId());
        WorkflowAction action =
                actionInForce(submission.workflowAction())
                        .orElseThrow(() -> new FciException(ApiErrorCode.NO_APPROVAL_RULE));
        List<WorkflowLevel> path = levelsOf(action);
        if (context.roleCodes().stream()
                .noneMatch(r -> roles.actsAs(r, path.get(0).getRoleCode()))) {
            throw new FciException(ApiErrorCode.NO_APPROVAL_RULE);
        }
        if ("LOCK".equals(submission.actionCode()) && policy.isAtHeadquarters(context)) {
            throw new FciException(ApiErrorCode.HQ_MAY_NOT_LOCK);
        }
        if (action.isHeadquartersOnly() && !policy.isAtHeadquarters(context)) {
            throw new FciException(ApiErrorCode.HQ_ONLY);
        }
        // SRS-15-29 / SRS-NF-26: one pending change per record, and the refusal names that change.
        approvals
                .findFirstByEntityNameAndEntityKeyAndStatusOrderBySubmittedAtDesc(
                        submission.entityName(),
                        submission.entityKey(),
                        ApprovalStatus.PENDING_APPROVAL)
                .ifPresent(
                        pending -> {
                            throw new FciException(
                                    ApiErrorCode.ALREADY_PENDING_BY,
                                    new Object[] {
                                        pending.getSubmittedBy(),
                                        pending.getSubmittedAt().toString()
                                    },
                                    List.of(),
                                    null);
                        });
        if (submission.reason() == null || submission.reason().isBlank()) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
        requireAttachment(context, action, submission);
        Long challengeId =
                action.isOtpRequired()
                        ? requireOtp(
                                context,
                                action.getOtpAction(),
                                submission.entityName(),
                                submission.entityKey(),
                                otp,
                                false)
                        : null;
        Instant now = clock.instant();
        ApprovalItem item =
                approvals.save(
                        ApprovalItem.submit(
                                submission.moduleCode(),
                                action.getId(),
                                action.getLevelCount(),
                                submission.entityName(),
                                submission.entityKey(),
                                submission.actionType(),
                                submission.payload(),
                                submission.effectiveDate(),
                                context.loginId(),
                                submission.reason(),
                                submission.impactValue(),
                                challengeId,
                                now));
        WorkflowLevel first = path.get(1);
        WorkflowInboxItem task =
                WorkflowInboxItem.open(
                        item.getId(),
                        first.getRoleCode(),
                        officeOf(first, submission.locationId()),
                        due(action, now));
        flagIfNoApprover(item, task, context.loginId(), now);
        inbox.save(task);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                submission.entityName(),
                                submission.entityKey(),
                                submission.actionCode(),
                                null,
                                "PENDING_APPROVAL",
                                submission.reason())
                        .approval(item.getId())
                        .module(submission.moduleCode()));
        return item;
    }

    /**
     * Approves, rejects or returns a pending item at its current level. An approval below the last
     * level moves it on; the final decision applies its effect.
     */
    @Transactional
    public ApprovalItem decide(
            AccessContext context,
            Long approvalId,
            Decision decision,
            String reason,
            OtpProof otp) {
        return decide(context, approvalId, decision, reason, otp, null);
    }

    /**
     * As {@link #decide(AccessContext, Long, Decision, String, OtpProof)}, taken in a bulk approval
     * (SRS-G-13, UC-07 A2): the decision row is written once with the bulk reference (DDL {@code
     * approval_decision.bulk_approval_ref}).
     */
    @Transactional
    public ApprovalItem decide(
            AccessContext context,
            Long approvalId,
            Decision decision,
            String reason,
            OtpProof otp,
            String bulkReference) {
        ApprovalItem item =
                approvals
                        .findById(approvalId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        WorkflowInboxItem task =
                inbox.findByApprovalId(approvalId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        ApiErrorCode denial = denial(context, item, task);
        if (denial != null) {
            FciException refused =
                    new FciException(
                            denial,
                            new Object[] {
                                item.getModuleCode(), decisionAction(item, moduleOf(item))
                            },
                            List.of(),
                            null);
            // OD-130: forging a decision on an item outside the scope. The inbox offers no such
            // decision; the session's own office names nothing the user chose (AccessPolicy).
            throw denial == ApiErrorCode.OUT_OF_SCOPE
                            && !java.util.Objects.equals(task.getOrgScopeId(), context.locationId())
                    ? refused.asAccessOffence()
                    : refused;
        }
        if (decision != Decision.APPROVE && (reason == null || reason.isBlank())) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
        Optional<WorkflowAction> action =
                item.getWorkflowActionId() == null
                        ? Optional.empty()
                        : actions.findById(item.getWorkflowActionId());
        List<WorkflowLevel> path = action.map(this::levelsOf).orElse(List.of());
        WorkflowLevel level =
                path.size() >= item.getCurrentLevel() ? path.get(item.getCurrentLevel() - 1) : null;
        if (decision == Decision.APPROVE
                && (item.isAtFinalLevel() || path.size() <= item.getCurrentLevel())) {
            // The owner's preconditions before the OTP step: a refused approval spends no code.
            events.publishEvent(
                    new ApprovalDeciding(
                            item.getId(),
                            item.getModuleCode(),
                            item.getEntityName(),
                            item.getEntityKey(),
                            item.getPayload(),
                            context.loginId()));
        }
        Long challengeId = null;
        if (decision == Decision.APPROVE
                && level != null
                && level.isOtpAtLevel()
                && action.get().getOtpAction() != null) {
            challengeId =
                    requireOtp(
                            context,
                            action.get().getOtpAction(),
                            item.getEntityName(),
                            item.getEntityKey(),
                            otp,
                            true);
        }
        Instant now = clock.instant();
        String role = task.getAssignedRoleCode() == null ? "CHECKER" : task.getAssignedRoleCode();
        decisions.save(
                ApprovalDecision.record(
                        item,
                        level,
                        context.loginId(),
                        role,
                        ApprovalDecision.Kind.valueOf(decision.name()),
                        reason,
                        challengeId,
                        now,
                        bulkReference));
        if (decision == Decision.APPROVE
                && !item.isAtFinalLevel()
                && path.size() > item.getCurrentLevel()) {
            return advance(context, item, task, path, action.get(), reason, challengeId, now);
        }
        ApprovalStatus outcome =
                switch (decision) {
                    case APPROVE -> ApprovalStatus.APPROVED;
                    case REJECT -> ApprovalStatus.REJECTED;
                    case RETURN -> ApprovalStatus.RETURNED;
                };
        item.decide(outcome, context.loginId(), reason, now);
        approvals.save(item);
        task.close(decision == Decision.RETURN);
        inbox.save(task);
        events.publishEvent(
                new ApprovalDecided(
                        item.getId(),
                        item.getModuleCode(),
                        item.getEntityName(),
                        item.getEntityKey(),
                        outcome,
                        // The Checker's own words for this decision; the item's decision_reason
                        // still holds the Maker's submission text on a RETURN.
                        reason,
                        item.getPayload(),
                        context.loginId(),
                        now,
                        challengeId));
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                item.getEntityName(),
                                item.getEntityKey(),
                                "DECISION",
                                "PENDING_APPROVAL",
                                outcome.name(),
                                reason == null ? "Approved" : reason)
                        .approval(item.getId())
                        .module(item.getModuleCode()));
        return item;
    }

    /** Level {@code n} approved: the item goes to level {@code n + 1}, still pending. */
    private ApprovalItem advance(
            AccessContext context,
            ApprovalItem item,
            WorkflowInboxItem task,
            List<WorkflowLevel> path,
            WorkflowAction action,
            String reason,
            Long challengeId,
            Instant now) {
        int from = item.getCurrentLevel();
        item.advance();
        approvals.save(item);
        WorkflowLevel next = path.get(item.getCurrentLevel() - 1);
        task.moveTo(
                next.getRoleCode(), officeOf(next, task.getOrgScopeId()), due(action, now), now);
        flagIfNoApprover(item, task, item.getSubmittedBy(), now);
        approvals.save(item);
        inbox.save(task);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                item.getEntityName(),
                                item.getEntityKey(),
                                "LEVEL",
                                String.valueOf(from),
                                String.valueOf(item.getCurrentLevel()),
                                reason == null || reason.isBlank()
                                        ? "Approved at level " + from
                                        : reason)
                        .approval(item.getId())
                        .module(item.getModuleCode()));
        events.publishEvent(
                new ApprovalAdvanced(
                        item.getId(),
                        item.getModuleCode(),
                        item.getEntityName(),
                        item.getEntityKey(),
                        from,
                        context.loginId(),
                        challengeId,
                        now));
        return item;
    }

    /**
     * SRS-G-31 "View order", SRS-13-18 "Show the order to the Payroll Checker at approval": the
     * documents of the item's record, to those who see the item: its submitter, or a session the
     * item waits for (the level's role, within the item's office scope). Anyone else: not found.
     */
    @Transactional(readOnly = true)
    public List<AttachmentPresence.AttachedFile> documents(AccessContext context, Long approvalId) {
        ApprovalItem item = visibleItem(context, approvalId);
        return attachments.files(item.getEntityName(), item.getEntityKey());
    }

    /** One document of the item's record (its access logged by the document store). */
    @Transactional
    public AttachmentPresence.FileContent document(
            AccessContext context, String sourceIp, Long approvalId, long attachmentId) {
        ApprovalItem item = visibleItem(context, approvalId);
        return attachments.content(
                context.loginId(),
                sourceIp,
                item.getEntityName(),
                item.getEntityKey(),
                attachmentId);
    }

    private ApprovalItem visibleItem(AccessContext context, Long approvalId) {
        ApprovalItem item =
                approvals
                        .findById(approvalId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (item.getSubmittedBy().equals(context.loginId())) {
            return item;
        }
        WorkflowInboxItem task =
                inbox.findByApprovalId(approvalId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        boolean role =
                task.getAssignedRoleCode() != null
                        && context.roleCodes().stream()
                                .anyMatch(r -> roles.actsAs(r, task.getAssignedRoleCode()));
        // Deny by default (security review 2026-11-04, W-1): a task without an office (a national
        // item routed to an office level) is in no office's scope, so it is Headquarters' only.
        boolean office =
                task.getOrgScopeId() == null
                        ? policy.isAtHeadquarters(context)
                        : context.dataScope().contains(task.getOrgScopeId());
        if (!role || !office) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return item;
    }

    /** Open items routed to the session's roles within its data scope. */
    @Transactional(readOnly = true)
    public Paged<ItemView> inbox(AccessContext context, PageSpec spec) {
        Set<String> held = context.roleCodes();
        if (held.isEmpty() || context.dataScope().isEmpty()) {
            return new Paged<>(List.of(), spec.page(), spec.size(), 0);
        }
        Page<WorkflowInboxItem> open =
                inbox.findOpen(
                        WorkflowInboxItem.Status.PENDING,
                        held,
                        context.dataScope(),
                        spec.pageable());
        Map<Long, ApprovalItem> items =
                byId(open.getContent().stream().map(WorkflowInboxItem::getApprovalId).toList());
        List<ItemView> views = new ArrayList<>();
        Instant now = clock.instant();
        for (WorkflowInboxItem task : open.getContent()) {
            ApprovalItem item = items.get(task.getApprovalId());
            if (item != null && item.isPending()) {
                views.add(view(item, task, denial(context, item, task), now));
            }
        }
        return new Paged<>(views, open.getNumber(), open.getSize(), open.getTotalElements());
    }

    /**
     * The caller's own submissions, newest first, one page: at Headquarters all of them; at any
     * other office only those of its data scope (an item's office is its inbox entry's), as every
     * screen follows the office chosen in the top bar. Items without an office are national and
     * always shown.
     */
    @Transactional(readOnly = true)
    public Paged<ItemView> submissions(AccessContext context, PageSpec spec) {
        Instant now = clock.instant();
        Collection<Long> scope = OfficeScope.ids(context);
        Page<ApprovalItem> mine =
                approvals.findMine(
                        context.loginId(),
                        policy.isAtHeadquarters(context),
                        scope,
                        spec.pageable());
        return Paged.of(mine)
                .map(
                        item ->
                                view(
                                        item,
                                        inbox.findByApprovalId(item.getId()).orElse(null),
                                        ApiErrorCode.MAKER_CHECKER,
                                        now));
    }

    /**
     * Why this session may not decide {@code item} (an error message key), or null. Order:
     * maker-checker, earlier level, subject of the change, level role, HQ-only, location scope.
     */
    ApiErrorCode denial(AccessContext context, ApprovalItem item, WorkflowInboxItem task) {
        if (!item.isPending()) {
            return ApiErrorCode.NOT_PENDING;
        }
        if (item.getSubmittedBy().equals(context.loginId())) {
            return ApiErrorCode.MAKER_CHECKER;
        }
        if (item.getCurrentLevel() > 2
                && decisions.findByApprovalIdOrderByDecidedAt(item.getId()).stream()
                        .anyMatch(d -> d.getDecidedBy().equals(context.loginId()))) {
            return ApiErrorCode.MAKER_CHECKER;
        }
        for (ApprovalSubjects owner : subjects) {
            Optional<Long> subject = owner.subjectUserId(item.getEntityName(), item.getId());
            if (subject.isPresent() && subject.get().equals(context.userId())) {
                return ApiErrorCode.SELF_APPROVAL;
            }
        }
        if (task.getAssignedRoleCode() != null
                && !context.roleCodes().contains(task.getAssignedRoleCode())) {
            return ApiErrorCode.ROLE_NOT_HELD;
        }
        boolean hqItem =
                org.headquarters().map(hq -> hq.getId().equals(task.getOrgScopeId())).orElse(false);
        if (hqItem && !policy.isAtHeadquarters(context)) {
            return ApiErrorCode.HQ_ONLY;
        }
        if (task.getOrgScopeId() == null && !policy.isAtHeadquarters(context)) {
            // W-1: AccessPolicy reads a null office as "no office to check"; an item without one
            // is decided at Headquarters only, never by a holder of the role at any office.
            return ApiErrorCode.OUT_OF_SCOPE;
        }
        String module = moduleOf(item);
        return policy.denialAt(context, module, decisionAction(item, module), task.getOrgScopeId());
    }

    private static String moduleOf(ApprovalItem item) {
        return item.getModuleCode() == null ? "X-02" : item.getModuleCode();
    }

    /**
     * The permission the deciders of {@code item} need on its screen: APPROVE, unless the screen's
     * action inventory names a decision of its own for the item's route, {@code <ROUTE
     * ACTION>_AUTHORISE} (route C02_UNDO -> C-02 UNDO_AUTHORISE; plan 05 D-11, owner 2026-10-25).
     * It keeps such a decision apart from the screen's other approvals: C-02 APPROVE verifies
     * payroll results, which the Administrator who authorises an undo must never do. Configuration,
     * not code: an inventory row and the role grants.
     */
    String decisionAction(ApprovalItem item, String module) {
        Long route = item.getWorkflowActionId();
        if (route == null) {
            return "APPROVE";
        }
        return decisionActions.computeIfAbsent(
                route,
                id -> {
                    String prefix = module.replace("-", "") + "_";
                    String action =
                            actions.findById(id).map(WorkflowAction::getActionCode).orElse("");
                    if (!action.startsWith(prefix)) {
                        return "APPROVE";
                    }
                    String own = action.substring(prefix.length()) + "_AUTHORISE";
                    return inventory.findByScreenCodeAndActionCode(module, own).isPresent()
                            ? own
                            : "APPROVE";
                });
    }

    /**
     * BR-APPR-007: if nobody but the maker holds the task's role at its office, the item and the
     * task are flagged to the HQ Administrator (visible in the inbox and the submissions); never
     * approved.
     */
    private void flagIfNoApprover(
            ApprovalItem item, WorkflowInboxItem task, String maker, Instant now) {
        boolean none =
                !approvers.hasApprover(task.getAssignedRoleCode(), task.getOrgScopeId(), maker);
        task.flagNoApprover(none);
        if (none) {
            item.flagNoApprover(now);
            approvals.save(item);
        }
    }

    /** The levels of an action, 1 (maker) to level_count; a malformed master is a server error. */
    private List<WorkflowLevel> levelsOf(WorkflowAction action) {
        List<WorkflowLevel> path = levels.findByWorkflowActionIdOrderByLevelNo(action.getId());
        if (path.size() != action.getLevelCount()
                || path.get(0).getName() != WorkflowLevel.Name.MAKER) {
            throw new IllegalStateException(
                    "approval_workflow_action "
                            + action.getActionCode()
                            + " has "
                            + path.size()
                            + " level(s), expected "
                            + action.getLevelCount());
        }
        return path;
    }

    /** The office a level is decided at, from the item's own location. */
    private Long officeOf(WorkflowLevel level, Long itemLocation) {
        return switch (level.getOfficeLevel()) {
            case HQ -> org.headquarters().map(OrgLocation::getId).orElse(itemLocation);
            case ZO -> ancestor(itemLocation, LocationType.ZO);
            case RO -> ancestor(itemLocation, LocationType.RO);
            case DO -> ancestor(itemLocation, LocationType.DO);
            case PAYING_UNIT, OWN_CYCLE_INSTANCE -> itemLocation;
        };
    }

    private Long ancestor(Long location, LocationType type) {
        return org.ancestorOrSelf(location, type).map(OrgLocation::getId).orElse(location);
    }

    /**
     * SRS-22-16: the item is due by the target turnaround of its workflow action, a business target
     * set on the workflow master (hours, target_tat_hours; D-10). An action without a target gives
     * no due time and never flags an item as late: no target is guessed.
     */
    private static Instant due(WorkflowAction action, Instant now) {
        return action.getTargetTatHours() == null
                ? null
                : now.plus(Duration.ofHours(action.getTargetTatHours()));
    }

    /**
     * SRS-22-16: an action whose route says "mandatory attachment" is submitted only with an active
     * attachment on the record; checked here for every module (each flow attaches the document to
     * the submitted record before it submits). SRS-20-27: the blocked submission is recorded in the
     * audit trail, in its own transaction, so the refusal is kept although the submission is not.
     */
    private void requireAttachment(
            AccessContext context, WorkflowAction action, Submission submission) {
        if (!action.isAttachmentRequired()
                || attachments.countActive(submission.entityName(), submission.entityKey()) > 0) {
            return;
        }
        audit.recordIndependently(
                context.loginId(),
                AuditService.Entry.change(
                                submission.entityName(),
                                submission.entityKey(),
                                "SUBMISSION_BLOCKED",
                                null,
                                "ATTACHMENT_MISSING",
                                "Submission blocked: the mandatory attachment of "
                                        + action.getActionCode()
                                        + " is missing")
                        .module(submission.moduleCode()));
        throw new FciException(ApiErrorCode.ATTACHMENT_REQUIRED);
    }

    private Long requireOtp(
            AccessContext context,
            OtpGatedAction action,
            String entityName,
            String entityKey,
            OtpProof otp,
            boolean boundToItem) {
        if (context.userId() == null) {
            throw new FciException(CommonErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (!secondFactor.required()) {
            return secondFactor.confirmWithoutCode(context.userId(), action, entityName, entityKey);
        }
        if (otp == null || otp.challengeId() == null || otp.code() == null) {
            Long challengeId = secondFactor.issue(context.userId(), action, entityName, entityKey);
            throw new FciException(
                    ApiErrorCode.OTP_REQUIRED, new Object[] {challengeId}, List.of(), null);
        }
        // A decision's OTP confirms that item only (security review 2026-10-31: a code asked on
        // item A is refused on item B). A submission's key may be assigned by the submitting
        // service on each attempt, so its code is bound to the user and action.
        return boundToItem
                ? secondFactor.verify(
                        context.userId(),
                        action,
                        otp.challengeId(),
                        otp.code(),
                        entityName,
                        entityKey)
                : secondFactor.verify(context.userId(), action, otp.challengeId(), otp.code());
    }

    private Map<Long, ApprovalItem> byId(List<Long> ids) {
        Map<Long, ApprovalItem> result = new HashMap<>();
        approvals.findAllById(ids).forEach(i -> result.put(i.getId(), i));
        return result;
    }

    private ItemView view(
            ApprovalItem item, WorkflowInboxItem task, ApiErrorCode denial, Instant now) {
        String label =
                task == null || task.getOrgScopeId() == null
                        ? null
                        : org.location(task.getOrgScopeId())
                                .map(OrgLocation::displayLabel)
                                .orElse(null);
        return new ItemView(
                item.getId(),
                item.getModuleCode(),
                item.getEntityName(),
                item.getEntityKey(),
                item.getActionType().name(),
                item.getStatus().name(),
                item.getSubmittedBy(),
                item.getSubmittedAt(),
                item.getDecisionReason(),
                task == null ? null : task.getAssignedRoleCode(),
                label,
                task == null ? null : task.getSlaDueAt(),
                task != null
                        && (task.isBreached()
                                || (task.getSlaDueAt() != null && now.isAfter(task.getSlaDueAt()))),
                item.getCurrentLevel(),
                item.getLevelCount(),
                task != null && task.isNoApprover(),
                denial == null,
                denial == null ? null : ProblemDetailFactory.messageKey(denial),
                item.getImpactValue(),
                Duration.between(item.getSubmittedAt(), now).toDays(),
                targetHours(item),
                denial == null && bulkEligible(item),
                item.getReturnReason(),
                item.getDecidedBy(),
                item.getDecidedAt());
    }

    /**
     * SRS-G-13, UC-07 A2 (US-GAP-21): the item's action allows bulk approval and its current level
     * asks for no OTP. An item that needs an OTP is approved on its own with its OTP: each item
     * keeps its identity check and its OTP, and no challenge is ever raised for a whole batch
     * (CLR-24).
     */
    public boolean bulkEligible(ApprovalItem item) {
        if (item.getWorkflowActionId() == null
                || item.getStatus() != ApprovalStatus.PENDING_APPROVAL) {
            return false;
        }
        Optional<WorkflowAction> action = actions.findById(item.getWorkflowActionId());
        if (action.isEmpty() || !action.get().isBulkApprovalAllowed()) {
            return false;
        }
        List<WorkflowLevel> path;
        try {
            path = levelsOf(action.get());
        } catch (IllegalStateException misconfigured) {
            return false; // a broken route is decided on its own, where the error is shown
        }
        WorkflowLevel level =
                path.size() >= item.getCurrentLevel() ? path.get(item.getCurrentLevel() - 1) : null;
        return level != null && !(level.isOtpAtLevel() && action.get().getOtpAction() != null);
    }

    /** The target turnaround of the item's workflow action, in hours; null if none is set. */
    private Integer targetHours(ApprovalItem item) {
        return item.getWorkflowActionId() == null
                ? null
                : actions.findById(item.getWorkflowActionId())
                        .map(WorkflowAction::getTargetTatHours)
                        .orElse(null);
    }
}
