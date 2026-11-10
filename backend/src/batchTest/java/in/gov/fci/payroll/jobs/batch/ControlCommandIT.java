package in.gov.fci.payroll.jobs.batch;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.jobs.batch.control.ControlCommandProcessor;
import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.recovery.AbandonSweep;
import in.gov.fci.payroll.jobs.business.model.BatchJobControl;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.ControlCommand;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * C-13 Stop / Pause / Resume / Restart as the batch node applies them (BR-CYC-011..017; ADR job
 * queue): each command is re-checked against the request's state when applied, recorded APPLIED or
 * REJECTED with the node and reason, and journalled; Resume / Restart only within the restart
 * window and from the last committed chunk.
 */
class ControlCommandIT extends BatchTestSupport {

    @Autowired private ControlCommandProcessor processor;
    @Autowired private JobRepositories.Controls controls;
    @Autowired private JobLaunchService launcher;
    @Autowired private AbandonSweep sweep;

    @Autowired private in.gov.fci.payroll.jobs.config.BatchProperties batchProperties;

    @AfterEach
    void releaseHalt() {
        // A halt this test engaged never outlives it: the database is shared with the other tests.
        jdbc.update("DELETE FROM batch_halt WHERE halted_by = 'it.admin'");
    }

    @Test
    void OD_02_whileHaltedNothingIsResumedRestartedOrLaunchedButAStopStillApplies() {
        jdbc.update(
                "INSERT INTO batch_halt (halted_by, halted_at, halt_reason) VALUES ('it.admin', ?,"
                        + " 'IT: wrong rates activated')",
                Timestamp.from(clock.instant()));
        BatchJobRequest failed =
                request(JobStatus.FAILED, clock.instant().minus(Duration.ofHours(1)));
        BatchJobControl restart = command(failed, ControlCommand.RESTART);
        BatchJobRequest waiting = request(JobStatus.PENDING, null);
        BatchJobControl stop = command(waiting, ControlCommand.STOP);

        processor.processPending();

        assertThat(reload(restart).getStatus()).isEqualTo(BatchJobControl.Status.REJECTED);
        assertThat(reload(restart).getOutcomeMessage())
                .isEqualTo("Batch processing is halted: release the halt first");
        assertThat(reload(failed).getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reload(stop).getStatus())
                .as("a stop starts nothing")
                .isEqualTo(BatchJobControl.Status.APPLIED);
        assertThat(reload(waiting).getStatus()).isEqualTo(JobStatus.STOPPED);

        BatchJobRequest picked = request(JobStatus.PICKED, null);
        assertThat(launcher.launch(picked, JobStatus.PENDING)).isFalse();
        assertThat(reload(picked).getStatus()).as("back in the queue").isEqualTo(JobStatus.PENDING);
        assertThat(journal(picked)).contains("LOCK_REFUSED");
    }

    @Test
    void SRS_15_27_aJobLeftFailedBeyondTheRestartWindowIsAbandonedByTheSystem() {
        BatchJobRequest old =
                request(
                        JobStatus.FAILED,
                        clock.instant().minus(batchProperties.restartWindow().plusHours(8)));
        BatchJobRequest recent =
                request(JobStatus.FAILED, clock.instant().minus(Duration.ofHours(1)));

        assertThat(sweep.sweep()).isGreaterThanOrEqualTo(1);

        assertThat(reload(old).getStatus()).isEqualTo(JobStatus.ABANDONED);
        assertThat(reload(recent).getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(journal(old)).contains("ABANDONED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT actor FROM batch_job_audit WHERE job_request_id = ? AND"
                                        + " event_type = 'ABANDONED'",
                                String.class,
                                old.getId()))
                .isEqualTo("system.housekeeping");
    }

    private Long unit() {
        return jdbc.queryForObject(
                "SELECT location_id FROM org_location WHERE location_code = 'DH001'", Long.class);
    }

