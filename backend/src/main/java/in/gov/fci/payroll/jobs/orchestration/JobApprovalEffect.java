package in.gov.fci.payroll.jobs.orchestration;

import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * C-13 maker-checker: approval puts the job in the queue (PENDING, claimable by a batch node); a
 * rejection or return ends it (REJECTED). Batch nodes never see a job before this.
 */
@Component
public class JobApprovalEffect {

    static final String ENTITY = "batch_job_request";

    private final JobRepositories.JobRequests jobRequests;
    private final JobJournal journal;

    public JobApprovalEffect(JobRepositories.JobRequests jobRequests, JobJournal journal) {
        this.jobRequests = jobRequests;
        this.journal = journal;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY)) {
            return;
        }
        jobRequests
                .findById(Long.valueOf(decision.entityKey()))
                .filter(request -> request.getStatus() == JobStatus.AWAITING_APPROVAL)
                .ifPresent(
                        request -> {
                            if (decision.approved()) {
                                request.approved();
                                journal.record(
                                        request.getId(),
                                        JobJournal.Event.APPROVED,
                                        decision.checkerLoginId(),
                                        JobStatus.AWAITING_APPROVAL,
                                        JobStatus.PENDING,
                                        null,
                                        "Approval " + decision.approvalId());
                            } else {
                                String why =
                                        decision.reason() == null
                                                ? decision.outcome().name()
                                                : decision.outcome().name()
                                                        + ": "
                                                        + decision.reason();
                                request.rejected(why, decision.decidedAt());
                                journal.record(
                                        request.getId(),
                                        JobJournal.Event.REJECTED,
                                        decision.checkerLoginId(),
                                        JobStatus.AWAITING_APPROVAL,
                                        JobStatus.REJECTED,
                                        null,
                                        why);
                            }
                            jobRequests.save(request);
                        });
    }
}
