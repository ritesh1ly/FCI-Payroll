package in.gov.fci.payroll.jobs.orchestration;

import in.gov.fci.payroll.jobs.business.model.BatchJobControl;
import in.gov.fci.payroll.jobs.business.model.ControlCommand;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Plan 05j step j2: the checker's decision on an abandon request ({@link
 * JobRequestService#requestAbandon}). Approved, an ABANDON command is recorded for the batch node,
 * which ends the job for good (request, Spring Batch execution and computation run ABANDONED); a
 * rejection or return leaves the job as it was and says so in its journal. In the decision's
 * transaction (R-MC).
 */
@Component
public class JobAbandonEffect {

    private final JobRepositories.JobRequests requests;
    private final JobRepositories.Controls controls;
    private final JobJournal journal;

    public JobAbandonEffect(
            JobRepositories.JobRequests requests,
            JobRepositories.Controls controls,
            JobJournal journal) {
        this.requests = requests;
        this.controls = controls;
        this.journal = journal;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(JobRequestService.ABANDON_ENTITY)) {
            return;
        }
        requests.findById(Long.valueOf(decision.entityKey()))
                .ifPresent(
                        request -> {
                            String detail =
                                    "Approval "
                                            + decision.approvalId()
                                            + (decision.reason() == null
                                                    ? ""
                                                    : ": " + decision.reason());
                            if (decision.approved()) {
                                controls.save(
                                        BatchJobControl.request(
                                                request.getId(),
                                                ControlCommand.ABANDON,
                                                null,
                                                decision.checkerLoginId(),
                                                truncate(detail),
                                                decision.decidedAt()));
                                journal.record(
                                        request.getId(),
                                        JobJournal.Event.CONTROL_REQUESTED,
                                        decision.checkerLoginId(),
                                        request.getStatus(),
                                        null,
                                        request.getJobExecutionId(),
                                        truncate("ABANDON approved: " + detail));
                            } else {
                                journal.record(
                                        request.getId(),
                                        JobJournal.Event.ABANDON_REFUSED,
                                        decision.checkerLoginId(),
                                        request.getStatus(),
                                        null,
                                        request.getJobExecutionId(),
                                        truncate(decision.outcome().name() + ": " + detail));
                            }
                        });
    }

    /** batch_job_control.reason and batch_job_audit.detail hold 500 characters. */
    private static String truncate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
