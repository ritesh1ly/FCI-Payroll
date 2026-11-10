package in.gov.fci.payroll.loans.orchestration;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.loans.business.LoanRules;
import in.gov.fci.payroll.loans.business.LoanRules.Terms;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Foreclosure and reschedule of a loan set up on I-04 (plan 09 phase 09e; SRS-12-4, SRS-12-16,
 * SRS-12-21, SRS-12-22, SRS-12-18): the Payroll Maker attaches the payment receipt (a foreclosure)
 * or the employee's signed request (a reschedule) and the Payroll Checker approves, a foreclosure
 * with the LOAN_FORECLOSURE OTP (routes I04_LOAN_FORECLOSE / I04_LOAN_RESCHEDULE). An employee loan
 * received from HRMS is changed only through HRMS (I-21; R-SRC), and a loan with a change already
 * waiting cannot take another.
 *
 * <p>US-GAP-202 (SRS-12-18, I-04 "Sanction / Foreclosure / Reschedule Order", SRS-G-30): every
 * foreclosure and reschedule also carries its order (the order copy with its reference, date and
 * issuing office) beside the payment receipt or the signed request; without it the submission is
 * blocked (ORDER_MISSING, audited) and the Checker checks the entry against both.
 */
@Service
@Loggable
public class LoanChangeService {

    /** The approval entity of a change (key: loan id | kind | token). */
    public static final String ENTITY = "loan_change";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** The document attached (receipt or signed request) with its particulars. */
    public record Document(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    /** A reschedule's terms on the remaining balance. */
    public record NewSchedule(
            int principalInstalments,
            BigDecimal instalment,
            int interestInstalments,
            BigDecimal interestInstalment,
            String startYymm) {}

    private final JdbcTemplate jdbc;
    private final LoanChanges changes;
    private final OrgDirectory org;
    private final PayrollRecords payroll;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final DocumentStore documents;
    private final ObjectMapper json;
    private final in.gov.fci.payroll.audit.api.AuditService audit;
    private final Clock clock;

    public LoanChangeService(
            JdbcTemplate jdbc,
            LoanChanges changes,
            OrgDirectory org,
            PayrollRecords payroll,
            Authorization policy,
            MakerChecker approvals,
            DocumentStore documents,
            ObjectMapper json,
            Clock clock,
            in.gov.fci.payroll.audit.api.AuditService audit) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.changes = changes;
        this.org = org;
        this.payroll = payroll;
        this.policy = policy;
        this.approvals = approvals;
        this.documents = documents;
        this.json = json;
        this.clock = clock;
    }

    /**
     * SRS-12-21 / SRS-12-18: the foreclosure, with its order and payment receipt, to the Checker
     * (OTP at approval).
     */
    @Transactional
    public SubmittedApproval foreclose(
            AccessContext context, long loanId, Document order, Document receipt) {
        Map<String, Object> loan = changeable(context, loanId, "LOAN_FORECLOSE");
        BigDecimal amount =
                LoanRules.foreclosureAmount(
                        (BigDecimal) loan.get("PRINCIPAL_OUTSTANDING"),
                        (BigDecimal) loan.get("INTEREST_OUTSTANDING"));
        return submit(
                context,
                loan,
                "FORECLOSURE",
                "LOAN_FORECLOSE",
                order,
                receipt,
                Map.of(),
                "Foreclosure of " + loan.get("LOAN_NUMBER") + ": " + amount.toPlainString(),
                amount);
    }

    /**
     * SRS-12-22 / SRS-12-4 / SRS-12-18: the reschedule on the remaining balance, with its order and
     * the signed request.
     */
    @Transactional
    public SubmittedApproval reschedule(
            AccessContext context,
            long loanId,
            NewSchedule s,
            Document order,
            Document signedRequest) {
        Map<String, Object> loan = changeable(context, loanId, "LOAN_RESCHEDULE");
        Terms t =
                LoanChanges.remaining(
                        loan,
                        s.principalInstalments(),
                        s.instalment(),
                        s.interestInstalments(),
                        s.interestInstalment(),
                        s.startYymm());
        LoanRules.defect(t)
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        YearMonth start = LoanRules.month(s.startYymm()).orElseThrow();
        if (start.isBefore(openPeriod(((Number) loan.get("EMPLOYEE_ID")).longValue()))) {
            throw invalid("START_BEFORE_OPEN_PERIOD");
        }
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("principalInstalments", s.principalInstalments());
        terms.put("instalment", s.instalment());
        terms.put("interestInstalments", s.interestInstalments());
        terms.put("interestInstalment", s.interestInstalment());
        terms.put("startYymm", s.startYymm());
        return submit(
                context,
                loan,
                "RESCHEDULE",
                "LOAN_RESCHEDULE",
                order,
                signedRequest,
                terms,
                "Reschedule of "
                        + loan.get("LOAN_NUMBER")
                        + ": "
                        + s.principalInstalments()
                        + " x "
                        + s.instalment().toPlainString()
                        + " from "
                        + s.startYymm(),
                null);
    }

    // ---------------------------------------------------------------- steps