    /** A request of this test in the given state (as the queue would have left it). */
    private BatchJobRequest request(JobStatus status, Instant finishedAt) {
        BatchJobRequest r =
                pickedRequest(
                        JobType.PAYROLL_DRY_RUN,
                        Map.of(
                                "payPeriod",
                                "2026-07",
                                "locationId",
                                String.valueOf(unit()),
                                "reason",
                                "control-it-" + System.nanoTime()),
                        unit());
        switch (status) {
            case PICKED -> {
                // as picked
            }
            case RUNNING -> r.started(987_654_321L, clock.instant());
            default ->
                    r.finished(
                            status,
                            0,
                            0,
                            0,
                            null,
                            null,
                            finishedAt == null ? clock.instant() : finishedAt);
        }
        return requests.save(r);
    }

    private BatchJobControl command(BatchJobRequest r, ControlCommand c) {
        return controls.save(
                BatchJobControl.request(
                        r.getId(), c, null, "test.maker", "IT " + c, clock.instant()));
    }

    private BatchJobControl reload(BatchJobControl c) {
        return controls.findById(c.getId()).orElseThrow();
    }

    private BatchJobRequest reload(BatchJobRequest r) {
        return requests.findById(r.getId()).orElseThrow();
    }

    private List<String> journal(BatchJobRequest r) {
        return jdbc.queryForList(
                "SELECT event_type FROM batch_job_audit WHERE job_request_id = ? ORDER BY event_at,"
                        + " audit_id",
                String.class,
                r.getId());
    }

    @Test
    void C_13_stoppingAWaitingRequestCancelsItAtOnce() {
        BatchJobRequest r = request(JobStatus.PENDING, null);
        BatchJobControl c = command(r, ControlCommand.STOP);
        processor.processPending();
        assertThat(reload(r).getStatus()).isEqualTo(JobStatus.STOPPED);
        assertThat(reload(r).getFinishedAt()).isNotNull();
        BatchJobControl applied = reload(c);
        assertThat(applied.getStatus()).isEqualTo(BatchJobControl.Status.APPLIED);
        assertThat(applied.getOutcomeMessage()).isEqualTo("Cancelled before start");
        assertThat(applied.getAppliedBy()).isNotBlank();
        assertThat(journal(r)).contains("CONTROL_APPLIED", "CANCELLED");
    }

    @Test
    void C_13_stoppingAPausedRequestEndsItForGood() {
        BatchJobRequest r = request(JobStatus.PAUSED, null);
        BatchJobControl c = command(r, ControlCommand.STOP);
        processor.processPending();
        assertThat(reload(r).getStatus()).isEqualTo(JobStatus.STOPPED);
        assertThat(reload(c).getOutcomeMessage()).isEqualTo("Stopped while paused");
    }

