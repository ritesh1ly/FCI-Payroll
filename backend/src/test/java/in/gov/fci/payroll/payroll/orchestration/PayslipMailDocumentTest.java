package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.notification.MemberDocuments;
import in.gov.fci.payroll.platform.security.AccessContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The e-mailed payslip (OD-148; SRS-17-1, O-01 l.3392): the same file as the member's own E-01
 * download of the locked month, read as the member; a month without a locked payslip is nothing to
 * send; any other refusal is a failure the dispatcher retries.
 */
class PayslipMailDocumentTest {

    private final PayslipPdfService pdfs = mock(PayslipPdfService.class);
    private final PayslipService payslips = mock(PayslipService.class);
    private final PayslipMailDocument mail = new PayslipMailDocument(pdfs, payslips);

    private static PayslipService.Payslip slip() {
        return new PayslipService.Payslip(
                "202610",
                "104882",
                "Rakesh Sharma",
                null,
                null,
                "IDA",
                "******234F",
                "******6789",
                null,
                java.util.List.of(),
                java.util.List.of(),
                new java.math.BigDecimal("45000.00"),
                new java.math.BigDecimal("5400.00"),
                new java.math.BigDecimal("39600.00"),
                "STAFF",
                false);
    }

    @Test
    void SRS_17_1_thePayslipIsTheMembersOwnLockedMonthDownload() {
        assertThat(mail.documentType()).isEqualTo("PAYSLIP");
        when(pdfs.mine(any(AccessContext.class), eq("202610"))).thenReturn(new byte[] {1, 2});
        when(payslips.payslip(any(AccessContext.class), eq("202610"))).thenReturn(slip());
        MemberDocuments.Rendered r = mail.render(7L, "202610", "9").orElseThrow();
        assertThat(r.attachment().fileName()).isEqualTo("payslip-202610.pdf");
        assertThat(r.attachment().content()).containsExactly(1, 2);
        assertThat(r.values())
                .containsEntry("month", "10/2026")
                .containsEntry("gross", "45000.00")
                .containsEntry("deductions", "5400.00")
                .containsEntry("net", "39600.00");
        ArgumentCaptor<AccessContext> context = ArgumentCaptor.forClass(AccessContext.class);
        verify(pdfs).mine(context.capture(), eq("202610"));
        assertThat(context.getValue().employeeId()).isEqualTo(7L);
        assertThat(context.getValue().permissionKeys()).containsExactly("E-01:SELF");
        assertThat(context.getValue().groups()).isEmpty();
    }

    @Test
    void SRS_17_12_noLockedPayslipIsNothingAndOtherRefusalsAreFailures() {
        when(pdfs.mine(any(AccessContext.class), eq("202611")))
                .thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        assertThat(mail.render(7L, "202611", "9")).isEmpty();
        when(pdfs.mine(any(AccessContext.class), eq("202612")))
                .thenThrow(
                        new FciException(
                                ApiErrorCode.PERMISSION_DENIED,
                                new Object[] {"E-01", "SELF"},
                                java.util.List.of(),
                                null));
        assertThatThrownBy(() -> mail.render(7L, "202612", "9")).isInstanceOf(FciException.class);
    }
}
