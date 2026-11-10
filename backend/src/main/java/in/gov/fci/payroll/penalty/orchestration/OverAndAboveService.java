package in.gov.fci.payroll.penalty.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.penalty.business.PenaltyErrorCode;
import in.gov.fci.payroll.penalty.business.PenaltyRules;
import in.gov.fci.payroll.penalty.business.model.OverAndAbovePayment;
import in.gov.fci.payroll.penalty.persistence.PenaltyRepositories;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.InputPatterns;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * I-06 over-and-above payments (plan 12 phase 12f; SRS-14-2, SRS-14-5, SRS-14-14, SRS-14-20,
 * BR-PEN2-002; D12-11): the Payroll Maker records the amount, the pay component, the reason and the
 * sanction reference (the sanction date, where given, later than the application date); the Payroll
 * Checker approves before posting (route I06_OVER_ABOVE), the approving authority and the sanction
 * reference recorded with the approval. Tax follows the component's taxable flag (SRS-14-5). It is
 * paid through Supplementary Payment I-23 (BR-CYC-010); the link from I-23 to the approved record
 * is PENDING 12-oaa-post. A payment is cancelled under maker-checker until its invoice is sent to
 * APPS (SRS-G-37: invoice status from module 23).
 *
 * <p>Use case alternate flow 4 (SRS l.2998, US-GAP-230): a payment the Payroll Checker returns or
 * rejects goes back to the Payroll Maker with the reason - RECORDED (returned) or REJECTED, the
 * Checker's reason listed with the payment on I-06 ({@link PaymentView#checkerReason}) and audited
 * with the decision.
 */
@Service
@Loggable
public class OverAndAboveService {

    /** The approval route of an over-and-above payment and of its cancellation. */
    static final String OVER_ABOVE_ROUTE = "I06_OVER_ABOVE";

    static final String ENTITY = "over_and_above_payment";
    static final String CANCEL = "over_above_cancel";
    private static final Pattern PRINTABLE = Pattern.compile("^[\\x20-\\x7E]*$");
    private static final Set<String> PAYABLE = Set.of("IN_SERVICE", "ON_DEPUTATION");

    /** What the Maker enters. */
    public record Request(
            String eis,
            int payCode,
            BigDecimal amount,
            String payrollYymm,
            String reason,
            LocalDate applicationDate,
            String sanctionReference,
            LocalDate sanctionDate) {}

    /**
     * A payment as I-06 lists it.
     *
     * @param checkerReason the Payroll Checker's reason when the payment was returned (RECORDED) or
     *     rejected (REJECTED), else null (US-GAP-230)
     */
    public record PaymentView(
            long id,
            String eis,
            Integer payCode,
            String payName,
            String payrollYymm,
            BigDecimal amount,
            boolean taxable,
            LocalDate applicationDate,
            String sanctionReference,
            LocalDate sanctionDate,
            String status,
            String invoiceStatus,
            String checkerReason,
            // SRS l.3030 (SRS-G-37): when the invoice went to APPS; null until sent.
            java.time.Instant invoiceSentAt,
            // The drawn invoice card: the invoice number and the APPS cancellation.
            Long invoiceNo,
            String appsCancelStatus,
            String employee) {}

    private final PenaltyRepositories.Payments payments;
    private final EmployeeDirectory employees;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public OverAndAboveService(
            PenaltyRepositories.Payments payments,
            EmployeeDirectory employees,
            Authorization policy,
            MakerChecker approvals,
            AuditService audit,
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate named,
            ObjectMapper json,
            Clock clock) {
        this.payments = payments;
        this.employees = employees;
        this.policy = policy;
        this.approvals = approvals;
        this.audit = audit;
        this.jdbc = jdbc;
        this.named = named;
        this.json = json;
        this.clock = clock;
    }

