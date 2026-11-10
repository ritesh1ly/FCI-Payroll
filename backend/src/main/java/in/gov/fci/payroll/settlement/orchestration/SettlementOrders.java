package in.gov.fci.payroll.settlement.orchestration;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.settlement.business.SettlementRules.Stage;
import in.gov.fci.payroll.settlement.orchestration.SettlementWork.Case;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The order copies of a case (SRS-G-30, SRS-G-31; SRS-G-32 l.546: O-05, the ex-gratia sanction
 * letter or succession document; pack orderCard, A-41; PENDING API-11-2): stored against the case,
 * read within the session's offices. The Payroll Maker attaches them (O-05 COMPUTE, pack rbac.c).
 * Once the case is posted its records are read-only (SRS-10-29): no new order.
 */
@Service
@Loggable
public class SettlementOrders {

    static final String ENTITY = "settlement_case";

    private final Authorization policy;
    private final SettlementWork work;
    private final SettlementViews views;
    private final DocumentStore documents;

    public SettlementOrders(
            Authorization policy,
            SettlementWork work,
            SettlementViews views,
            DocumentStore documents) {
        this.policy = policy;
        this.work = work;
        this.views = views;
        this.documents = documents;
    }

    /** The active orders of a case in the session's offices (else not found). */
    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> orders(AccessContext context, long caseId) {
        return documents.active(ENTITY, views.reference(context, caseId));
    }

    /** One order of the case; an attachment of another record is not found (SRS-G-31). */
    @Transactional
    public DocumentStore.Content file(
            AccessContext context, String sourceIp, long caseId, long attachmentId) {
        return documents.read(
                context.loginId(),
                sourceIp,
                ENTITY,
                views.reference(context, caseId),
                attachmentId);
    }

    /** Stores an order with the case; refused from Posted on (SRS-10-29). */
    @Transactional
    public DocumentStore.AttachmentView attach(
            AccessContext context,
            long caseId,
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {
        Case c = work.load(caseId);
        policy.requireEmployeeRecord(
                context, SettlementCaseOpener.SCREEN, "COMPUTE", c.employeeId(), c.locationId());
        if (c.stage().number() >= Stage.POSTED.number()) {
            throw SettlementWork.stage(c, Stage.APPROVED);
        }
        return documents.attach(
                context.loginId(),
                new DocumentStore.NewAttachment(
                        ENTITY,
                        c.reference(),
                        documentType,
                        reference,
                        orderDate,
                        issuingAuthority,
                        fileName,
                        content,
                        true));
    }
}
