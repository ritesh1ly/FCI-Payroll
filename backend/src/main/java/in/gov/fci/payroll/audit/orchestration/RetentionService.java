package in.gov.fci.payroll.audit.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.FieldChanges;
import in.gov.fci.payroll.audit.business.model.RetentionClass;
import in.gov.fci.payroll.audit.persistence.AuditRepositories;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.InputPatterns;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * SRS-20-5: the retention periods of the native audit trail and the Legacy Audit table are settings
 * the Administrator maintains (A-07), from {@code retention_class}. A class nobody has set shows
 * "not decided" ({@code is_confirmed = N}, OQ-10): no period is guessed, and nothing is ever purged
 * by the application (R-DEL). A change is proposed at Headquarters and takes effect only when a
 * checker approves it (R-MC, route A07_EDIT); the applied change writes one audit row per changed
 * field in the same transaction (SRS-20-2, SRS-20-18).
 */
@Service
@Loggable
public class RetentionService {

    static final String ENTITY = "retention_class";
    static final String SCREEN = "A-07";

    /** The largest period {@code retention_months NUMBER(5)} holds (a column limit, not a rule). */
    static final int MAX_MONTHS = 99_999;

    public record RetentionView(
            String code,
            String name,
            Integer months,
            boolean indefinite,
            boolean confirmed,
            String basis,
            String archivalAction,
            Long pendingApprovalId) {}

    public record ChangeRequest(
            String code, Integer months, boolean indefinite, String basis, String reason) {}

    /** The proposed period travels in the approval item until approved. */
    public record Proposal(Integer months, boolean indefinite, String basis) {}

    private final AuditRepositories.RetentionClasses classes;
    private final MakerChecker approvals;
    private final ApprovalStatuses statuses;
    private final Authorization policy;
    private final AuditService audit;
    private final ObjectMapper json;

    public RetentionService(
            AuditRepositories.RetentionClasses classes,
            MakerChecker approvals,
            ApprovalStatuses statuses,
            Authorization policy,
            AuditService audit,
            ObjectMapper json) {
        this.classes = classes;
        this.approvals = approvals;
        this.statuses = statuses;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<RetentionView> list(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        Map<String, Long> pending = pending();
        return classes.findAllByOrderByCodeAsc().stream()
                .map(c -> view(c, pending.get(c.getCode())))
                .toList();
    }

    /** SRS-20-5: proposes a period; in force only after a Headquarters checker approves it. */
    @Transactional
    public RetentionView propose(AccessContext context, ChangeRequest request, OtpProof otp) {
        policy.require(context, SCREEN, "EDIT");
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(ApiErrorCode.ADMIN_HQ_ONLY);
        }
        if (request == null || request.code() == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        RetentionClass target =
                classes.findByCode(request.code())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        Proposal proposal = validate(request);
        if (pending().containsKey(target.getCode())) {
            throw new FciException(ApiErrorCode.RETENTION_PENDING);
        }
        String payload = json.writeValueAsString(proposal);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                "EDIT",
                                ENTITY,
                                target.getCode(),
                                ActionType.UPDATE,
                                context.locationId(),
                                payload,
                                null,
                                request.reason()),
                        otp);
        return view(target, item.id());
    }

    /**
     * SRS-20-5: a period is a number of months (1 … column limit) or indefinite - not both, not
     * neither - and names the instrument that sets it (printable ASCII, R-ASCII).
     */
    static Proposal validate(ChangeRequest request) {
        boolean hasMonths = request.months() != null;
        String basis = request.basis() == null ? "" : request.basis().strip();
        if (hasMonths == request.indefinite()
                || (hasMonths && (request.months() < 1 || request.months() > MAX_MONTHS))
                || basis.isEmpty()
                || basis.length() > 200
                || !InputPatterns.isPrintableAscii(basis)
                || request.reason() == null
                || request.reason().isBlank()) {
            throw new FciException(
                    ApiErrorCode.RETENTION_INVALID, new Object[] {MAX_MONTHS}, List.of(), null);
        }
        return new Proposal(request.months(), request.indefinite(), basis);
    }

    /**
     * The checker's final decision: an approved period is applied with its audit rows in the same
     * transaction; any other outcome changes nothing.
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY) || decision.outcome() != ApprovalStatus.APPROVED) {
            return;
        }
        RetentionClass target =
                classes.findByCode(decision.entityKey())
                        .orElseThrow(() -> new IllegalStateException("retention class gone"));
        Proposal proposal;
        try {
            proposal = json.readValue(decision.payload(), Proposal.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("unreadable retention proposal", ex);
        }
        Map<String, String> before = target.image();
        target.setPeriod(proposal.months(), proposal.indefinite(), proposal.basis());
        classes.save(target);
        audit.recordAll(
                decision.checkerLoginId(),
                FieldChanges.between(
                                ENTITY,
                                target.getCode(),
                                before,
                                target.image(),
                                decision.reason() == null ? "Approved" : decision.reason())
                        .stream()
                        .map(e -> e.approval(decision.approvalId()).module(SCREEN))
                        .toList());
    }

    private Map<String, Long> pending() {
        return statuses.pending(ENTITY).stream()
                .collect(
                        Collectors.toMap(
                                ApprovalStatuses.Pending::entityKey,
                                ApprovalStatuses.Pending::approvalId,
                                (newest, older) -> newest));
    }

    private static RetentionView view(RetentionClass c, Long pendingApprovalId) {
        return new RetentionView(
                c.getCode(),
                c.getName(),
                c.getMonths(),
                c.isIndefinite(),
                c.isConfirmed(),
                c.getBasis(),
                c.getArchivalAction(),
                pendingApprovalId);
    }
}