    private Map<String, Object> changeable(AccessContext context, long loanId, String action) {
        Map<String, Object> loan = changes.openLoan(loanId);
        if (loan == null) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        long employeeId = ((Number) loan.get("EMPLOYEE_ID")).longValue();
        Long location =
                jdbc.queryForObject(
                        "SELECT location_id FROM employee WHERE employee_id = ?",
                        Long.class,
                        employeeId);
        policy.requireEmployeeRecord(
                context, LoanSanctionService.SCREEN, action, employeeId, location);
        if ("HRMS".equals(loan.get("LOAN_SOURCE"))) {
            throw invalid("HRMS_LOAN");
        }
        Long pending =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM approval_queue WHERE entity_name = ? AND entity_key"
                            + " LIKE ? AND status IN ('PENDING_APPROVAL', 'PENDING_ATTACHMENT')",
                        Long.class,
                        ENTITY,
                        loanId + "|%");
        if (RowCounts.of(pending) > 0) {
            throw new FciException(
                    ApiErrorCode.LOAN_OPEN_REQUEST,
                    new Object[] {"change", loan.get("LOAN_NUMBER")},
                    List.of(),
                    null);
        }
        return loan;
    }

    private SubmittedApproval submit(
            AccessContext context,
            Map<String, Object> loan,
            String kind,
            String action,
            Document order,
            Document doc,
            Map<String, Object> terms,
            String summary,
            BigDecimal impact) {
        if (missing(doc)) {
            // SRS-20-27: the blocked submission is audited.
            audit.submissionBlocked(
                    context.loginId(),
                    ENTITY,
                    String.valueOf(loan.get("LOAN_ID")),
                    LoanSanctionService.SCREEN,
                    "FORECLOSURE".equals(kind) ? "payment receipt" : "signed request");
            throw invalid(
                    "FORECLOSURE".equals(kind) ? "RECEIPT_MISSING" : "SIGNED_REQUEST_MISSING");
        }
        if (missing(order)) {
            // US-GAP-202 (SRS-12-18): the order is required on every foreclosure and reschedule.
            audit.submissionBlocked(
                    context.loginId(),
                    ENTITY,
                    String.valueOf(loan.get("LOAN_ID")),
                    LoanSanctionService.SCREEN,
                    "FORECLOSURE".equals(kind) ? "foreclosure order" : "reschedule order");
            throw invalid("ORDER_MISSING");
        }
        if (order.documentType() == null
                || order.documentType().isBlank()
                || order.orderDate() == null) {
            // Wave D review 4 (SRS-G-30): an order names its document type and the date it was
            // issued; without a type the store would take it as a system file and skip its checks.
            audit.submissionBlocked(
                    context.loginId(),
                    ENTITY,
                    String.valueOf(loan.get("LOAN_ID")),
                    LoanSanctionService.SCREEN,
                    "order particulars (document type, issued on)");
            throw invalid("ORDER_PARTICULARS");
        }
        long loanId = ((Number) loan.get("LOAN_ID")).longValue();
        String key = loanId + "|" + kind + "|" + UUID.randomUUID().toString().substring(0, 8);
        long orderId = attach(context, key, order);
        long attachedId = attach(context, key, doc);
        Map<String, Object> payload = new LinkedHashMap<>(terms);
        payload.put("loanId", loanId);
        payload.put("kind", kind);
        payload.put("attachmentId", attachedId);
        payload.put("orderAttachmentId", orderId);
        long employeeId = ((Number) loan.get("EMPLOYEE_ID")).longValue();
        Long location =
                jdbc.queryForObject(
                        "SELECT location_id FROM employee WHERE employee_id = ?",
                        Long.class,
                        employeeId);
        long unit =
                org.cycleUnitOf(location)
                        .orElseThrow(() -> new FciException(ApiErrorCode.CYCLE_NO_UNIT))
                        .getId();
        Submission submission =
                new Submission(
                        LoanSanctionService.SCREEN,
                        action,
                        ENTITY,
                        key,
                        ActionType.UPDATE,
                        unit,
                        json.writeValueAsString(payload),
                        LocalDate.now(clock.withZone(IST)),
                        summary);
        return approvals.submit(
                context,
                impact == null ? submission : submission.withImpact(impact),
                (OtpProof) null);
    }

    private static boolean missing(Document d) {
        return d == null || d.content() == null || d.content().length == 0;
    }

    /** Attaches one document to the change's approval item (SRS-G-30 particulars). */
    private long attach(AccessContext context, String key, Document doc) {
        String provisional = DocumentStore.PROVISIONAL + UUID.randomUUID();
        DocumentStore.AttachmentView attached =
                documents.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                ENTITY,
                                provisional,
                                doc.documentType(),
                                doc.reference(),
                                doc.orderDate(),
                                doc.issuingAuthority(),
                                doc.fileName(),
                                doc.content(),
                                true));
        documents.adopt(context.loginId(), ENTITY, provisional, attached.id(), key);
        return attached.id();
    }

    private YearMonth openPeriod(long employeeId) {
        YearMonth current = YearMonth.now(clock.withZone(IST));
        Long location =
                jdbc.queryForObject(
                        "SELECT location_id FROM employee WHERE employee_id = ?",
                        Long.class,
                        employeeId);
        String yymm = String.format("%04d%02d", current.getYear(), current.getMonthValue());
        boolean locked =
                location != null
                        && org.cycleUnitOf(location)
                                .flatMap(u -> payroll.cycle(u.getId(), yymm))
                                .filter(PayrollRecords.Cycle::isLocked)
                                .isPresent();
        return locked ? current.plusMonths(1) : current;
    }

    private static FciException invalid(String defect) {
        return new FciException(ApiErrorCode.LOAN_SANCTION, new Object[] {defect}, List.of(), null);
    }
}
