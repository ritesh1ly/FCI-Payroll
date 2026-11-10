package in.gov.fci.payroll.workflow.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.SecureTokens;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The governed buttons of the wireframe screens ({@code screen_action_inventory}). Each press is
 * authorised server side with the same {@code can()} as everything else; an action with an approval
 * workflow action in force ({@code approval_workflow_action}, {@code <screen>_<action>}) becomes a
 * maker-checker submission, any other permitted action is recorded in the audit trail. Approvals
 * are decided in the inbox, never here.
 */
@Service
@Loggable
public class ActionService {

    public enum Outcome {
        RECORDED,
        SUBMITTED
    }

    public record ActionRequest(
            String screenCode,
            String actionCode,
            Long locationId,
            String entityKey,
            String reason,
            OtpProof otp) {}

    public record ActionResult(Outcome outcome, Long approvalId) {}

    private static final Set<String> DECIDED_IN_INBOX = Set.of("APPROVE");

    private final WorkflowRepositories.ScreenActions inventory;
    private final Authorization policy;
    private final ApprovalService approvals;
    private final AuditService audit;

    public ActionService(
            WorkflowRepositories.ScreenActions inventory,
            Authorization policy,
            ApprovalService approvals,
            AuditService audit) {
        this.inventory = inventory;
        this.policy = policy;
        this.approvals = approvals;
        this.audit = audit;
    }

    @Transactional
    public ActionResult perform(AccessContext context, ActionRequest request) {
        if (inventory
                        .findByScreenCodeAndActionCode(request.screenCode(), request.actionCode())
                        .isEmpty()
                || DECIDED_IN_INBOX.contains(request.actionCode())
                // A route's own decision permission (ApprovalService#decisionAction) is decided
                // in the inbox too, never performed as an action.
                || request.actionCode().endsWith("_AUTHORISE")) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        Long location = request.locationId() == null ? context.locationId() : request.locationId();
        boolean governed =
                approvals
                        .actionInForce(
                                Submission.workflowActionOf(
                                        request.screenCode(), request.actionCode()))
                        .isPresent();
        if (!governed) {
            policy.requireAt(context, request.screenCode(), request.actionCode(), location);
            audit.record(
                    context.loginId(),
                    AuditService.Entry.change(
                                    "screen_action",
                                    request.screenCode() + ":" + request.actionCode(),
                                    request.actionCode(),
                                    null,
                                    "RECORDED",
                                    request.reason() == null
                                            ? "Action performed"
                                            : request.reason())
                            .module(request.screenCode()));
            return new ActionResult(Outcome.RECORDED, null);
        }
        boolean lock = "LOCK".equals(request.actionCode());
        String key =
                request.entityKey() == null || request.entityKey().isBlank()
                        ? request.screenCode() + "-" + SecureTokens.randomToken(16)
                        : request.entityKey();
        ApprovalItem item =
                approvals.submitItem(
                        context,
                        new Submission(
                                request.screenCode(),
                                request.actionCode(),
                                lock
                                        ? ApprovalService.LOCK_ENTITY
                                        : "screen:" + request.screenCode(),
                                key,
                                actionType(request.actionCode()),
                                location,
                                "{}",
                                null,
                                request.reason()),
                        request.otp());
        return new ActionResult(Outcome.SUBMITTED, item.getId());
    }

    private static ActionType actionType(String action) {
        return switch (action) {
            case "CREATE" -> ActionType.CREATE;
            case "DELETE" -> ActionType.DEACTIVATE;
            default -> ActionType.UPDATE;
        };
    }
}
