package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.ReferenceValue;
import in.gov.fci.payroll.configuration.orchestration.RateMasterService.LineView;
import in.gov.fci.payroll.configuration.orchestration.RateMasterService.ProposedVersion;
import in.gov.fci.payroll.configuration.orchestration.RateMasterService.Screen;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
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
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * M-04 / M-22 rate masters (SRS-G-6, SRS-11-15, SRS-1-62): a proposal names its start day and at
 * least one line; a special-compensatory set, like a staff revision, links its Active circular
 * (US-GAP-211); proposed off Headquarters it names no office; the History button finds the screen
 * of a code, or none.
 */
class RateMasterServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);

    private final ConfigurationRepositories.ReferenceValues catalogue =
            mock(ConfigurationRepositories.ReferenceValues.class);
    private final ConfigurationRepositories.RateSets sets =
            mock(ConfigurationRepositories.RateSets.class);
    private final ConfigurationRepositories.RateSetLines lines =
            mock(ConfigurationRepositories.RateSetLines.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Circulars circulars = mock(Circulars.class);
    private final AccessContext context = mock(AccessContext.class);
    private final RateMasterService service =
            new RateMasterService(
                    catalogue,
                    sets,
                    lines,
                    approvals,
                    mock(Authorization.class),
                    mock(SystemParameters.class),
                    circulars);

    RateMasterServiceTest() {
        ReferenceValue entry = mock(ReferenceValue.class);
        when(entry.getValueCode()).thenReturn("SCA_NE");
        when(entry.getValueLabelEn()).thenReturn("Special Compensatory Allowance (North East)");
        when(catalogue.findByListCodes(List.of("SPECIAL_COMPENSATORY", "OTHER_RATE_MASTER")))
                .thenReturn(List.of(entry));
        when(catalogue.findByListCodes(List.of("STAFF_RATE_MASTER"))).thenReturn(List.of());
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(9L, ApprovalStatus.PENDING_APPROVAL, null));
        // The database assigns the id on save.
        when(sets.save(any()))
                .thenAnswer(
                        call -> {
                            Object set = call.getArgument(0);
                            org.springframework.test.util.ReflectionTestUtils.setField(
                                    set, "rateSetId", 31L);
                            return set;
                        });
        when(lines.save(any())).thenAnswer(call -> call.getArgument(0));
        when(context.locationId()).thenReturn(41L);
    }

    private static LineView line() {
        return new LineView(
                null, " ", null, null, null, null, "SCA_NE", new BigDecimal("1500"), "AMOUNT");
    }

    @Test
    void SRS_G_6_aProposalNamesItsStartDayAndAtLeastOneLine() {
        for (ProposedVersion bad :
                Arrays.asList(
                        null,
                        new ProposedVersion(null, null, List.of(line()), "r"),
                        new ProposedVersion(FROM, null, null, "r"),
                        new ProposedVersion(FROM, null, List.of(), "r"))) {
            assertThatThrownBy(() -> service.propose(context, Screen.SPECIAL, "SCA_NE", bad, null))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            ex ->
                                    assertThat(ex.getErrorCode())
                                            .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        }
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_11_15_aSpecialSetLinksItsActiveCircularAndOffHeadquartersNamesNoOffice() {
        service.propose(
                context,
                Screen.SPECIAL,
                "SCA_NE",
                new ProposedVersion(FROM, 77L, List.of(line()), "NE revision"),
                null);
        // SRS l.2819 / SRS-G-38 (US-GAP-211): an M-22 rate change is linked to its circular.
        verify(circulars).requireActive(77L);
        ArgumentCaptor<Submission> submitted = ArgumentCaptor.captor();
        verify(approvals).submit(any(), submitted.capture(), any());
        assertThat(submitted.getValue().locationId()).isNull();
        assertThat(submitted.getValue().moduleCode()).isEqualTo("M-22");
    }

    @Test
    void SRS_G_38_anM22RateChangeWithoutAnActiveCircularIsNotSubmitted() {
        doThrow(new FciException(ApiErrorCode.CIRCULAR_NOT_ACTIVE))
                .when(circulars)
                .requireActive(null);
        assertThatThrownBy(
                        () ->
                                service.propose(
                                        context,
                                        Screen.SPECIAL,
                                        "SCA_NE",
                                        new ProposedVersion(FROM, null, List.of(line()), "r"),
                                        null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ApiErrorCode.CIRCULAR_NOT_ACTIVE));
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_G_6_theHistoryButtonFindsTheScreenOfACodeOrNone() {
        assertThat(service.screenOf("SCA_NE")).contains(Screen.SPECIAL);
        assertThat(service.screenOf("UNKNOWN")).isEmpty();
    }
}
