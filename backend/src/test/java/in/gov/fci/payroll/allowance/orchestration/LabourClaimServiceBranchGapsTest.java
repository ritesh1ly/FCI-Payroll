package in.gov.fci.payroll.allowance.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.allowance.business.AllowanceErrorCode;
import in.gov.fci.payroll.allowance.business.ClaimRules;
import in.gov.fci.payroll.allowance.business.model.ReimbursementClaim;
import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * I-03 labour claims (SRS-11-13, SRS-11-14; plan 10 D10-7): an entry without documents is a claim
 * with none; an employee (not a labour) is HRMS-only; only a SUBMITTED labour claim is validated;
 * the Maker's reason is kept (stripped) before the claim summary, a blank one is no reason.
 */
class LabourClaimServiceBranchGapsTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private final AllowanceRepositories.Claims claims = mock(AllowanceRepositories.Claims.class);
    private final ClaimCatalogue catalogue = mock(ClaimCatalogue.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final SystemParameters settings = mock(SystemParameters.class);
    private final AccessContext context = mock(AccessContext.class);
    private final LabourClaimService service =
            new LabourClaimService(
                    claims,
                    catalogue,
                    documents,
                    approvals,
                    mock(Authorization.class),
                    mock(AuditService.class),
                    settings);

    private final ClaimRules.Period period =
            new ClaimRules.Period(TODAY.minusDays(10), TODAY.minusDays(5));

    private static ClaimCatalogue.MemberFacts member(String type) {
        return new ClaimCatalogue.MemberFacts(7L, "L0000007", "Ram", type, "IN_SERVICE", null, 40L);
    }

    @BeforeEach
    void wire() {
        when(settings.today()).thenReturn(TODAY);
        when(context.loginId()).thenReturn("ldh@fci-demo.test");
        when(catalogue.norm(anyString(), eq("LABOUR"), any()))
                .thenReturn(new ClaimCatalogue.Norm(3L, new BigDecimal("500"), null));
        when(catalogue.adjudicate(any(), anyLong(), anyString(), any(), any(), any()))
                .thenReturn(
                        new ClaimRules.Adjudication(
                                new BigDecimal("500"), new BigDecimal("400"), false));
        when(claims.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void SRS_11_14_anEntryWithoutDocumentsIsAClaimWithNone() {
        LabourClaimService.NewClaim in =
                new LabourClaimService.NewClaim(
                        "L0000007",
                        "LABOUR_TA",
                        period.from(),
                        period.to(),
                        new BigDecimal("400"),
                        null);
        assertThat(in.documents()).isEmpty();
        when(catalogue.memberByNumber("L0000007")).thenReturn(member("DEPT_LABOUR"));
        when(catalogue.types("LABOUR", TODAY))
                .thenReturn(List.of(new ClaimCatalogue.ClaimType("LABOUR_TA", "TA", 951)));
        when(catalogue.requiredDocuments(TODAY)).thenReturn(List.of());
        when(catalogue.overlap(eq(7L), eq("LABOUR_TA"), any(), isNull()))
                .thenReturn(Optional.empty());

        ReimbursementClaim saved = service.enter(context, in);

        assertThat(saved.getStatus()).isEqualTo(ReimbursementClaim.Status.SUBMITTED.name());
        verify(documents, never()).attach(anyString(), any());
    }

    @Test
    void SRS_11_13_anEmployeeHasNoLabourClaimEntered() {
        when(catalogue.memberByNumber("E0000007")).thenReturn(member("STAFF"));
        LabourClaimService.NewClaim in =
                new LabourClaimService.NewClaim(
                        "E0000007",
                        "LABOUR_TA",
                        period.from(),
                        period.to(),
                        new BigDecimal("400"),
                        List.of());
        assertThatThrownBy(() -> service.enter(context, in))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AllowanceErrorCode.HRMS_ONLY));
    }

    @Test
    void SRS_11_13_anEmployeeClaimIsNeverValidatedOnTheLabourPath() {
        ReimbursementClaim employeeClaim =
                ReimbursementClaim.fromHrms(
                        7L,
                        "MEDICAL_OPD",
                        period,
                        new BigDecimal("400"),
                        3L,
                        new ClaimRules.Adjudication(
                                new BigDecimal("500"), new BigDecimal("400"), false),
                        "HR-1",
                        9L,
                        null,
                        null,
                        11L);
        employeeClaim.returned(); // SUBMITTED: only the member type (EMPLOYEE) refuses it
        when(claims.findById(5L)).thenReturn(Optional.of(employeeClaim));
        when(catalogue.member(7L)).thenReturn(member("DEPT_LABOUR"));
        assertThatThrownBy(() -> service.validate(context, 5L, "ok"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AllowanceErrorCode.STATE));
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_11_13_theMakersReasonLeadsTheSummaryAndABlankOneIsNone() {
        when(catalogue.member(7L)).thenReturn(member("DEPT_LABOUR"));
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(21L, ApprovalStatus.PENDING_APPROVAL, null));
        List<String> reasons = new ArrayList<>();
        for (String reason : Arrays.asList(null, "   ", "  checked bills  ")) {
            ReimbursementClaim claim =
                    ReimbursementClaim.labour(7L, "LABOUR_TA", period, new BigDecimal("400"));
            when(claims.findById(5L)).thenReturn(Optional.of(claim));
            service.validate(context, 5L, reason);
            ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
            verify(approvals, atLeastOnce()).submit(any(), sent.capture(), any());
            reasons.add(sent.getValue().reason());
            assertThat(claim.getApprovalId()).isEqualTo(21L);
        }
        String summary = "LABOUR_TA " + period.from() + " to " + period.to();
        assertThat(reasons).containsExactly(summary, summary, "checked bills - " + summary);
    }
}
