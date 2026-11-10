package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserAccessGroup;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * SRS-G-36 / FRS V0.5 §6.10.6, applied in the transaction of the employee change that causes it: a
 * separation or dismissal deactivates the holder's accounts in the same act (with the I-17 event as
 * {@code deactivation_event_id}); a transfer withdraws every access right held at the old unit
 * ({@code revoke_cause} TRANSFER), to be assigned afresh at the new unit. Open sessions end and the
 * cached access contexts are evicted, so nothing granted before survives the next request.
 */
@Component
public class EmployeeAccessEffects {

    private final AccessRepositories.Users users;
    private final AccessRepositories.Assignments assignments;
    private final SessionService sessions;
    private final AccessContextService contexts;
    private final AuditService audit;
    private final Clock clock;

    public EmployeeAccessEffects(
            AccessRepositories.Users users,
            AccessRepositories.Assignments assignments,
            SessionService sessions,
            AccessContextService contexts,
            AuditService audit,
            Clock clock) {
        this.users = users;
        this.assignments = assignments;
        this.sessions = sessions;
        this.contexts = contexts;
        this.audit = audit;
        this.clock = clock;
    }

    @EventListener
    public void on(EmployeeEvents.EmployeeSeparated event) {
        Instant now = clock.instant();
        for (AppUser user : users.findByEmployeeId(event.employeeId())) {
            if (user.getStatus() == AppUser.Status.DEACTIVATED) {
                continue;
            }
            revokeAll(
                    user,
                    AppUserAccessGroup.RevokeCause.SEPARATION,
                    event.eventType() + ": " + event.reason(),
                    now);
            user.deactivateForSeparation(event.eventId(), now);
            users.save(user);
            sessions.endAllSessions(user.getId(), now, AppSession.EndReason.REVOCATION);
            audit.record(
                    event.decidedBy(),
                    AuditService.Entry.change(
                                    "app_user",
                                    String.valueOf(user.getId()),
                                    "user_status",
                                    "ACTIVE",
                                    "DEACTIVATED",
                                    event.eventType()
                                            + " (life-cycle event "
                                            + event.eventId()
                                            + ", from HRMS)")
                            // SRS-1-63 (plan 16 D16-33): a separation or dismissal arrives
                            // through I-21 only.
                            .module("I-21"));
        }
        contexts.evictAll();
    }

    @EventListener
    public void on(EmployeeEvents.EmployeeTransferred event) {
        Instant now = clock.instant();
        for (AppUser user : users.findByEmployeeId(event.employeeId())) {
            int revoked =
                    revokeAll(
                            user,
                            AppUserAccessGroup.RevokeCause.TRANSFER,
                            "Transfer "
                                    + (event.orderReference() == null ? "" : event.orderReference())
                                    + " from "
                                    + event.transferDate(),
                            now);
            if (revoked > 0) {
                sessions.endAllSessions(user.getId(), now, AppSession.EndReason.REVOCATION);
                audit.record(
                        event.decidedBy(),
                        AuditService.Entry.change(
                                        "app_user",
                                        String.valueOf(user.getId()),
                                        "access_groups",
                                        revoked + " active",
                                        "0 (TRANSFER)",
                                        "Rights withdrawn on transfer (M-11 transfer "
                                                + event.transferId()
                                                + ")")
                                .module("M-11"));
            }
        }
        contexts.evictAll();
    }

    private int revokeAll(
            AppUser user, AppUserAccessGroup.RevokeCause cause, String reason, Instant now) {
        int count = 0;
        for (AppUserAccessGroup assignment : assignments.findByUserId(user.getId())) {
            if (assignment.getRevokedAt() == null) {
                assignment.revoke(
                        cause, reason.length() <= 500 ? reason : reason.substring(0, 500), now);
                assignments.save(assignment);
                count++;
            }
        }
        return count;
    }
}
