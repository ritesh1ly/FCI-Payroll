package in.gov.fci.payroll.jobs.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.business.model.ControlCommand;
import in.gov.fci.payroll.jobs.orchestration.BatchHaltService;
import in.gov.fci.payroll.jobs.orchestration.JobCatalog;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * C-13 control commands (plan 05j): stop, pause, resume and restart each ask the service for their
 * own command and answer 202 with no-store; a refusal of the service (no reason, a state that does
 * not allow the command) passes through unchanged and nothing else is called.
 */
class JobControllerControlTest {

    private final JobRequestService jobs = mock(JobRequestService.class);
    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final JobController controller =
            new JobController(
                    jobs,
                    mock(BatchHaltService.class),
                    mock(JobCatalog.class),
                    contexts,
                    mock(Paging.class));

    JobControllerControlTest() {
        when(contexts.current()).thenReturn(context);
    }

    @Test
    void C_13_pauseAsksForThePauseCommandAndAnswersAccepted() {
        JobRequestService.ControlView view = mock(JobRequestService.ControlView.class);
        when(jobs.requestControl(context, 41L, ControlCommand.PAUSE, "Lunch break"))
                .thenReturn(view);

        ResponseEntity<JobRequestService.ControlView> response =
                controller.pause(41L, new JobController.ControlRequest("Lunch break"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isSameAs(view);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        verify(jobs).requestControl(context, 41L, ControlCommand.PAUSE, "Lunch break");
        verifyNoMoreInteractions(jobs);
    }

    @Test
    void C_13_stopResumeAndRestartEachAskForTheirOwnCommand() {
        JobController.ControlRequest request = new JobController.ControlRequest("Reason");

        controller.stop(7L, request);
        controller.resume(8L, request);
        controller.restart(9L, request);

        verify(jobs).requestControl(context, 7L, ControlCommand.STOP, "Reason");
        verify(jobs).requestControl(context, 8L, ControlCommand.RESUME, "Reason");
        verify(jobs).requestControl(context, 9L, ControlCommand.RESTART, "Reason");
        verifyNoMoreInteractions(jobs);
    }

    @Test
    void C_13_aRefusedControlCommandPassesTheServiceRefusalThrough() {
        when(jobs.requestControl(eq(context), eq(41L), eq(ControlCommand.PAUSE), any()))
                .thenThrow(new FciException(ApiErrorCode.REASON_REQUIRED));

        assertThatThrownBy(() -> controller.pause(41L, new JobController.ControlRequest(" ")))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ApiErrorCode.REASON_REQUIRED));
    }

    @Test
    void C_13_abandonIsAcceptedForTheCheckerToDecide() {
        JobRequestService.JobView view = mock(JobRequestService.JobView.class);
        when(jobs.requestAbandon(context, 5L, "Not needed")).thenReturn(view);

        ResponseEntity<JobRequestService.JobView> response =
                controller.abandon(5L, new JobController.ControlRequest("Not needed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isSameAs(view);
    }
}
