package in.gov.fci.payroll.allowance.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.allowance.business.model.ReimbursementClaim;
import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * SRS-11-14, SRS-G-30 (I-03 order-copy card, A-41): the Payroll Maker adds a supporting document to
 * a labour claim not yet validated, inside the office scope; an employee claim is HRMS's and
 * read-only (SRS-11-1); a claim past validation stands as the Checker sees it; an unknown claim is
 * not found.
 */
class ClaimAttachTest {

    private final AllowanceRepositories.Claims claims = mock(AllowanceRepositories.Claims.class);
    private final ClaimCatalogue catalogue = mock(ClaimCatalogue.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final LabourClaimService service =
            new LabourClaimService(
                    claims,
                    catalogue,
                    documents,
                    mock(MakerChecker.class),
                    policy,
                    audit,
                    mock(SystemParameters.class));
    private final LabourClaimService.Document document =
            new LabourClaimService.Document(
                    "OTHER",
                    "BILL/1",
                    LocalDate.of(2026, 10, 1),
                    "Depot office",
                    "bill.pdf",
                    new byte[] {1});

    private ReimbursementClaim claim(String memberType, String status) {
        ReimbursementClaim c = mock(ReimbursementClaim.class);
        when(c.getEmployeeId()).thenReturn(7L);
        when(c.getMemberType()).thenReturn(memberType);
        when(c.getStatus()).thenReturn(status);
        when(claims.findById(5L)).thenReturn(Optional.of(c));
        when(catalogue.member(7L))
                .thenReturn(
                        new ClaimCatalogue.MemberFacts(
                                7L, "L1", "Asha", "DEPT_LABOUR", "IN_SERVICE", null, 40L));
        return c;
    }

    @Test
    void SRS_11_14_theMakerAddsADocumentToALabourClaimNotYetValidated() {
        claim("LABOUR", "SUBMITTED");
        DocumentStore.AttachmentView view = mock(DocumentStore.AttachmentView.class);
        when(documents.attach(any(), any())).thenReturn(view);
        when(context.loginId()).thenReturn("maker@x");
        assertThat(service.attach(context, 5L, document)).isSameAs(view);
        verify(policy).requireEmployeeRecord(context, "I-03", "EDIT", 7L, 40L);
        verify(audit).record(eq("maker@x"), any());
    }

    @Test
    void SRS_11_1_anEmployeeClaimIsReadOnlyHere() {
        claim("EMPLOYEE", "SUBMITTED");
        assertThatThrownBy(() -> service.attach(context, 5L, document))
                .isInstanceOf(FciException.class);
        verify(documents, never()).attach(any(), any());
    }

    @Test
    void SRS_11_13_aClaimPastValidationStandsAsTheCheckerSeesIt() {
        claim("LABOUR", "VALIDATED");
        assertThatThrownBy(() -> service.attach(context, 5L, document))
                .isInstanceOf(FciException.class);
        verify(documents, never()).attach(any(), any());
    }

    @Test
    void RBAC_anOfficeOutsideTheScopeIsRefusedBeforeAnythingIsStored() {
        claim("LABOUR", "SUBMITTED");
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .requireEmployeeRecord(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.attach(context, 5L, document))
                .isInstanceOf(FciException.class);
        verify(documents, never()).attach(any(), any());
    }

    @Test
    void SRS_11_14_anUnknownClaimIsNotFound() {
        when(claims.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.attach(context, 9L, document))
                .isInstanceOf(FciException.class);
    }
}
