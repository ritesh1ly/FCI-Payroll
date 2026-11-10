package in.gov.fci.payroll.jobs.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.jobs.business.model.BatchHalt;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan 05j step j4 (owner decision D-5): the batch kill switch on C-13. A System Administrator at
 * Headquarters (C-13 HALT) halts all batch processing at once, with a reason: an emergency stop is
 * not delayed by an approval. Batch nodes then launch nothing, apply no Resume / Restart and stop
 * every running job at its next chunk boundary (committed work kept). The release is maker-checker
 * (C13_RELEASE: the System Administrator asks, a Payroll Checker at HQ decides) and restarts
 * nothing by itself: each stopped job is restarted deliberately, under the resume rule.
 */
@Service
@Loggable
public class BatchHaltService {

    private static final Logger log = LoggerFactory.getLogger(BatchHaltService.class);

    static final String SCREEN = "C-13";
    static final String HALT = "HALT";

    /** The approval item of a release: key = the halt id. */
    public static final String ENTITY = "batch_halt";

    /** The halt as C-13 shows it; {@code engaged} false and the rest null when not halted. */
    public record HaltView(
            boolean engaged,
            Long haltId,
            String haltedBy,
            Instant haltedAt,
            String reason,
            Long releaseApprovalId,
            String releaseRequestedBy,
            String releaseReason) {

        static HaltView none() {
            return new HaltView(false, null, null, null, null, null, null, null);
        }

        static HaltView of(BatchHalt h) {
            return new HaltView(
                    true,
                    h.getId(),
                    h.getHaltedBy(),
                    h.getHaltedAt(),
                    h.getHaltReason(),
                    h.getReleaseApprovalId(),
                    h.getReleaseRequestedBy(),
                    h.getReleaseReason());
        }
    }

    private final JobRepositories.Halts halts;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final AuditService audit;
    private final Clock clock;

    public BatchHaltService(
            JobRepositories.Halts halts,
            Authorization policy,
            MakerChecker approvals,
            AuditService audit,
            Clock clock) {
        this.halts = halts;
        this.policy = policy;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
    }

    /** Whether batch processing is halted, and since when, by whom, why (every C-13 viewer). */
    @Transactional(readOnly = true)
    public HaltView state(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        return halts.findByReleasedAtIsNullOrderByIdDesc().stream()
                .findFirst()
                .map(HaltView::of)
                .orElseGet(HaltView::none);
    }

    /** Halts all batch processing now (C-13 HALT, at Headquarters, with a reason). */
    @Transactional
    public HaltView halt(AccessContext context, String reason) {
        requireHalter(context, reason);
        if (halts.existsByReleasedAtIsNull()) {
            throw new FciException(ApiErrorCode.JOB_ALREADY_HALTED);
        }
        BatchHalt halt =
                halts.save(BatchHalt.engage(context.loginId(), reason.strip(), clock.instant()));
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(halt.getId()),
                                "halt",
                                null,
                                "ENGAGED",
                                reason.strip())
                        .module(SCREEN));
        log.warn("event=BATCH_HALT_ENGAGED haltId={} by={}", halt.getId(), context.loginId());
        return HaltView.of(halt);
    }

    /** Asks for the halt to be released: in force only once a Payroll Checker at HQ approves. */
    @Transactional
    public HaltView requestRelease(AccessContext context, String reason) {
        requireHalter(context, reason);
        BatchHalt halt =
                halts.findByReleasedAtIsNullOrderByIdDesc().stream()
                        .findFirst()
                        .orElseThrow(() -> new FciException(ApiErrorCode.JOB_NOT_HALTED));
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                "C13_RELEASE",
                                SCREEN,
                                HALT,
                                ENTITY,
                                String.valueOf(halt.getId()),
                                ActionType.UPDATE,
                                context.locationId(),
                                "{\"haltId\":" + halt.getId() + "}",
                                null,
                                reason.strip()),
                        null);
        halt.releaseRequested(item.id(), context.loginId(), reason.strip());
        halt = halts.save(halt);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(halt.getId()),
                                "release",
                                null,
                                "REQUESTED",
                                reason.strip())
                        .approval(item.id())
                        .module(SCREEN));
        return HaltView.of(halt);
    }

    /** The checker's decision on a release, in the decision's transaction (R-MC). */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY)) {
            return;
        }
        halts.findById(Long.valueOf(decision.entityKey()))
                .filter(BatchHalt::isEngaged)
                .ifPresent(
                        halt -> {
                            if (decision.approved()) {
                                halt.released(decision.checkerLoginId(), decision.decidedAt());
                                log.warn(
                                        "event=BATCH_HALT_RELEASED haltId={} by={}",
                                        halt.getId(),
                                        decision.checkerLoginId());
                            } else {
                                halt.releaseRefused();
                            }
                            halts.save(halt);
                        });
    }

    private void requireHalter(AccessContext context, String reason) {
        policy.require(context, SCREEN, HALT);
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(ApiErrorCode.HQ_ONLY);
        }
        if (reason == null || reason.isBlank()) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
    }
}
