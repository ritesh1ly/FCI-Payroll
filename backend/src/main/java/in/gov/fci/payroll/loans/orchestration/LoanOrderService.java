package in.gov.fci.payroll.loans.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "Order copy - attach &amp; store" card of I-04 (SRS-G-30, SRS-12-18, wireframe orderCard;
 * API-09-1): the orders of a loan are those stored with it and the order its sanction went to the
 * Checker with. An order is stored only with a loan set up on I-04: an employee loan's documents
 * belong to HRMS and its I-21 task (R-SRC, DDL loan_header). A document never changes a term of the
 * loan, so it needs no approval; the store keeps the file and the audit names the actor.
 */
@Service
@Loggable
public class LoanOrderService {

    /** The owner of an order stored after the loan exists. */
    static final String ENTITY = "loan_header";

    private final LoanQueryService loans;
    private final DocumentStore documents;
    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final AuditService audit;

    public LoanOrderService(
            LoanQueryService loans,
            DocumentStore documents,
            JdbcTemplate jdbc,
            Authorization policy,
            AuditService audit) {
        this.loans = loans;
        this.documents = documents;
        this.jdbc = jdbc;
        this.policy = policy;
        this.audit = audit;
    }

    /** The orders of a loan in the user's scope, the sanction's first. */
    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> list(AccessContext context, long loanId) {
        loans.detail(context, loanId);
        List<DocumentStore.AttachmentView> orders = new ArrayList<>();
        String sanction = sanctionKey(loanId);
        if (sanction != null) {
            orders.addAll(documents.active(LoanSanctionService.ENTITY, sanction));
        }
        orders.addAll(documents.active(ENTITY, String.valueOf(loanId)));
        return orders;
    }

    /** One order of the loan (the read is logged by the store). */
    @Transactional
    public DocumentStore.Content file(
            AccessContext context, String sourceIp, long loanId, long attachmentId) {
        DocumentStore.AttachmentView order =
                list(context, loanId).stream()
                        .filter(a -> Objects.equals(a.id(), attachmentId))
                        .findFirst()
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        return documents.read(
                context.loginId(), sourceIp, order.entityName(), order.entityKey(), attachmentId);
    }

    /** SRS-G-30: stores an order with a loan set up on I-04, with its particulars. */
    @Transactional
    public DocumentStore.AttachmentView attach(
            AccessContext context, long loanId, LoanChangeService.Document order) {
        LoanQueryService.LoanRow loan = loans.detail(context, loanId).loan();
        policy.require(context, LoanSanctionService.SCREEN, "LOAN_SANCTION");
        if ("HRMS".equals(loan.source())) {
            throw new FciException(
                    ApiErrorCode.LOAN_SANCTION, new Object[] {"HRMS_LOAN"}, List.of(), null);
        }
        DocumentStore.AttachmentView stored =
                documents.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                ENTITY,
                                String.valueOf(loanId),
                                order.documentType(),
                                order.reference(),
                                order.orderDate(),
                                order.issuingAuthority(),
                                order.fileName(),
                                order.content(),
                                true));
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                        ENTITY,
                        loan.loanNumber(),
                        "order_copy",
                        null,
                        stored.fileName(),
                        "Order copy stored on I-04 (SRS-G-30)"));
        return stored;
    }

    /** The approval key the sanction's order was stored under; null for a loan from HRMS. */
    private String sanctionKey(long loanId) {
        return jdbc
                .queryForList(
                        "SELECT q.entity_key FROM loan_header h JOIN approval_queue q ON"
                                + " q.approval_id = h.approval_id WHERE h.loan_id = ? AND"
                                + " q.entity_name = ?",
                        String.class,
                        loanId,
                        LoanSanctionService.ENTITY)
                .stream()
                .findFirst()
                .orElse(null);
    }
}
