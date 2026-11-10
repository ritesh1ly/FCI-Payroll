package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import java.time.Instant;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SRS-15-28 / BR-CYC-015: the approved force-release of a cycle lease (route {@code
 * C02_LEASE_RELEASE}, plan 05 D-6), in the decision's transaction. The Checker decided with the OTP
 * {@code CYCLE_UNFREEZE_STUCK_RELEASE} (SRS-G-21; the approval engine asks it at the checker
 * level). Only the lease named in the request is released (same holder and acquisition): a lease
 * taken since the request is left alone, and a lease already gone needs nothing. The log row
 * carries the decider, the requester's reason, the approval and the OTP challenge (DDL {@code
 * chk_lease_force}).
 */
@Component
public class CycleLeaseReleaseEffect {

    private final CycleLeases leases;
    private final AuditService audit;
    private final ObjectMapper json;

    public CycleLeaseReleaseEffect(CycleLeases leases, AuditService audit, ObjectMapper json) {
        this.leases = leases;
        this.audit = audit;
        this.json = json;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.approved() || !decision.concerns(CycleService.LEASE_ENTITY)) {
            return;
        }
        JsonNode lease = json.readTree(decision.payload());
        long cycleId = lease.path("cycleInstanceId").asLong();
        String heldBy = lease.path("heldBy").asString();
        Instant acquiredAt = Instant.parse(lease.path("acquiredAt").asString());
        // The requester's reason, carried in the request (the Checker's may be empty on approval).
        String reason = lease.path("reason").asString();
        if (reason == null || reason.isBlank()) {
            reason = "Force release";
        }
        String checker =
                decision.checkerLoginId().length() <= 50
                        ? decision.checkerLoginId()
                        : decision.checkerLoginId().substring(0, 50);
        // The engine asked the OTP at the deciding level; without one (a misconfigured route) the
        // DDL check would refuse the log row, so the decision fails with a clear message instead.
        if (decision.otpChallengeId() == null) {
            throw new IllegalStateException(
                    "Route C02_LEASE_RELEASE must ask the OTP at the deciding level (X-01)");
        }
        boolean released =
                leases.forceRelease(
                        cycleId,
                        heldBy,
                        acquiredAt,
                        checker,
                        reason,
                        decision.approvalId(),
                        decision.otpChallengeId());
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                CycleService.LEASE_ENTITY,
                                String.valueOf(cycleId),
                                "lease",
                                lease.path("action").asString() + " by " + heldBy,
                                released ? "FORCE_RELEASED" : "ALREADY_RELEASED",
                                reason)
                        .approval(decision.approvalId())
                        .module("C-02"));
    }
}
