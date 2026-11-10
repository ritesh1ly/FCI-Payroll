package in.gov.fci.payroll.jobs.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.jobs.batch.support.BatchHistory;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.ControlCommand;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * C-13 refusals before a job is queued (SRS-G-29, SRS-NF-11): only a submittable job type at a
 * known location in a cycle unit, with a priority of 1 - 9 that may lower its type's priority but
 * never raise it (DR-17), and a control command only with its reason.
 */
class JobRequestRulesTest {

    private final JobRepositories.JobRequests requests = mock(JobRepositories.JobRequests.class);
    private final JobCatalog catalog = mock(JobCatalog.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final BatchProperties properties = mock(BatchProperties.class);
    private final Authorization policy = mock(Authorization.class);

    private final JobRequestService service =
            new JobRequestService(
                    requests,
                    mock(JobRepositories.Controls.class),
                    catalog,
                    mock(JobJournal.class),
                    mock(BatchHistory.class),
                    policy,
                    org,
                    mock(AuditService.class),
                    mock(MakerChecker.class),
                    properties,
                    mock(ApiProperties.class),
                    List.of(),
                    mock(ApprovalStatuses.class),
                    mock(JobRepositories.Halts.class),
                    Clock.fixed(Instant.parse("2026-10-22T06:00:00Z"), ZoneOffset.UTC));

    private final AccessContext maker =
            new AccessContext(
                    1L,
                    "maker@x",
                    "Maker",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    private static ErrorCode code(Throwable t) {
        return ((FciException) t).getErrorCode();
    }

    private static void refused(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).satisfies(e -> assertThat(code(e)).isEqualTo(expected));
    }

    private void locationKnown(boolean inUnit) {
        when(catalog.validate(any(), any())).thenReturn(Map.of("locationId", "10"));
        when(org.location(10L)).thenReturn(Optional.of(mock(OrgLocation.class)));
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(10L);
        when(org.cycleUnitOf(10L)).thenReturn(inUnit ? Optional.of(unit) : Optional.empty());
    }

    @Test
    void SRS_G_29_onlyASubmittableTypeAtAKnownLocationInACycleUnitIsQueued() {
        refused(
                () -> service.submit(maker, "NO_SUCH_JOB", Map.of(), 5, "r"),
                ApiErrorCode.JOB_TYPE_NOT_SUBMITTABLE);
        when(catalog.validate(any(), any())).thenReturn(Map.of("locationId", "10"));
        when(org.location(10L)).thenReturn(Optional.empty());
        refused(
                () -> service.submit(maker, "PAYROLL_DRY_RUN", Map.of(), 5, "r"),
                CommonErrorCode.NOT_FOUND);
        locationKnown(false);
        refused(
                () -> service.submit(maker, "PAYROLL_DRY_RUN", Map.of(), 5, "r"),
                ApiErrorCode.CYCLE_NO_UNIT);
        verify(requests, never()).save(any());
    }

    @Test
    void SRS_NF_7_aStatutoryOutputJobNeedsItsOwnScreensCreateRightAtTheUnit() {
        locationKnown(true);
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .requireAt(maker, "C-10", "CREATE", 10L);
        refused(
                () -> service.submit(maker, "TAX_FORM16_PART_B", Map.of(), 5, "r"),
                CommonErrorCode.ACCESS_DENIED);
        // C-13 at the location and at its unit (the same office here), then the owning screen.
        verify(policy, times(2)).requireAt(maker, "C-13", "CREATE", 10L);
        verify(requests, never()).save(any());
        assertThat(JobRequestService.OWNER_SCREENS)
                .containsOnly(
                        Map.entry(JobType.TAX_FORM16_PART_B, "C-10"),
                        Map.entry(JobType.TAX_24Q_RETURN, "C-11"));
    }

    @Test
    void SRS_NF_11_aGivenPriorityIsOneToNine() {
        locationKnown(true);
        for (Integer bad : Arrays.asList(0, 10, -1)) {
            assertThatThrownBy(() -> service.submit(maker, "PAYROLL_DRY_RUN", Map.of(), bad, "r"))
                    .as(String.valueOf(bad))
                    .satisfies(
                            e -> {
                                assertThat(code(e)).isEqualTo(ApiErrorCode.JOB_PARAMETER_INVALID);
                                assertThat(((FciException) e).getMessageArgs())
                                        .containsExactly("priority");
                            });
        }
        verify(requests, never()).save(any());
    }

    private int queuedPriority(String type, Integer asked) {
        reset(requests);
        when(requests.save(any())).thenThrow(new IllegalStateException("captured"));
        assertThatThrownBy(() -> service.submit(maker, type, Map.of(), asked, "r"))
                .hasMessage("captured");
        ArgumentCaptor<BatchJobRequest> saved = ArgumentCaptor.forClass(BatchJobRequest.class);
        verify(requests).save(saved.capture());
        return saved.getValue().getPriority();
    }

    // Deep review DR-17: the type sets the priority; a requester may lower it, never raise it.
    @Test
    void SRS_NF_11_theTypeSetsThePriorityARequesterMayOnlyLowerIt() {
        locationKnown(true);
        assertThat(queuedPriority("PAYROLL_COMPUTATION", null)).isEqualTo(9);
        assertThat(queuedPriority("PAYROLL_COMPUTATION", 4)).isEqualTo(4);
        assertThat(queuedPriority("PAYROLL_DRY_RUN", null)).isEqualTo(2);
        assertThat(queuedPriority("PAYROLL_DRY_RUN", 9)).as("never raised").isEqualTo(2);
        assertThat(queuedPriority("PAYROLL_DRY_RUN", 1)).isEqualTo(1);
    }

    @Test
    void SRS_G_29_aControlCommandNeedsItsReason() {
        for (String missing : Arrays.asList(null, "", " \t")) {
            for (ControlCommand command : ControlCommand.values()) {
                refused(
                        () -> service.requestControl(maker, 7L, command, missing),
                        ApiErrorCode.REASON_REQUIRED);
            }
        }
        verify(requests, never()).findById(any());
    }
}
