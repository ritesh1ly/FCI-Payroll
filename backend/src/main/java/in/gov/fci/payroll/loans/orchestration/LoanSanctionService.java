package in.gov.fci.payroll.loans.orchestration;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * A loan or advance set up by the Payroll Maker on I-04 for the types not held in HRMS (plan 09
 * phase 09b; SRS-12-1, SRS-12-6, SRS-12-8, SRS-12-11 … 12-15, SRS-12-17, SRS-12-18; D09-4, D09-8,
 * D09-11). The request lives in its approval item until the Payroll Checker approves it against the
 * attached sanction order (route I04_LOAN_SANCTION); the loan is written on approval ({@link
 * LoanSanctionEffect}). Every ceiling and limit is a rule-engine value: an unset one refuses the
 * sanction as "not configured" (R-RULE).
 *
 * <p>OPEN-QUESTION (PENDING 09-no-room): SRS-12-14 says a schedule that breaches the deduction
 * ceiling is never activated and the sanction is never rejected only for it; when no room at all is
 * left this month the instalment cannot be reduced, so the submission is refused naming the ceiling
 * (D09-11) until FCI says how such a sanction waits.
 *
 * <p>OPEN-QUESTION (CLR-61, US-GAP-206): a reduced instalment whose extended tenure runs past the
 * member's superannuation is set up as SRS-12-14 states; the balance left at separation goes to
 * Full &amp; Final (SRS-12-5) until FCI says otherwise.
 */
@Service
@Loggable
public class LoanSanctionService {

    /** The screen of loans and advances. */
    public static final String SCREEN = "I-04";

    /** The approval entity of a sanction request (key: employee id | type | token). */
    public static final String ENTITY = "loan_request";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Members who may be sanctioned a loan: in service (also on deputation); use case precondition
     * l.2684, also for a sanction from HRMS (US-GAP-199).
     */
    static final Set<String> PAYABLE = Set.of("IN_SERVICE", "ON_DEPUTATION");

    /** The loan statuses that still carry an outstanding balance. */
    static final String OPEN_STATUSES = "('ACTIVE', 'RESCHEDULED', 'STOPPED')";

    /**
     * What the Maker enters from the sanction order.
     *
     * @param reviewedOpenRequest the Maker reviewed the open request of the same type (SRS-12-8)
     */
    public record Proposal(
            String eis,
            String loanType,
            BigDecimal sanctioned,
            int principalInstalments,
            BigDecimal instalment,
            BigDecimal firstInstalment,
            BigDecimal interestAmount,
            int interestInstalments,
            BigDecimal interestInstalment,
            String startYymm,
            boolean reviewedOpenRequest) {}

    /** The sanction order (SRS-12-18, SRS-G-30). */
    public record Order(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    /**
     * What the Maker sees before submitting (SRS-12-11, SRS-12-14).
     *
     * @param reduced the instalment was reduced and the tenure extended to fit the ceiling
     * @param openRequest the open request of the same type (its approval id), if any
     */
    public record Preview(
            String eis,
            String loanType,
            Terms terms,
            List<LoanRules.Line> schedule,
            BigDecimal outstanding,
            BigDecimal maxNewSanction,
            boolean reduced,
            Long openRequest) {}

    /** The loan type in force today. */
    record LoanType(
            long id, String code, boolean interest, String ceilingKey, Integer maxInstalments) {}

    private final JdbcTemplate jdbc;
    private final EmployeeDirectory employees;
    private final OrgDirectory org;
    private final PayrollRecords payroll;
    private final RuleEngine rules;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final DocumentStore documents;
    private final ObjectMapper json;
    private final in.gov.fci.payroll.audit.api.AuditService audit;
    private final Clock clock;

    public LoanSanctionService(
            JdbcTemplate jdbc,
            EmployeeDirectory employees,
            OrgDirectory org,
            PayrollRecords payroll,
            RuleEngine rules,
            Authorization policy,
            MakerChecker approvals,
            DocumentStore documents,
            ObjectMapper json,
            Clock clock,
            in.gov.fci.payroll.audit.api.AuditService audit) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.employees = employees;
        this.org = org;
        this.payroll = payroll;
        this.rules = rules;
        this.policy = policy;
        this.approvals = approvals;
        this.documents = documents;
        this.json = json;
        this.clock = clock;
    }

