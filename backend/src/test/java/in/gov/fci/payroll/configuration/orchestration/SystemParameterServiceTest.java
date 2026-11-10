package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A-07 system parameters (SRS-SEC-14, SRS-G-48): a proposal names its setting and value; the first
 * value of a setting not yet configured is proposed from none; off Headquarters it names no office.
 */
class SystemParameterServiceTest {

    private final SystemParameters current = mock(SystemParameters.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final AccessContext context = mock(AccessContext.class);
    private final SystemParameterService service =
            new SystemParameterService(
                    mock(ConfigurationRepositories.RuleVersions.class),
                    current,
                    approvals,
                    mock(Authorization.class),
                    Clock.systemUTC());

    @Test
    void SRS_SEC_14_aProposalNamesItsSettingAndValue() {
        for (SystemParameterService.ChangeRequest bad :
                Arrays.asList(
                        null,
                        new SystemParameterService.ChangeRequest(null, 15L, "r"),
                        new SystemParameterService.ChangeRequest("SESSION_IDLE", null, "r"))) {
            assertThatThrownBy(() -> service.propose(context, bad, null))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            ex ->
                                    assertThat(ex.getErrorCode())
                                            .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        }
    }

    @Test
    void SRS_SEC_14_aFirstValueIsProposedFromNoneAndOffHeadquartersNamesNoOffice() {
        when(context.locationId()).thenReturn(41L);
        when(context.loginId()).thenReturn("rule.admin@x");
        when(current.today()).thenReturn(LocalDate.of(2026, 10, 29));
        when(current.definitions())
                .thenReturn(
                        List.of(
                                new SystemParameters.Definition(
                                        3L,
                                        "SESSION_IDLE",
                                        "SESSION",
                                        "INTEGER",
                                        5,
                                        60,
                                        "MINUTES",
                                        true)));
        when(current.current()).thenReturn(Map.of());
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(9L, ApprovalStatus.PENDING_APPROVAL, null));
        service.propose(
                context, new SystemParameterService.ChangeRequest("SESSION_IDLE", 15L, "r"), null);
        ArgumentCaptor<Submission> submitted = ArgumentCaptor.captor();
        verify(approvals).submit(any(), submitted.capture(), any());
        assertThat(submitted.getValue().locationId()).isNull();
        assertThat(submitted.getValue().payload()).contains("\"from\":null");
    }
}
