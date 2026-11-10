package in.gov.fci.payroll.payroll.batch.computation;

import in.gov.fci.payroll.payroll.api.MemberComputationBlocked;
import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import in.gov.fci.payroll.payroll.business.PayDataException;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.context.ApplicationEventPublisher;

/**
 * UC-01 alternate flow (SRS-G-9, gate G2): a member the Actual run skips is flagged on the X-05
 * worklist, besides the batch failure log. One listener per partition (step scope): it knows its
 * run. A General (simulation) run raises nothing: its results are never paid.
 */
class BlockedMemberListener
        implements SkipListener<MemberReader.MemberRow, ComputationProcessor.Computed> {

    private final CycleRuns.OpenRun run;
    private final ApplicationEventPublisher events;

    BlockedMemberListener(CycleRuns.OpenRun run, ApplicationEventPublisher events) {
        this.run = run;
        this.events = events;
    }

    @Override
    public void onSkipInProcess(MemberReader.MemberRow item, Throwable t) {
        if (run.runType() != CycleRuns.RunType.ACTUAL || item == null) {
            return;
        }
        PayDataException.Where where =
                t instanceof MemberBlockedException blocked ? blocked.where() : null;
        events.publishEvent(
                new MemberComputationBlocked(
                        run.cycleId(),
                        item.employeeId(),
                        t.getMessage(),
                        where == null ? null : where.entityName(),
                        where == null ? null : where.fieldName(),
                        where == null ? null : where.fieldSource(),
                        where == null ? null : where.screen()));
    }
}