    @Test
    void C_13_aCommandNotValidForTheStatusIsRejectedWithTheReason() {
        BatchJobRequest pending = request(JobStatus.PENDING, null);
        BatchJobControl pause = command(pending, ControlCommand.PAUSE);
        BatchJobRequest done = request(JobStatus.COMPLETED, null);
        BatchJobControl restart = command(done, ControlCommand.RESTART);
        processor.processPending();
        assertThat(reload(pause).getStatus()).isEqualTo(BatchJobControl.Status.REJECTED);
        assertThat(reload(pause).getOutcomeMessage())
                .isEqualTo("PAUSE is not possible while the job is PENDING");
        assertThat(reload(pending).getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(reload(restart).getOutcomeMessage())
                .isEqualTo("RESTART is not possible while the job is COMPLETED");
        assertThat(journal(pending)).contains("CONTROL_REJECTED");
    }

    @Test
    void C_13_aRequestThatIsStartingIsDecidedOnTheNextPoll() {
        BatchJobRequest r = request(JobStatus.PICKED, null);
        BatchJobControl c = command(r, ControlCommand.STOP);
        processor.processPending();
        assertThat(reload(c).getStatus()).isEqualTo(BatchJobControl.Status.PENDING);
        assertThat(reload(r).getStatus()).isEqualTo(JobStatus.PICKED);
        // Once it has started (or failed to), the next poll decides.
        BatchJobRequest started = reload(r);
        started.finished(JobStatus.PENDING, 0, 0, 0, null, null, clock.instant());
        requests.save(started);
        processor.processPending();
        assertThat(reload(c).getStatus()).isEqualTo(BatchJobControl.Status.APPLIED);
    }

    @Test
    void C_13_stoppingAnExecutionThatIsNoLongerRunningIsRejectedAndClearsTheIntent() {
        BatchJobRequest r = request(JobStatus.RUNNING, null);
        BatchJobControl c = command(r, ControlCommand.PAUSE);
        processor.processPending();
        assertThat(reload(c).getStatus()).isEqualTo(BatchJobControl.Status.REJECTED);
        assertThat(reload(c).getOutcomeMessage()).isEqualTo("The execution is no longer running");
        assertThat(reload(r).getStopIntent()).isNull();
        assertThat(reload(r).getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    /**
     * SRS-15-27 "beyond the configured period" (BMAD TR-079): the window is the setting, whatever
     * it holds (its 72 h default is no FCI value, CLR-132); a run one hour past it is refused,
     * naming the configured value. That a PAUSED job also expires is as built (CLR-132).
     */
    @Test
    void C_13_resumeAndRestartAreRefusedAfterTheRestartWindow() {
        Duration window = batchProperties.restartWindow();
        String passed = "Restart window of " + window.toHours() + " h has passed";
        BatchJobRequest paused =
                request(JobStatus.PAUSED, clock.instant().minus(window.plusHours(1)));
        BatchJobControl resume = command(paused, ControlCommand.RESUME);
        BatchJobRequest failed =
                request(JobStatus.FAILED, clock.instant().minus(window.plusHours(8)));
        BatchJobControl restart = command(failed, ControlCommand.RESTART);
        processor.processPending();
        assertThat(reload(resume).getStatus()).isEqualTo(BatchJobControl.Status.REJECTED);
        assertThat(reload(resume).getOutcomeMessage()).isEqualTo(passed);
        assertThat(reload(restart).getOutcomeMessage()).isEqualTo(passed);
        assertThat(reload(failed).getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void C_13_aRestartWithinTheWindowPicksTheRequestAndRunsItAgain() throws Exception {
        BatchJobRequest failed =
                request(JobStatus.FAILED, clock.instant().minus(Duration.ofHours(1)));
        BatchJobControl restart = command(failed, ControlCommand.RESTART);
        processor.processPending();
        BatchJobControl applied = reload(restart);
        assertThat(applied.getStatus()).isEqualTo(BatchJobControl.Status.APPLIED);
        assertThat(applied.getOutcomeMessage())
                .isEqualTo("Restarting from the last committed chunk");
        // The relaunch runs on a job thread: wait for it to end.
        JobStatus status = reload(failed).getStatus();
        for (int i = 0;
                i < 240 && (status == JobStatus.PICKED || status == JobStatus.RUNNING);
                i++) {
            Thread.sleep(250);
            status = reload(failed).getStatus();
        }
        assertThat(status).isIn(JobStatus.COMPLETED, JobStatus.FAILED);
        assertThat(reload(failed).getAttemptNo()).isGreaterThanOrEqualTo(1);
        assertThat(journal(failed)).contains("CONTROL_APPLIED");
    }

    @Test
    void C_13_anAlreadyDecidedCommandIsNotAppliedAgain() {
        BatchJobRequest r = request(JobStatus.PENDING, null);
        BatchJobControl c = command(r, ControlCommand.STOP);
        c.rejected("other-node", "decided elsewhere", clock.instant());
        controls.save(c);
        processor.processPending();
        assertThat(reload(r).getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(reload(c).getOutcomeMessage()).isEqualTo("decided elsewhere");
    }
}
