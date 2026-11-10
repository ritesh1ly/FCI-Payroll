package in.gov.fci.payroll.leave.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.leave.business.LeaveErrorCode;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the I-24 screen shows around its records as Wireframes v6.1 draws it (OD-121; SRS-8-24,
 * SRS-1-43, SRS-G-37, SRS-G-30, BR-ARR-LE-004, BR-AUDIT-009, FRS §6.4.3): the tile "Records for
 * this order", the Cancel of the invoice card and the "Order copy - attach &amp; store" card. The
 * SRS gives no contract for these (PENDING API-13-2). A cancellation goes through maker-checker
 * (route {@value #ACTION}, SRS-G-1): the Maker raises it with a reason, the Payroll Checker
 * approves it, and the record is then marked CANCELLED, never deleted, its old state in the audit
 * trail.
 */
@Service
@Loggable
public class LeaveArrearDeskService {

    public static final String ACTION = "I24_CANCEL";
    static final String ENTITY = "leave_encashment_arrear";
    private static final String SCREEN = LeaveArrearService.SCREEN;

    /**
     * SRS-G-37: free until the invoice is sent; a record with the Checker waits for the Checker.
     */
    private static final Set<String> CANCELLABLE = Set.of("CALCULATED", "APPROVED");

    /** An order copy is attached while the record is still the Maker's. */
    private static final String ATTACHABLE = "CALCULATED";

    private static final String RECORD =
            "SELECT a.le_status, a.record_number, a.arrear_amount FROM leave_encashment_arrear a"
                    + " JOIN employee m ON m.employee_id = a.employee_id WHERE a.le_arrear_id = :id"
                    + " AND (:all = 'Y' OR m.location_id IN (:locations))";

    /** The tile: the latest order of the session's offices and its records not cancelled. */
    public record Summary(String order, long records) {}

    /** The particulars of one order copy (the drawn card). */
    public record NewOrder(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingOffice,
            String fileName,
            byte[] content) {}

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final AuditService audit;
    private final DocumentStore documents;

    public LeaveArrearDeskService(
            NamedParameterJdbcTemplate named,
            Authorization policy,
            MakerChecker approvals,
            AuditService audit,
            DocumentStore documents) {
        this.named = named;
        this.policy = policy;
        this.approvals = approvals;
        this.audit = audit;
        this.documents = documents;
    }

    /** The tile "Records for this order": the order last revised in the session's offices. */
    @Transactional(readOnly = true)
    public Summary summary(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        List<Summary> rows =
                named.query(
                        "SELECT c.circular_number, COUNT(*) FROM leave_encashment_arrear a JOIN"
                            + " employee m ON m.employee_id = a.employee_id LEFT JOIN"
                            + " circular_order c ON c.circular_order_id = a.circular_order_id WHERE"
                            + " (:all = 'Y' OR m.location_id IN (:locations)) AND a.le_status <>"
                            + " 'CANCELLED' GROUP BY c.circular_number ORDER BY"
                            + " MAX(a.revision_effective_date) DESC, MAX(a.le_arrear_id) DESC FETCH"
                            + " FIRST 1 ROWS ONLY",
                        scope(context),
                        (rs, i) -> new Summary(rs.getString(1), rs.getLong(2)));
        return rows.isEmpty() ? new Summary(null, 0) : rows.getFirst();
    }

    /**
     * SRS-G-37, BR-AUDIT-009: the Maker raises the cancellation of a record whose invoice has not
     * gone to APPS; a record already with the Checker, invoiced or paid is refused, and so is a
     * second cancellation while the first is open.
     */
    @Transactional
    public SubmittedApproval cancel(AccessContext context, long id, String reason) {
        policy.require(context, SCREEN, "SUBMIT");
        if (reason == null || reason.isBlank()) {
            throw new FciException(
                    LeaveErrorCode.ARREAR_SELECTION, new Object[] {"a reason"}, List.of(), null);
        }
        Map<String, Object> r = record(context, id);
        String status = (String) r.get("LE_STATUS");
        String number = (String) r.get("RECORD_NUMBER");
        Long open =
                named.queryForObject(
                        "SELECT COUNT(*) FROM approval_queue WHERE entity_name = :entity AND"
                                + " entity_key = :key AND status = 'PENDING_APPROVAL'",
                        new MapSqlParameterSource("entity", ENTITY)
                                .addValue("key", String.valueOf(id)),
                        Long.class);
        if (!CANCELLABLE.contains(status) || (open != null && open > 0)) {
            throw new FciException(
                    LeaveErrorCode.ARREAR_STATE, new Object[] {number, status}, List.of(), null);
        }
        BigDecimal impact = (BigDecimal) r.get("ARREAR_AMOUNT");
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                        ACTION,
                                        SCREEN,
                                        "SUBMIT",
                                        ENTITY,
                                        String.valueOf(id),
                                        ActionType.UPDATE,
                                        context.locationId(),
                                        null,
                                        LocalDate.now(ZoneId.of("Asia/Kolkata")),
                                        reason.strip())
                                .withImpact(impact),
                        (OtpProof) null);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY, number, "cancellation", null, "REQUESTED", reason.strip())
                        .approval(item.id())
                        .module(SCREEN));
        return item;
    }

    /** SRS-G-37: approved, the record is cancelled (a status, never a delete); else unchanged. */
    @EventListener
    public void on(ApprovalDecided d) {
        if (!d.concerns(ENTITY) || !d.approved()) {
            return;
        }
        named.update(
                "UPDATE leave_encashment_arrear SET le_status = 'CANCELLED', ineligible_reason ="
                        + " :reason, approval_id = :approval, row_version = row_version + 1 WHERE"
                        + " le_arrear_id = :id AND le_status IN ('CALCULATED', 'APPROVED')",
                new MapSqlParameterSource("id", Long.valueOf(d.entityKey()))
                        .addValue("approval", d.approvalId())
                        .addValue("reason", "Cancelled through approval " + d.approvalId()));
        audit.record(
                d.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                d.entityKey(),
                                "le_status",
                                null,
                                "CANCELLED",
                                "Cancellation approved")
                        .approval(d.approvalId())
                        .module(SCREEN));
    }

    /** FRS §6.4.3, A-41: the Maker stores an order copy with a record still to be submitted. */
    @Transactional
    public DocumentStore.AttachmentView attachOrder(AccessContext context, long id, NewOrder o) {
        policy.require(context, SCREEN, "SUBMIT");
        Map<String, Object> r = record(context, id);
        if (!ATTACHABLE.equals(r.get("LE_STATUS"))) {
            throw new FciException(
                    LeaveErrorCode.ARREAR_STATE,
                    new Object[] {r.get("RECORD_NUMBER"), r.get("LE_STATUS")},
                    List.of(),
                    null);
        }
        return documents.attach(
                context.loginId(),
                new DocumentStore.NewAttachment(
                        ENTITY,
                        String.valueOf(id),
                        o.documentType(),
                        o.reference(),
                        o.orderDate(),
                        o.issuingOffice(),
                        o.fileName(),
                        o.content(),
                        false));
    }

    /** The orders stored with the record, oldest first (the drawn "Stored order" table). */
    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> orders(AccessContext context, long id) {
        policy.require(context, SCREEN, "VIEW");
        record(context, id);
        return documents.active(ENTITY, String.valueOf(id));
    }

    /** One stored order (read-only; the access is logged by the document module). */
    @Transactional
    public DocumentStore.Content order(
            AccessContext context, String sourceIp, long id, long attachmentId) {
        policy.require(context, SCREEN, "VIEW");
        record(context, id);
        return documents.read(
                context.loginId(), sourceIp, ENTITY, String.valueOf(id), attachmentId);
    }

    /** A record outside the session's offices reads as not found (no existence leak, R-RBAC). */
    private Map<String, Object> record(AccessContext context, long id) {
        List<Map<String, Object>> rows =
                named.queryForList(RECORD, scope(context).addValue("id", id));
        if (rows.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return rows.getFirst();
    }

    private MapSqlParameterSource scope(AccessContext context) {
        return OfficeScope.of(context, policy.isAtHeadquarters(context));
    }
}
