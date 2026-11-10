package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUserAccessGroup;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.workflow.api.ApprovalSubjects;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The checker's decisions on access changes (A-02 grants, A-03 groups, A-04 assignments). A
 * rejected or returned assignment is withdrawn, so it can never be approved later by accident; an
 * approved one ends the user's open sessions (end reason ROLE_CHANGE), so the new access applies
 * from the next sign-in (SRS-22-23 and its use case, SRS v0.5 line 1103: "applies from the user's
 * next login"; SRS-SEC-14; Module 02 decision D-5). Every decision refreshes the cached access
 * contexts. Also tells the workflow who an assignment is about (no one approves their own access).
 */
@Component
public class AccessApprovalEffects implements ApprovalSubjects {

    static final String ASSIGNMENT = "app_user_access_group";

    private final AccessRepositories.Assignments assignments;
    private final AccessContextService contexts;
    private final SessionService sessions;

    public AccessApprovalEffects(
            AccessRepositories.Assignments assignments,
            AccessContextService contexts,
            SessionService sessions) {
        this.assignments = assignments;
        this.contexts = contexts;
        this.sessions = sessions;
    }

    @Override
    public Optional<Long> subjectUserId(String entityName, Long approvalId) {
        return ASSIGNMENT.equals(entityName)
                ? assignments.findByApprovalId(approvalId).map(AppUserAccessGroup::getUserId)
                : Optional.empty();
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        switch (decision.entityName()) {
            case ASSIGNMENT -> {
                if (decision.approved()) {
                    assignments
                            .findByApprovalId(decision.approvalId())
                            .ifPresent(
                                    a ->
                                            sessions.endAllSessions(
                                                    a.getUserId(),
                                                    decision.decidedAt(),
                                                    AppSession.EndReason.ROLE_CHANGE));
                } else {
                    assignments
                            .findByApprovalId(decision.approvalId())
                            .filter(a -> a.getRevokedAt() == null)
                            .ifPresent(
                                    a -> {
                                        a.revoke(
                                                AppUserAccessGroup.RevokeCause.ADMIN,
                                                withdrawal(decision),
                                                decision.decidedAt());
                                        assignments.save(a);
                                    });
                }
                contexts.evictAll();
            }
            case "access_group", "app_role_permission" -> contexts.evictAll();
            default -> {
                // not an access entity
            }
        }
    }

    private static String withdrawal(ApprovalDecided decision) {
        return "Approval " + decision.outcome().name().toLowerCase(Locale.ROOT);
    }
}
