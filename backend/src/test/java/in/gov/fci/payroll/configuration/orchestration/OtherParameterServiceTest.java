package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.OtherParameter;
import in.gov.fci.payroll.configuration.business.model.ReferenceValue;
import in.gov.fci.payroll.configuration.orchestration.OtherParameterService.ParameterRow;
import in.gov.fci.payroll.configuration.orchestration.OtherParameterService.ProposedParameter;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * M-23 Other Parameters (SRS-11-15, SRS-1-66; DDL v4.0 S19): the version in force is the approved
 * one covering today, never an approved one not yet started; the pending one waits for the Checker;
 * a version without an approval shows no status; a proposal needs a request with its start day and,
 * off Headquarters, names no office.
 */
class OtherParameterServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    private final ConfigurationRepositories.ReferenceValues catalogue =
            mock(ConfigurationRepositories.ReferenceValues.class);
    private final ConfigurationRepositories.OtherParameters parameters =
            mock(ConfigurationRepositories.OtherParameters.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final SystemParameters settings = mock(SystemParameters.class);
    private final AccessContext context = mock(AccessContext.class);
    private final OtherParameterService service =
            new OtherParameterService(
                    catalogue,
                    parameters,
                    statuses,
                    approvals,
                    mock(Authorization.class),
                    settings);

    OtherParameterServiceTest() {
        ReferenceValue entry = mock(ReferenceValue.class);
        when(entry.getValueCode()).thenReturn("SHIFT_CEILING");
        when(entry.getValueLabelEn()).thenReturn("Shift allowance ceiling");
        when(catalogue.findByListCodes(List.of(OtherParameterService.LIST)))
                .thenReturn(List.of(entry));
        when(settings.today()).thenReturn(TODAY);
    }

    private static OtherParameter version(int no, Long approval, boolean coversToday) {
        OtherParameter p = mock(OtherParameter.class);
        when(p.getParameterCode()).thenReturn("SHIFT_CEILING");
        when(p.getVersionNo()).thenReturn(no);
        when(p.getApprovalId()).thenReturn(approval);
        when(p.coversDay(TODAY)).thenReturn(coversToday);
        return p;
    }

    /** SRS M-23 table, FRS 2.3, R-SRC: the MHS amount is received from HRMS; no version is held. */
    @Test
    void SRS_M23_theMhsRowIsHrmsOwnedAndItsVersionIsRefused() {
        ReferenceValue mhs = mock(ReferenceValue.class);
        when(mhs.getValueCode()).thenReturn(OtherParameterService.MHS_CONTRIBUTION);
        when(catalogue.findByListCodes(List.of("OTHER_PARAMETER"))).thenReturn(List.of(mhs));
        when(parameters.findByParameterCodeInOrderByParameterCodeAscVersionNoDesc(any()))
                .thenReturn(List.of());

        assertThat(service.list(context).get(0).hrmsOwned()).isTrue();
        assertThatThrownBy(
                        () ->
                                service.propose(
                                        context,
                                        OtherParameterService.MHS_CONTRIBUTION,
                                        null,
                                        null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.MASTER_HRMS_OWNED));
    }

    @Test
    void SRS_11_15_theVersionInForceIsApprovedAndCoversTodayAndThePendingOneWaits() {
        OtherParameter future = version(4, 14L, false);
        OtherParameter rejected = version(3, 13L, true);
        OtherParameter inForce = version(2, 12L, true);
        OtherParameter pending = version(5, 15L, false);
        OtherParameter unapproved = version(1, null, false);
        when(parameters.findByParameterCodeInOrderByParameterCodeAscVersionNoDesc(any()))
                .thenReturn(List.of(pending, future, rejected, inForce, unapproved));
        // The reader answers a HashMap, which accepts the null id of a version without an approval.
        Map<Long, ApprovalStatus> decided =
                new java.util.HashMap<>(
                        Map.of(
                                15L, ApprovalStatus.PENDING_APPROVAL,
                                14L, ApprovalStatus.APPROVED,
                                13L, ApprovalStatus.REJECTED,
                                12L, ApprovalStatus.APPROVED));
        when(statuses.of(any())).thenReturn(decided);
        ParameterRow row = service.list(context).get(0);
        assertThat(row.payCode()).isNull();
        assertThat(row.current().versionNo()).isEqualTo(2);
        assertThat(row.current().status()).isEqualTo("APPROVED");
        assertThat(row.pending().versionNo()).isEqualTo(5);

        when(parameters.findByParameterCodeOrderByVersionNoDesc("SHIFT_CEILING"))
                .thenReturn(List.of(unapproved));
        assertThat(service.history(context, "SHIFT_CEILING").get(0).status()).isEqualTo("NONE");
    }

    @Test
    void SRS_11_15_aProposalNeedsARequestWithItsStartDay() {
        for (ProposedParameter bad :
                java.util.Arrays.asList(
                        null,
                        new ProposedParameter(
                                null,
                                BigDecimal.TEN,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                true,
                                "r"))) {
            assertThatThrownBy(() -> service.propose(context, "SHIFT_CEILING", bad, null))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            ex ->
                                    assertThat(ex.getErrorCode())
                                            .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        }
    }

    @Test
    void SRS_11_15_aProposalOffHeadquartersNamesNoOffice() {
        when(context.locationId()).thenReturn(41L);
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(9L, ApprovalStatus.PENDING_APPROVAL, null));
        when(parameters.save(any())).thenAnswer(call -> call.getArgument(0));
        service.propose(
                context,
                "SHIFT_CEILING",
                new ProposedParameter(
                        TODAY.plusDays(3),
                        null,
                        new BigDecimal("1500"),
                        null,
                        " ",
                        null,
                        null,
                        " ",
                        true,
                        "Ceiling of the 2026 circular"),
                null);
        ArgumentCaptor<Submission> submitted = ArgumentCaptor.captor();
        verify(approvals).submit(any(), submitted.capture(), any());
        assertThat(submitted.getValue().locationId()).isNull();
    }
}