    /** I-04: every check of the sanction, the schedule it would run on and the ceilings' room. */
    @Transactional(readOnly = true)
    public Preview preview(AccessContext context, Proposal p) {
        return check(context, p).preview();
    }

    /**
     * SRS-12-17 / SRS-12-18: the sanction with its order goes to the Payroll Checker; an open
     * request of the same type must have been reviewed (SRS-12-8).
     */
    @Transactional
    public SubmittedApproval submit(AccessContext context, Proposal p, Order order) {
        if (order == null || order.content() == null || order.content().length == 0) {
            // SRS-20-27: the blocked submission is audited.
            audit.submissionBlocked(
                    context.loginId(),
                    ENTITY,
                    p == null || p.eis() == null ? "-" : p.eis(),
                    SCREEN,
                    "sanction order copy");
            throw invalid("ORDER_MISSING");
        }
        Checked c = check(context, p);
        if (c.preview().openRequest() != null && !p.reviewedOpenRequest()) {
            throw new FciException(
                    ApiErrorCode.LOAN_OPEN_REQUEST,
                    new Object[] {c.type().code(), "A" + c.preview().openRequest()},
                    List.of(),
                    null);
        }
        String key =
                c.member().id()
                        + "|"
                        + c.type().code()
                        + "|"
                        + UUID.randomUUID().toString().substring(0, 8);
        String provisional = DocumentStore.PROVISIONAL + UUID.randomUUID();
        DocumentStore.AttachmentView attached =
                documents.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                ENTITY,
                                provisional,
                                order.documentType(),
                                order.reference(),
                                order.orderDate(),
                                order.issuingAuthority(),
                                order.fileName(),
                                order.content(),
                                true));
        documents.adopt(context.loginId(), ENTITY, provisional, attached.id(), key);
        Terms t = c.preview().terms();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("employeeId", c.member().id());
        payload.put("eis", c.member().eis());
        payload.put("loanTypeId", c.type().id());
        payload.put("loanType", c.type().code());
        payload.put("sanctioned", t.sanctioned());
        payload.put("principalInstalments", t.principalInstalments());
        payload.put("instalment", t.instalment());
        payload.put("firstInstalment", t.firstInstalment());
        payload.put("interestAmount", t.interestAmount());
        payload.put("interestInstalments", t.interestInstalments());
        payload.put("interestInstalment", t.interestInstalment());
        payload.put("startYymm", t.startYymm());
        payload.put("reduced", c.preview().reduced());
        payload.put("orderAttachmentId", attached.id());
        payload.put("reviewedOpenRequest", c.preview().openRequest());
        return approvals.submit(
                context,
                new Submission(
                                SCREEN,
                                "LOAN_SANCTION",
                                ENTITY,
                                key,
                                ActionType.CREATE,
                                c.unit(),
                                json.writeValueAsString(payload),
                                YearMonth.from(LoanRules.month(t.startYymm()).orElseThrow())
                                        .atDay(1),
                                c.type().code()
                                        + " of "
                                        + t.sanctioned().toPlainString()
                                        + " for "
                                        + c.member().eis()
                                        + " in "
                                        + t.principalInstalments()
                                        + " instalments from "
                                        + t.startYymm()
                                        + (c.preview().reduced()
                                                ? " (instalment reduced to fit the ceiling)"
                                                : ""))
                        .withImpact(t.sanctioned()),
                (OtpProof) null);
    }

    // ---------------------------------------------------------------- the checks

    private record Checked(
            EmployeeDirectory.Member member, LoanType type, long unit, Preview preview) {}

    private Checked check(AccessContext context, Proposal p) {
        EmployeeDirectory.Member member =
                employees
                        .memberByEis(p.eis() == null ? null : p.eis().strip())
                        .orElseThrow(() -> invalid("UNKNOWN_EMPLOYEE"));
        policy.requireEmployeeRecord(
                context, SCREEN, "LOAN_SANCTION", member.id(), member.locationId());
        if (!PAYABLE.contains(member.employmentStatus())) {
            throw invalid("NOT_PAYABLE");
        }
        LocalDate today = LocalDate.now(clock.withZone(IST));
        LoanType type = type(p.loanType(), today);
        Terms asked =
                new Terms(
                        p.sanctioned(),
                        p.principalInstalments(),
                        p.instalment(),
                        p.firstInstalment(),
                        p.interestAmount(),
                        p.interestInstalments(),
                        p.interestInstalment(),
                        p.startYymm());
        LoanRules.defect(asked)
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        LoanRules.interestDefect(type.interest(), asked)
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        long unit =
                org.cycleUnitOf(member.locationId())
                        .orElseThrow(() -> new FciException(ApiErrorCode.CYCLE_NO_UNIT))
                        .getId();
        YearMonth open = openPeriod(unit, YearMonth.from(today));
        if (LoanRules.month(asked.startYymm()).orElseThrow().isBefore(open)) {
            throw invalid("START_BEFORE_OPEN_PERIOD");
        }
        if (type.maxInstalments() == null) {
            throw notConfigured("the instalment limit of " + type.code());
        }
        if (asked.principalInstalments() > type.maxInstalments()) {
            throw ceiling("more than " + type.maxInstalments() + " instalments for " + type.code());
        }
        RuleEngine.Context ctx = new RuleEngine.Context("STAFF", member.locationId());
        // SRS-12-12: the type's sanction and outstanding ceiling, and its concurrent loans.
        if (type.ceilingKey() == null) {
            throw notConfigured("the ceiling of " + type.code());
        }
        BigDecimal typeCeiling = scalar(type.ceilingKey(), today, ctx);
        BigDecimal typeOutstanding = outstanding(member.id(), type.id());
        if (asked.sanctioned().add(typeOutstanding).compareTo(typeCeiling) > 0) {
            throw ceiling(
                    type.code()
                            + " limit "
                            + typeCeiling.toPlainString()
                            + " with "
                            + typeOutstanding.toPlainString()
                            + " outstanding");
        }
        BigDecimal maxConcurrent = scalar("LOAN." + type.code() + ".MAX_CONCURRENT", today, ctx);
        if (BigDecimal.valueOf(openLoans(member.id(), type.id())).compareTo(maxConcurrent) >= 0) {
            throw ceiling("already " + maxConcurrent.toPlainString() + " open " + type.code());
        }
        // SRS-12-11: every outstanding balance, then the combined ceiling.
        BigDecimal outstanding = outstanding(member.id(), null);
        BigDecimal maxNew =
                LoanRules.maxNewSanction(scalar("LOAN.COMBINED.CEILING", today, ctx), outstanding);
        if (asked.sanctioned().compareTo(maxNew) > 0) {
            throw ceiling(
                    "combined ceiling: at most " + maxNew.toPlainString() + " may be sanctioned");
        }
        // SRS-12-13 … 12-15: the largest monthly instalment with the existing deductions.
        BigDecimal pct = scalar("LOAN.DEDUCTION.CEILING", today, ctx);
        Map<String, Object> pay = latestPay(member.id());
        BigDecimal largest = asked.instalment();
        if (asked.firstInstalment() != null && asked.firstInstalment().compareTo(largest) > 0) {
            largest = asked.firstInstalment();
        }
        LoanRules.Fit fit =
                LoanRules.fit(
                        asked.sanctioned(),
                        largest,
                        (BigDecimal) pay.get("GROSS_EARNINGS"),
                        (BigDecimal) pay.get("TOTAL_DEDUCTIONS"),
                        pct);
        if (!fit.fits()) {
            throw ceiling("no room under the deduction ceiling this month");
        }
        Terms terms =
                fit.reduced()
                        ? new Terms(
                                asked.sanctioned(),
                                fit.instalments(),
                                fit.instalment(),
                                null,
                                asked.interestAmount(),
                                asked.interestInstalments(),
                                asked.interestInstalment(),
                                asked.startYymm())
                        : asked;
        Long open1 = openRequest(member.id(), type.code());
        return new Checked(
                member,
                type,
                unit,
                new Preview(
                        member.eis(),
                        type.code(),
                        terms,
                        LoanRules.schedule(terms),
                        outstanding,
                        maxNew,
                        fit.reduced(),
                        open1));
    }

    private LoanType type(String code, LocalDate today) {
        return jdbc
                .query(
                        "SELECT loan_type_id, loan_type_code, is_interest_bearing,"
                            + " ceiling_rule_key, max_instalments FROM loan_type_master WHERE"
                            + " loan_type_code = ? AND payroll_type_scope = 'STAFF' AND"
                            + " effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)"
                            + " ORDER BY effective_from DESC",
                        (rs, i) ->
                                new LoanType(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        "Y".equals(rs.getString(3)),
                                        rs.getString(4),
                                        rs.getObject(5, Integer.class)),
                        code,
                        java.sql.Date.valueOf(today),
                        java.sql.Date.valueOf(today))
                .stream()
                .findFirst()
                .orElseThrow(() -> invalid("TYPE"));
    }

    /** The month recovery may start: this month, or the next once this month's cycle is locked. */
    private YearMonth openPeriod(long unit, YearMonth current) {
        String yymm = String.format("%04d%02d", current.getYear(), current.getMonthValue());
        return payroll.cycle(unit, yymm).filter(PayrollRecords.Cycle::isLocked).isPresent()
                ? current.plusMonths(1)
                : current;
    }

    private BigDecimal scalar(String key, LocalDate day, RuleEngine.Context ctx) {
        try {
            return rules.resolve(key, day, ctx)
                    .map(RuleEngine.Resolved::scalar)
                    .filter(v -> v != null)
                    .orElseThrow(() -> notConfigured(key));
        } catch (FciException notSet) {
            if (notSet.getErrorCode() == ApiErrorCode.LOAN_NOT_CONFIGURED) {
                throw notSet;
            }
            throw notConfigured(key);
        }
    }

    /** The outstanding principal and interest of the member's open loans (of one type, or all). */
    private BigDecimal outstanding(long employeeId, Long typeId) {
        BigDecimal sum =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(COALESCE(principal_outstanding, 0) +"
                                + " COALESCE(interest_outstanding, 0)), 0) FROM loan_header WHERE"
                                + " employee_id = ? AND loan_status IN "
                                + OPEN_STATUSES
                                + " AND (? IS NULL OR loan_type_id = ?)",
                        BigDecimal.class,
                        employeeId,
                        typeId,
                        typeId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private long openLoans(long employeeId, long typeId) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM loan_header WHERE employee_id = ? AND loan_type_id ="
                                + " ? AND loan_status IN "
                                + OPEN_STATUSES,
                        Long.class,
                        employeeId,
                        typeId);
        return RowCounts.of(n);
    }

    /** The member's latest computed pay: the gross and deductions the ceiling is tested on. */
    private Map<String, Object> latestPay(long employeeId) {
        return jdbc
                .queryForList(
                        "SELECT gross_earnings, total_deductions FROM payroll_result WHERE"
                                + " employee_id = ? AND payroll_type = 'STAFF' AND run_type ="
                                + " 'ACTUAL' AND result_status = 'CURRENT' ORDER BY payroll_yymm"
                                + " DESC FETCH FIRST 1 ROWS ONLY",
                        employeeId)
                .stream()
                .findFirst()
                .orElseThrow(() -> invalid("NO_PAY_RESULT"));
    }

    /** SRS-12-8: an open request of the same type: pending, or returned and not withdrawn. */
    private Long openRequest(long employeeId, String typeCode) {
        Optional<Long> id =
                jdbc
                        .queryForList(
                                "SELECT approval_id FROM approval_queue WHERE entity_name = ? AND"
                                        + " entity_key LIKE ? AND status IN ('PENDING_APPROVAL',"
                                        + " 'PENDING_ATTACHMENT', 'RETURNED') ORDER BY approval_id",
                                Long.class,
                                ENTITY,
                                employeeId + "|" + typeCode + "|%")
                        .stream()
                        .findFirst();
        return id.orElse(null);
    }

    private static FciException invalid(String defect) {
        return new FciException(ApiErrorCode.LOAN_SANCTION, new Object[] {defect}, List.of(), null);
    }

    private static FciException notConfigured(String what) {
        return new FciException(
                ApiErrorCode.LOAN_NOT_CONFIGURED, new Object[] {what}, List.of(), null);
    }

    private static FciException ceiling(String criterion) {
        return new FciException(
                ApiErrorCode.LOAN_CEILING, new Object[] {criterion}, List.of(), null);
    }
}