    /** SRS-14-2: the payment is checked and sent to the Payroll Checker. */
    @Transactional
    public SubmittedApproval submit(AccessContext context, Request r) {
        if (r == null) {
            throw invalid("the payment is missing");
        }
        EmployeeDirectory.Member member =
                employees
                        .memberByEis(r.eis() == null ? null : r.eis().strip())
                        .orElseThrow(() -> invalid("no such member"));
        policy.requireEmployeeRecord(
                context,
                PenaltyOrderService.SCREEN,
                "OVER_ABOVE",
                member.id(),
                member.locationId());
        if (!PAYABLE.contains(member.employmentStatus())) {
            throw invalid("the member is not in service");
        }
        if (r.amount() == null
                || r.amount().signum() <= 0
                || r.amount().stripTrailingZeros().scale() > 2) {
            throw invalid("the amount is above zero, in rupees and paise");
        }
        if (PenaltyRules.month(r.payrollYymm()).isEmpty()) {
            throw invalid("the payroll month is YYYYMM");
        }
        text(r.reason(), 500, "the reason", true);
        text(r.sanctionReference(), 60, "the sanction reference");
        if (r.sanctionDate() != null
                && r.applicationDate() != null
                && !r.sanctionDate().isAfter(r.applicationDate())) {
            // BR-PEN2-002, chk_over_above_sanction_dt.
            throw invalid("the sanction date is later than the application date");
        }
        Map<String, Object> component =
                jdbc
                        .queryForList(
                                "SELECT component_id, taxable_flag FROM pay_component WHERE"
                                        + " legacy_pay_code = ? AND component_type = 'EARNING'"
                                        + " AND effective_from <= ? AND (effective_to IS NULL OR"
                                        + " effective_to >= ?)",
                                r.payCode(),
                                java.sql.Date.valueOf(LocalDate.now(clock)),
                                java.sql.Date.valueOf(LocalDate.now(clock)))
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> invalid("choose an earning pay component in force"));
        OverAndAbovePayment p =
                payments.save(
                        OverAndAbovePayment.submitted(
                                member.id(),
                                ((Number) component.get("COMPONENT_ID")).longValue(),
                                !"N".equals(component.get("TAXABLE_FLAG")),
                                r.payrollYymm(),
                                r.amount(),
                                r.applicationDate(),
                                r.sanctionReference().strip(),
                                r.sanctionDate()));
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                        PenaltyOrderService.SCREEN,
                                        "OVER_ABOVE",
                                        ENTITY,
                                        String.valueOf(p.getId()),
                                        ActionType.CREATE,
                                        context.locationId(),
                                        json.writeValueAsString(
                                                Map.of(
                                                        "eis", member.eis(),
                                                        "payCode", r.payCode(),
                                                        "amount", r.amount(),
                                                        "sanctionReference",
                                                                p.getSanctionReference())),
                                        PenaltyRules.month(r.payrollYymm()).orElseThrow().atDay(1),
                                        "Over-and-above "
                                                + r.amount().toPlainString()
                                                + " for "
                                                + member.eis()
                                                + " (sanction "
                                                + p.getSanctionReference()
                                                + "): "
                                                + r.reason().strip())
                                .withImpact(r.amount()),
                        (OtpProof) null);
        p.approval(item.id());
        payments.save(p);
        return item;
    }

    /** SRS-14-20, SRS-G-37: a cancellation until the invoice is sent to APPS. */
    @Transactional
    public SubmittedApproval cancel(AccessContext context, long id, String reason) {
        OverAndAbovePayment p = inScope(context, id, "CANCEL");
        text(reason, 1000, "the reason", true);
        if (p.status() == OverAndAbovePayment.Status.PAID
                || p.status() == OverAndAbovePayment.Status.CANCELLED
                || p.status() == OverAndAbovePayment.Status.REJECTED
                || !Set.of("NOT_RAISED", "RAISED").contains(p.getInvoiceStatus())) {
            throw invalid("the payment is " + p.status().name() + " and cannot be cancelled");
        }
        return approvals.submit(
                context,
                // The payment's own route (I06_OVER_ABOVE: Maker, Payroll Checker), not the
                // penalty order's I06_CANCEL, whose route asks for the order copy of a penalty
                // (SRS-14-2 names no document for an over-and-above payment or its cancellation).
                new Submission(
                                OVER_ABOVE_ROUTE,
                                PenaltyOrderService.SCREEN,
                                "CANCEL",
                                CANCEL,
                                String.valueOf(p.getId()),
                                ActionType.UPDATE,
                                context.locationId(),
                                json.writeValueAsString(Map.of("id", p.getId())),
                                LocalDate.now(clock),
                                "Cancellation of over-and-above "
                                        + p.getId()
                                        + ": "
                                        + reason.strip())
                        .withImpact(p.getAmount()),
                (OtpProof) null);
    }

    /** The unit's payments (Maker, Checker, Finance), newest first. */
    @Transactional(readOnly = true)
    public List<PaymentView> payments(AccessContext context) {
        policy.require(context, PenaltyOrderService.SCREEN, "VIEW");
        if (context.dataScope().isEmpty()) {
            return List.of();
        }
        // Deep profile 2026-10-27: the scope in the database, then one lookup per kind for the list
        // (members, pay codes), not two per payment.
        List<OverAndAbovePayment> rows = payments.inScope(context.dataScope());
        Map<Long, EmployeeDirectory.Member> people =
                employees.members(
                        rows.stream().map(OverAndAbovePayment::getEmployeeId).distinct().toList());
        Map<Long, PayCode> codes = new java.util.HashMap<>();
        rows.stream()
                .map(OverAndAbovePayment::getComponentId)
                .distinct()
                .forEach(id -> codes.put(id, payCode(id)));
        Map<Long, String> reasons = checkerReasons(rows);
        return rows.stream()
                .map(
                        p -> {
                            EmployeeDirectory.Member m = people.get(p.getEmployeeId());
                            return view(
                                    p,
                                    m == null ? "" : m.eis(),
                                    m == null ? "" : m.name(),
                                    codes.get(p.getComponentId()),
                                    reasons.get(p.getApprovalId()));
                        })
                .toList();
    }

    /**
     * US-GAP-230: the Checker's reason of each payment returned (back to RECORDED) or rejected, by
     * its approval, in one query (the id list split for Oracle by the application's named template,
     * InLists).
     */
    private Map<Long, String> checkerReasons(List<OverAndAbovePayment> rows) {
        List<Long> decided =
                rows.stream()
                        .filter(
                                p ->
                                        p.getApprovalId() != null
                                                && (p.status()
                                                                == OverAndAbovePayment.Status
                                                                        .RECORDED
                                                        || p.status()
                                                                == OverAndAbovePayment.Status
                                                                        .REJECTED))
                        .map(OverAndAbovePayment::getApprovalId)
                        .distinct()
                        .toList();
        Map<Long, String> reasons = new java.util.HashMap<>();
        if (decided.isEmpty()) {
            return reasons;
        }
        named.query(
                "SELECT approval_id, CASE WHEN status = 'RETURNED' THEN return_reason ELSE"
                        + " decision_reason END AS reason FROM approval_queue WHERE status IN"
                        + " ('RETURNED', 'REJECTED') AND approval_id IN (:ids)",
                new MapSqlParameterSource("ids", decided),
                rs -> {
                    reasons.put(rs.getLong(1), rs.getString(2));
                });
        return reasons;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (decision.concerns(ENTITY)) {
            OverAndAbovePayment p =
                    payments.findById(Long.parseLong(decision.entityKey())).orElseThrow();
            p.status(
                    decision.approved()
                            ? OverAndAbovePayment.Status.APPROVED
                            : decision.outcome() == ApprovalStatus.RETURNED
                                    ? OverAndAbovePayment.Status.RECORDED
                                    : OverAndAbovePayment.Status.REJECTED);
            payments.save(p);
            record(
                    p,
                    decision,
                    decision.approved()
                            ? "decided"
                            : "decided, back to the Maker: " + decision.reason());
        } else if (decision.concerns(CANCEL) && decision.approved()) {
            OverAndAbovePayment p =
                    payments.findById(Long.parseLong(decision.entityKey())).orElseThrow();
            p.status(OverAndAbovePayment.Status.CANCELLED);
            payments.save(p);
            record(p, decision, "cancelled: " + decision.reason());
        }
    }

    private void record(OverAndAbovePayment p, ApprovalDecided decision, String what) {
        // SRS-14-14: the approving authority and the sanction reference of every payment.
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(p.getId()),
                                "payment_status",
                                null,
                                p.status().name(),
                                "Over-and-above (sanction "
                                        + p.getSanctionReference()
                                        + ") "
                                        + what
                                        + " by "
                                        + decision.checkerLoginId())
                        .approval(decision.approvalId())
                        .module(PenaltyOrderService.SCREEN));
    }

    private OverAndAbovePayment inScope(AccessContext context, long id, String action) {
        OverAndAbovePayment p =
                payments.findById(id)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        EmployeeDirectory.Member m =
                employees
                        .member(p.getEmployeeId())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (!policy.canAt(context, PenaltyOrderService.SCREEN, action, m.locationId())) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return p;
    }

    /** A pay component by code and name (owner 2026-10-27: never a bare code on a screen). */
    private record PayCode(Integer code, String name) {}

    private PayCode payCode(Long componentId) {
        return jdbc
                .query(
                        "SELECT legacy_pay_code, component_name FROM pay_component WHERE"
                                + " component_id = ?",
                        (rs, i) -> new PayCode(rs.getInt(1), rs.getString(2)),
                        componentId)
                .stream()
                .findFirst()
                .orElse(new PayCode(null, null));
    }

    private PaymentView view(
            OverAndAbovePayment p, String eis, String name, PayCode code, String reason) {
        return new PaymentView(
                p.getId(),
                eis,
                code.code(),
                code.name(),
                p.getPayrollYymm(),
                p.getAmount(),
                p.isTaxable(),
                p.getApplicationDate(),
                p.getSanctionReference(),
                p.getSanctionDate(),
                p.status().name(),
                p.getInvoiceStatus(),
                reason,
                p.getInvoiceSentAt(),
                p.getApInvoiceId(),
                p.getAppsCancelStatus(),
                name);
    }

    private static void text(String v, int max, String what) {
        text(v, max, what, false);
    }

    /** A required text; {@code multiLine}: entered in a text area, line breaks kept (R-ASCII). */
    private static void text(String v, int max, String what, boolean multiLine) {
        String s = v == null ? "" : v.strip();
        boolean printable =
                multiLine ? InputPatterns.isPrintableText(s) : PRINTABLE.matcher(s).matches();
        if (s.isEmpty() || s.length() > max || !printable) {
            throw invalid(what + " is required, printable, at most " + max + " characters");
        }
    }

    private static FciException invalid(String why) {
        return new FciException(PenaltyErrorCode.ORDER, new Object[] {why}, List.of(), null);
    }
}
