package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.notification.MailAttachment;
import in.gov.fci.payroll.platform.notification.MemberDocuments;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The payslip e-mailed after the month-end lock (OD-148; SRS-17-1 l.3250, O-01 l.3390, l.3392, E-01
 * l.3274): the same file the member downloads on E-01 for the same locked month ({@link
 * PayslipPdfService#mine}: the locked ACTUAL result of the member's own type, PAN and bank account
 * masked as on screen), read as the member's own record; the wording may name the month and its
 * gross, deductions and net ({month}, {gross}, {deductions}, {net}: FCI's PAYSLIP template). A
 * month without a locked payslip has nothing to send.
 */
@Component
public class PayslipMailDocument implements MemberDocuments {

    public static final String TYPE = "PAYSLIP";

    private final PayslipPdfService pdfs;
    private final PayslipService payslips;

    public PayslipMailDocument(PayslipPdfService pdfs, PayslipService payslips) {
        this.pdfs = pdfs;
        this.payslips = payslips;
    }

    @Override
    public String documentType() {
        return TYPE;
    }

    @Override
    public Optional<Rendered> render(long employeeId, String periodKey, String sourceKey) {
        AccessContext member = member(employeeId);
        byte[] pdf;
        PayslipService.Payslip slip;
        try {
            pdf = pdfs.mine(member, periodKey);
            slip = payslips.payslip(member, periodKey);
        } catch (FciException e) {
            if (e.getErrorCode() == CommonErrorCode.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
        return Optional.of(
                new Rendered(
                        MailAttachment.pdf("payslip-" + periodKey + ".pdf", pdf),
                        Map.of(
                                "month",
                                periodKey.substring(4) + "/" + periodKey.substring(0, 4),
                                "gross",
                                slip.gross().toPlainString(),
                                "deductions",
                                slip.totalDeductions().toPlainString(),
                                "net",
                                slip.net().toPlainString())));
    }

    /** The member's own read (E-01 SELF), as the member's own download is. */
    static AccessContext member(long employeeId) {
        return new AccessContext(
                null,
                "system.document-mail",
                null,
                null,
                null,
                null,
                employeeId,
                List.of(),
                Map.of(),
                Set.of(AccessContext.key("E-01", "SELF")),
                Set.of(),
                Set.of());
    }
}
