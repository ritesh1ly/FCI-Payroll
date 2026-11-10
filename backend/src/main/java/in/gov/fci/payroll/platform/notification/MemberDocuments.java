package in.gov.fci.payroll.platform.notification;

import java.util.Map;
import java.util.Optional;

/**
 * The document of one kind that a member is e-mailed (OD-148; SRS-17-1, SRS l.3390, l.3392): the
 * module that owns it renders exactly the file the member downloads in eSeva for the same closed
 * period (same content on re-generation), masked as on the screens. Each owning module registers
 * one implementation: the payroll module the payslip ({@code PAYSLIP}), the tax module Form 16
 * ({@code FORM16}).
 */
public interface MemberDocuments {

    /** The document type it renders: {@code PAYSLIP} or {@code FORM16}. */
    String documentType();

    /**
     * The member's document, or empty when there is none to send (e.g. the certificate is no longer
     * released). When the module stores its PDFs from a batch generation (SRS-NF-5, SRS-NF-7), this
     * attaches the stored file: {@link DocumentNotReadyException} while it is not generated yet
     * (the e-mail waits, its attempt not spent), {@link DocumentFailedException} when the
     * generation recorded a failure for the member (the e-mail fails with that reason). Any other
     * exception is a failure the caller retries by its settings.
     *
     * @param periodKey YYYYMM (payslip) or YYYY-YY (Form 16)
     * @param sourceKey the cycle instance (payslip) or the certificate (Form 16)
     */
    Optional<Rendered> render(long employeeId, String periodKey, String sourceKey);

    /**
     * The rendered document and the values the e-mail wording may name ({month}, {year}, {net} …),
     * none of them PAN, bank or Aadhaar (R-PII).
     */
    record Rendered(MailAttachment attachment, Map<String, String> values) {

        public Rendered {
            values = Map.copyOf(values);
        }
    }
}
