package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.notification.MailAttachment;
import in.gov.fci.payroll.platform.notification.MemberDocuments;
import in.gov.fci.payroll.platform.notification.MemberDocumentsReleased;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.tax.business.model.Form16Certificate;
import in.gov.fci.payroll.tax.business.model.Form16Certificate.Status;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Form 16 by e-mail on its release (OD-148, PENDING F16-5; SRS-7-9: only the merged certificate is
 * released; SRS-17-12: a failed e-mail never blocks the release).
 *
 * <ul>
 *   <li>In the Checker's release decision (C-10, route C10_RELEASE), the certificates being
 *       released (MERGED, or already RELEASED when {@link Form16Service} decided first) are
 *       announced as {@link MemberDocumentsReleased}, so their e-mails are queued in the same
 *       transaction and roll back with it (a refused reconciliation queues nothing).
 *   <li>At sending, the certificate is rendered exactly as the member downloads it on E-01 ({@link
 *       Form16Service#certificatePdf}: Part A then Part B, PAN as the form prescribes, PENDING
 *       F16-3), read as the member's own record; a certificate no longer released is not sent.
 * </ul>
 */
@Component
public class Form16MailDocument implements MemberDocuments {

    public static final String TYPE = "FORM16";

    /** The login recorded on the document access log for an e-mailed Part A. */
    static final String DELIVERY_LOGIN = "system.document-mail";

    private final Form16Service form16;
    private final TaxRepositories.Form16s certificates;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    public Form16MailDocument(
            Form16Service form16,
            TaxRepositories.Form16s certificates,
            ApplicationEventPublisher events,
            ObjectMapper json) {
        this.form16 = form16;
        this.certificates = certificates;
        this.events = events;
        this.json = json;
    }

    @Override
    public String documentType() {
        return TYPE;
    }

    /** OD-148: the approved release announces its certificates (same transaction). */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!Form16Service.SCREEN.equals(decision.moduleCode())
                || !decision.concerns(Form16Service.ENTITY)
                || !decision.approved()) {
            return;
        }
        Form16Service.ReleaseProposal p =
                json.readValue(decision.payload(), Form16Service.ReleaseProposal.class);
        List<MemberDocumentsReleased.Item> items = new ArrayList<>();
        for (Long id : p.certificateIds()) {
            certificates
                    .findById(id)
                    .filter(Form16MailDocument::released)
                    .ifPresent(
                            f ->
                                    items.add(
                                            new MemberDocumentsReleased.Item(
                                                    f.getEmployeeId(),
                                                    f.getFinYear(),
                                                    sourceKey(f))));
        }
        if (!items.isEmpty()) {
            events.publishEvent(
                    new MemberDocumentsReleased(
                            TYPE, p.orgUnitId(), decision.checkerLoginId(), items));
        }
    }

    /**
     * The certificate and its Part B generation: a certificate reissued against a correction return
     * (Part B generated again, released again by the Checker) is e-mailed again; the same release
     * decided twice is not.
     */
    static String sourceKey(Form16Certificate f) {
        Instant generated = f.getPartBGeneratedAt();
        return f.getForm16Id() + "@" + (generated == null ? 0 : generated.toEpochMilli());
    }

    /** The certificate of a source key. */
    static long certificateOf(String sourceKey) {
        int at = sourceKey.indexOf('@');
        return Long.parseLong(at < 0 ? sourceKey : sourceKey.substring(0, at));
    }

    /** Released by this decision: MERGED before it, RELEASED after it. */
    static boolean released(Form16Certificate f) {
        return f.status() == Status.MERGED || f.status() == Status.RELEASED;
    }

    @Override
    public Optional<Rendered> render(long employeeId, String periodKey, String sourceKey) {
        Form16Service.CertificatePdf pdf;
        try {
            pdf = form16.certificatePdf(member(employeeId), null, certificateOf(sourceKey));
        } catch (FciException e) {
            if (e.getErrorCode() == CommonErrorCode.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
        String name = pdf.fileName().endsWith(".pdf") ? pdf.fileName() : pdf.fileName() + ".pdf";
        return Optional.of(
                new Rendered(MailAttachment.pdf(name, pdf.pdf()), Map.of("year", periodKey)));
    }

    /** The member's own read (E-01 SELF), as the member's own download is. */
    static AccessContext member(long employeeId) {
        return new AccessContext(
                null,
                DELIVERY_LOGIN,
                null,
                null,
                null,
                null,
                employeeId,
                List.of(),
                Map.of(),
                Set.of(AccessContext.key(Form16Service.MEMBER_SCREEN, "SELF")),
                Set.of(),
                Set.of());
    }
}
