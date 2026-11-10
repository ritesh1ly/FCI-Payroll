package in.gov.fci.payroll.penalty.orchestration;

import in.gov.fci.payroll.configuration.api.NetPayFloors;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.loans.api.DeductionCeiling;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.penalty.business.PenaltyErrorCode;
import in.gov.fci.payroll.penalty.business.PenaltyRules;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
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
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * I-06 penalty / recovery order entry by the Payroll Maker (plan 12 phase 12b; SRS-14-1, SRS-14-5,
 * SRS-14-6, SRS-14-7, SRS-14-10, SRS-14-12, SRS-14-18; I-06 use case and field table):
 *
 * <ul>
 *   <li>an employee's disciplinary penalty is HRMS's (I-21, CF-17): on I-06 only a RECOVERY order
 *       is keyed for an employee (plan 12 D12-2); a labour penalty of any nature is keyed here
 *       (SRS-14-1; fields Q-12-1). A nature with no recovery (increment stop, Basic reduction)
 *       carries no amount, schedule or pay test; a labour recovery is tested on the labourer's own
 *       payroll, and is refused while that payroll has computed nothing (LBR-20d-3);
 *   <li>the per-period deduction is the amount ÷ instalments (rounded down, the last the residual,
 *       D12-3), from an open or future month;
 *   <li>SRS-14-7: within the Loans &amp; Advances deduction ceiling ({@link DeductionCeiling}): a
 *       breaching instalment is reduced and the recovery lengthened, and the Checker is told;
 *   <li>SRS-14-10: a recovery that would take the latest net pay below the M-14 floor (without a
 *       floor: to zero or below, as the cycle's own quarantine, D12-13) is submitted for two
 *       approvals (route I06_FLOOR: the paying unit's Payroll Checker, then the Regional Office's);
 *       the Maker may instead lengthen the recovery;
 *   <li>the order copy is mandatory (SRS-14-12) and the preview shows the member and the Basic
 *       (SRS-14-18); a back-dated effective date is flagged (SRS-14-6; the retrospective
 *       recomputation is module 13's).
 * </ul>
 *
 * The order and its schedule are written on the last approval ({@link PenaltyOrderEffect}); until
 * then the request lives in its approval item, a returned one with its reason (PN-17).
 */
@Service
@Loggable
public class PenaltyOrderService {

    public static final String SCREEN = "I-06";
    public static final String ENTITY = "penalty_order_request";
    static final String NATURES = "PENALTY_NATURE";
    static final String HEADS = "PENALTY_RECOVERY";
    static final String RECOVERY = "RECOVERY";
    static final String STAFF = "STAFF";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Set<String> PAYABLE = Set.of("IN_SERVICE", "ON_DEPUTATION");
    private static final Pattern PRINTABLE = Pattern.compile("^[\\x20-\\x7E]*$");

    /** What the Maker enters from the order. */
    public record Request(
            String eis,
            String nature,
            String orderReference,
            LocalDate effectiveDate,
            BigDecimal amount,
            int instalments,
            String startYymm,
            // SRS l.2995 (US-12-08 AC4): at a below-floor breach the Maker may instead defer the
            // recovery to F&F; null or false = recover in the cycles (default, OD-75).
            Boolean deferToFnf,
            // SRS-14-13: the end of a labour order that recovers nothing, null when open.
            LocalDate periodTo) {

        /** A recovery order, deferred to F&amp;F or not. */
        public Request(
                String eis,
                String nature,
                String orderReference,
                LocalDate effectiveDate,
                BigDecimal amount,
                int instalments,
                String startYymm,
                Boolean deferToFnf) {
            this(
                    eis,
                    nature,
                    orderReference,
                    effectiveDate,
                    amount,
                    instalments,
                    startYymm,
                    deferToFnf,
                    null);
        }

        /** An order recovered in the cycles (no deferral to F&F). */
        public Request(
                String eis,
                String nature,
                String orderReference,
                LocalDate effectiveDate,
                BigDecimal amount,
                int instalments,
                String startYymm) {
            this(eis, nature, orderReference, effectiveDate, amount, instalments, startYymm, null);
        }

        /** The Maker chose to defer the recovery to F&amp;F (SRS l.2995). */
        public boolean deferred() {
            return Boolean.TRUE.equals(deferToFnf);
        }
    }

    /** The order copy (SRS-14-12, SRS-G-30). */
    public record Copy(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    /**
     * What the Maker and the Checker see before the decision.
     *
     * @param adjusted the instalment was reduced and the recovery lengthened for the ceiling
     * @param belowFloor the recovery takes the net pay below the floor (two approvals)
     * @param floor the M-14 floor amount for the latest gross, null when no floor is in force
     */
    public record Preview(
            String eis,
            String name,
            BigDecimal currentBasic,
            String nature,
            BigDecimal amount,
            int instalments,
            List<PenaltyRules.Line> schedule,
            boolean adjusted,
            BigDecimal latestNet,
            BigDecimal netAfter,
            BigDecimal floor,
            boolean belowFloor,
            boolean backdated,
            // SRS-14-18 (l.2979): the Basic after the order; a recovery leaves Basic as it is (a
            // pay-reducing nature is HRMS's, Q-091).
            BigDecimal proposedBasic,
            // SRS l.2995: the recovery is deferred to F&F instead of breaching the floor.
            boolean deferredToFnf) {}

    private record Checked(EmployeeDirectory.Member member, long unit, Preview preview) {}

    private final JdbcTemplate jdbc;
    private final EmployeeDirectory employees;
    private final OrgDirectory org;
    private final PayrollRecords payroll;
    private final RuleEngine rules;
    private final NetPayFloors floors;
    private final ReferenceData references;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final DocumentStore documents;
    private final ObjectMapper json;
    private final in.gov.fci.payroll.audit.api.AuditService audit;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public PenaltyOrderService(
            JdbcTemplate jdbc,
            EmployeeDirectory employees,
            OrgDirectory org,
            PayrollRecords payroll,
            RuleEngine rules,
            NetPayFloors floors,
            ReferenceData references,
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
        this.floors = floors;
        this.references = references;
        this.policy = policy;
        this.approvals = approvals;
        this.documents = documents;
        this.json = json;
        this.clock = clock;
    }

    /** I-06: every check of the order, its schedule, the ceiling fit and the floor test. */
    @Transactional(readOnly = true)
    public Preview preview(AccessContext context, Request r) {
        return check(context, r).preview();
    }

    /**
     * SRS-14-1, SRS-14-12: the order with its copy goes to the Payroll Checker (and, below the
     * floor, then to the Regional Office's Payroll Checker). SRS-14-15 and the use case's main flow
     * (US-GAP-229): the Penalty ID is assigned here, as the order is recorded and before the
     * Checker's decision; it is the request's key ({@code <employee id>|<Penalty ID>}), shown on
     * the request and carried to the written order. A returned or rejected request keeps its
     * Penalty ID unused (plan 12 OD-58).
     */
    @Transactional
    public SubmittedApproval submit(AccessContext context, Request r, Copy copy) {
        if (copy == null || copy.content() == null || copy.content().length == 0) {
            // SRS-20-27: the blocked submission is audited.
            audit.submissionBlocked(
                    context.loginId(),
                    ENTITY,
                    r == null || r.eis() == null ? "-" : r.eis(),
                    SCREEN,
                    "order copy");
            throw new FciException(PenaltyErrorCode.ORDER_COPY);
        }
        Checked c = check(context, r);
        Preview p = c.preview();
        String penaltyRef = PenaltyWriter.nextPenaltyId(jdbc, LocalDate.now(clock.withZone(IST)));
        String key = c.member().id() + "|" + penaltyRef;
        String provisional = DocumentStore.PROVISIONAL + UUID.randomUUID();
        DocumentStore.AttachmentView attached =
                documents.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                ENTITY,
                                provisional,
                                copy.documentType(),
                                copy.reference(),
                                copy.orderDate(),
                                copy.issuingAuthority(),
                                copy.fileName(),
                                copy.content(),
                                true));
        documents.adopt(context.loginId(), ENTITY, provisional, attached.id(), key);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("employeeId", c.member().id());
        payload.put("penaltyRef", penaltyRef);
        payload.put("eis", p.eis());
        payload.put("nature", p.nature());
        payload.put("orderReference", r.orderReference().strip());
        payload.put("effectiveDate", r.effectiveDate().toString());
        payload.put("amount", p.amount());
        payload.put("instalments", p.instalments());
        payload.put("startYymm", r.startYymm());
        payload.put("adjusted", p.adjusted());
        payload.put("belowFloor", p.belowFloor());
        payload.put("latestNet", p.latestNet());
        payload.put("netAfter", p.netAfter());
        payload.put("floor", p.floor());
        payload.put("backdated", p.backdated());
        payload.put("deferToFnf", p.deferredToFnf());
        payload.put("periodTo", r.periodTo() == null ? null : r.periodTo().toString());
        payload.put("orderAttachmentId", attached.id());
        return approvals.submit(
                context,
                new Submission(
                                SCREEN,
                                // A deferred order recovers nothing below the floor in service: the
                                // ordinary route (OD-75); the floor route only for a breach
                                // applied.
                                p.belowFloor() && !p.deferredToFnf() ? "FLOOR" : "CREATE",
                                ENTITY,
                                key,
                                ActionType.CREATE,
                                c.unit(),
                                json.writeValueAsString(payload),
                                r.effectiveDate(),
                                penaltyRef
                                        + ": "
                                        + p.nature()
                                        + " "
                                        + r.orderReference().strip()
                                        + terms(p, r)
                                        + (p.adjusted() ? " (lengthened for the ceiling)" : "")
                                        + (p.deferredToFnf()
                                                ? " (deferred to F&F: below the net-pay floor)"
                                                : p.belowFloor()
                                                        ? " (below the net-pay floor)"
                                                        : ""))
                        .withImpact(p.amount()),
                (OtpProof) null);
    }

    /** The order's terms as the approval item tells them. */
    private static String terms(Preview p, Request r) {
        return p.amount() == null
                ? " for " + p.eis() + " from " + r.effectiveDate()
                : " of "
                        + p.amount().toPlainString()
                        + " for "
                        + p.eis()
                        + " in "
                        + p.instalments()
                        + " instalments from "
                        + r.startYymm();
    }

    // ---------------------------------------------------------------- the checks

    private Checked check(AccessContext context, Request r) {
        if (r == null) {
            throw invalid("the order is missing");
        }
        EmployeeDirectory.Member member =
                employees
                        .memberByEis(r.eis() == null ? null : r.eis().strip())
                        .orElseThrow(() -> invalid("no such member"));
        policy.requireEmployeeRecord(context, SCREEN, "CREATE", member.id(), member.locationId());
        if (!PAYABLE.contains(member.employmentStatus())) {
            throw invalid("the member is not in service");
        }
        if (r.nature() == null || !references.contains(NATURES, r.nature())) {
            throw invalid("choose a penalty nature of the list");
        }
        // SRS-16-13, SRS-14-10: the member's own payroll type decides the floor and the pay tested.
        String type = employees.employeeTypeOf(member.id()).orElse(STAFF);
        if (!RECOVERY.equals(r.nature()) && STAFF.equals(type)) {
            // Plan 12 D12-2: an employee's disciplinary penalty is received from HRMS (I-21).
            throw new FciException(
                    PenaltyErrorCode.HRMS_ONLY, new Object[] {r.nature()}, List.of(), null);
        }
        String reference = r.orderReference() == null ? "" : r.orderReference().strip();
        if (reference.isEmpty()
                || reference.length() > 60
                || !PRINTABLE.matcher(reference).matches()) {
            throw invalid("the order reference is required, printable, at most 60 characters");
        }
        if (r.effectiveDate() == null) {
            throw invalid("the effective date is required");
        }
        LocalDate today = LocalDate.now(clock.withZone(IST));
        long unit =
                org.cycleUnitOf(member.locationId())
                        .orElseThrow(() -> new FciException(ApiErrorCode.CYCLE_NO_UNIT))
                        .getId();
        YearMonth open = openPeriod(unit, YearMonth.from(today));
        if (!RECOVERY.equals(r.nature()) && head(r.nature(), today).isEmpty()) {
            return withoutRecovery(member, unit, r, open);
        }
        PenaltyRules.defect(r.amount(), r.instalments(), r.startYymm(), open)
                .ifPresent(
                        d -> {
                            throw invalid(
                                    switch (d) {
                                        case "AMOUNT" ->
                                                "the amount is above zero, in rupees and paise";
                                        case "INSTALMENTS" ->
                                                "the instalments are 1 to 999 and no more than the"
                                                        + " amount has rupees";
                                        default -> "the recovery starts in an open or future month";
                                    });
                        });
        if (head(r.nature(), today).isEmpty()) {
            throw notConfigured("the recovery head of " + r.nature() + " (M-16 " + HEADS + ")");
        }
        Map<String, Object> pay = latestPay(member.id(), type);
        BigDecimal gross = (BigDecimal) pay.get("GROSS_EARNINGS");
        BigDecimal deductions = (BigDecimal) pay.get("TOTAL_DEDUCTIONS");
        BigDecimal net = (BigDecimal) pay.get("NET_PAY");
        BigDecimal pct = scalar(DeductionCeiling.RULE_KEY, today, member.locationId(), type);
        List<PenaltyRules.Line> asked =
                PenaltyRules.schedule(r.amount(), r.instalments(), r.startYymm());
        DeductionCeiling.Fit fit =
                DeductionCeiling.fit(r.amount(), asked.get(0).amount(), gross, deductions, pct);
        if (!fit.fits()) {
            throw new FciException(PenaltyErrorCode.CEILING);
        }
        int instalments = fit.reduced() ? fit.instalments() : r.instalments();
        List<PenaltyRules.Line> schedule =
                fit.reduced()
                        ? PenaltyRules.schedule(r.amount(), instalments, r.startYymm())
                        : asked;
        BigDecimal netAfter = net.subtract(schedule.get(0).amount());
        LocalDate startDay = PenaltyRules.month(r.startYymm()).orElseThrow().atDay(1);
        Optional<NetPayFloors.Floor> floor = floors.inForce(type, startDay);
        boolean below =
                floor.map(f -> f.breachedBy(netAfter, gross)).orElse(netAfter.signum() <= 0);
        boolean backdated = r.effectiveDate().isBefore(open.atDay(1));
        // SRS l.2995: deferral to F&F is the alternative to a below-floor breach, nothing else.
        if (r.deferred() && !below) {
            throw invalid("an order is deferred to F&F only when it breaches the net-pay floor");
        }
        BigDecimal currentBasic = basic(member.id());
        return new Checked(
                member,
                unit,
                new Preview(
                        member.eis(),
                        member.name(),
                        currentBasic,
                        r.nature(),
                        r.amount(),
                        instalments,
                        schedule,
                        fit.reduced(),
                        net,
                        netAfter,
                        floor.map(f -> f.amount(gross)).orElse(null),
                        below,
                        backdated,
                        "RECOVERY".equals(r.nature()) ? currentBasic : null,
                        r.deferred()));
    }

    /**
     * SRS-14-8, SRS-14-13: a labour order that recovers nothing (an increment stop, a Basic
     * reduction): the reference and date are checked as for any order; there is no schedule to
     * test, and an amount or recovery terms are refused, as on an HRMS record.
     */
    private Checked withoutRecovery(
            EmployeeDirectory.Member member, long unit, Request r, YearMonth open) {
        if (r.amount() != null || r.instalments() != 0 || r.startYymm() != null) {
            throw invalid("an amount is given for a nature that recovers nothing");
        }
        if (r.deferred()) {
            throw invalid("an order is deferred to F&F only when it breaches the net-pay floor");
        }
        if (r.periodTo() != null && r.periodTo().isBefore(r.effectiveDate())) {
            throw invalid("the period ends before it starts");
        }
        BigDecimal currentBasic = basic(member.id());
        return new Checked(
                member,
                unit,
                new Preview(
                        member.eis(),
                        member.name(),
                        currentBasic,
                        r.nature(),
                        null,
                        0,
                        List.of(),
                        false,
                        null,
                        null,
                        null,
                        false,
                        r.effectiveDate().isBefore(open.atDay(1)),
                        null,
                        false));
    }

    /** The month recovery may start: this month, or the next once this month's cycle is locked. */
    private YearMonth openPeriod(long unit, YearMonth current) {
        String yymm = String.format("%04d%02d", current.getYear(), current.getMonthValue());
        return payroll.cycle(unit, yymm).filter(PayrollRecords.Cycle::isLocked).isPresent()
                ? current.plusMonths(1)
                : current;
    }

    /** The pay code a nature is recovered under, in force on {@code day} (D12-6). */
    private Optional<Integer> head(String nature, LocalDate day) {
        return jdbc
                .queryForList(
                        "SELECT v.numeric_attribute FROM reference_value v JOIN reference_list l ON"
                                + " l.list_id = v.list_id WHERE l.list_code = 'PENALTY_RECOVERY'"
                                + " AND v.value_code = ? AND v.numeric_attribute IS NOT NULL AND"
                                + " v.effective_from <= ? AND (v.effective_to IS NULL OR"
                                + " v.effective_to >= ?)",
                        BigDecimal.class,
                        nature,
                        java.sql.Date.valueOf(day),
                        java.sql.Date.valueOf(day))
                .stream()
                .findFirst()
                .map(BigDecimal::intValueExact);
    }

    /** The member's latest computed pay: what the ceiling and the floor are tested on. */
    private Map<String, Object> latestPay(long employeeId, String payrollType) {
        return jdbc
                .queryForList(
                        "SELECT gross_earnings, total_deductions, net_pay FROM payroll_result"
                                + " WHERE employee_id = ? AND payroll_type = ? AND run_type ="
                                + " 'ACTUAL' AND result_status = 'CURRENT' ORDER BY payroll_yymm"
                                + " DESC FETCH FIRST 1 ROWS ONLY",
                        employeeId,
                        payrollType)
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                invalid(
                                        "the member has no computed pay yet to test the recovery"
                                                + " on"));
    }

    private BigDecimal basic(long employeeId) {
        return jdbc.queryForObject(
                "SELECT current_basic FROM employee WHERE employee_id = ?",
                BigDecimal.class,
                employeeId);
    }

    private BigDecimal scalar(String key, LocalDate day, Long locationId, String payrollType) {
        try {
            return rules.resolve(key, day, new RuleEngine.Context(payrollType, locationId))
                    .map(RuleEngine.Resolved::scalar)
                    .filter(v -> v != null)
                    .orElseThrow(() -> notConfigured(key + " (M-18)"));
        } catch (FciException notSet) {
            if (notSet.getErrorCode() == PenaltyErrorCode.NOT_CONFIGURED) {
                throw notSet;
            }
            throw notConfigured(key + " (M-18)");
        }
    }

    private static FciException invalid(String why) {
        return new FciException(PenaltyErrorCode.ORDER, new Object[] {why}, List.of(), null);
    }

    private static FciException notConfigured(String what) {
        return new FciException(
                PenaltyErrorCode.NOT_CONFIGURED, new Object[] {what}, List.of(), null);
    }
}
