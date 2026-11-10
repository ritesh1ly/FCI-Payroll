package in.gov.fci.payroll.settlement.orchestration;

import in.gov.fci.payroll.allowance.api.AllowanceClearance;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.leave.api.LeaveEncashments;
import in.gov.fci.payroll.loans.api.LoanClearance;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.payroll.api.PendingSalary;
import in.gov.fci.payroll.penalty.api.PenaltyClearance;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.settlement.business.SettlementErrorCode;
import in.gov.fci.payroll.settlement.business.SettlementRules;
import in.gov.fci.payroll.settlement.business.SettlementRules.Stage;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Payroll Maker's and the establishment's work on a case (plan 11 phases 11b / 11c; SRS-10-1,
 * 10-4, 10-5, 10-7, 10-13, 10-16, 10-21, 10-27, 10-28; D11-2, D11-3).
 *
 * <p>Computation: the system clearances are read again; pending salary is the separation month's
 * net pay when the regular run has not paid it (a separation on the last day of the month only:
 * OPEN-QUESTION, PENDING 11-part-month, the SRS gives no part-month rule); leave encashment is the
 * leave module's valuation at the separation date; arrears are the member's approved arrear details
 * not yet posted (SRS-8-16; an arrear awaiting its decision on I-08 holds the computation, it is
 * neither paid unapproved nor left out); ex-gratia the approved entries; every outstanding loan,
 * advance, penalty and PLI advance is itemised for full recovery (never carried forward), against
 * payroll dues unless the Maker chose gratuity. Gratuity waits for the qualifying service from HRMS
 * (D11-3). A source that cannot answer stops the computation: never settled on partial data.
 */
@Service
@Loggable
public class SettlementWork {

    static final String ENTITY = "final_settlement";

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final SettlementClearances clearances;
    private final PendingSalary pending;
    private final PayrollRecords payroll;
    private final LeaveEncashments encashments;
    private final LoanClearance loans;
    private final PenaltyClearance penalties;
    private final AllowanceClearance allowances;
    private final ApprovalStatuses statuses;
    private final AuditService audit;
    private final Clock clock;

    public SettlementWork(
            JdbcTemplate jdbc,
            Authorization policy,
            SettlementClearances clearances,
            PendingSalary pending,
            PayrollRecords payroll,
            LeaveEncashments encashments,
            LoanClearance loans,
            PenaltyClearance penalties,
            AllowanceClearance allowances,
            ApprovalStatuses statuses,
            AuditService audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.clearances = clearances;
        this.pending = pending;
        this.payroll = payroll;
        this.encashments = encashments;
        this.loans = loans;
        this.penalties = penalties;
        this.allowances = allowances;
        this.statuses = statuses;
        this.audit = audit;
        this.clock = clock;
    }

    /** The case and its settlement as the work reads them. */
    record Case(
            long caseId,
            String reference,
            long employeeId,
            Long locationId,
            LocalDate separationDate,
            Stage stage,
            long settlementId,
            String status,
            String employmentStatus) {}

    /**
     * SRS-10-1 / 10-13: computes (or recomputes) the settlement once its clearances are raised;
     * stage Computed. SRS-10-14 (US-GAP-218): an outstanding clearance does not hold the
     * computation; it blocks only the move to Approved ({@link SettlementApprovals}). Never while
     * the settlement waits for the Payroll Checker (Wave A review): the item submitted stands for
     * the settlement as then computed, so a recomputation waits for its return or rejection.
     */
    @Transactional
    public void compute(AccessContext context, long caseId) {
        Case c = load(caseId);
        policy.requireEmployeeRecord(
                context, SettlementCaseOpener.SCREEN, "COMPUTE", c.employeeId(), c.locationId());
        if (!statuses.pending(ENTITY, c.reference()).isEmpty()) {
            throw new FciException(
                    SettlementErrorCode.APPROVAL_PENDING,
                    new Object[] {
                        "Payroll Checker's decision on the settlement submitted (return it to"
                                + " recompute)"
                    },
                    List.of(),
                    null);
        }
        SettlementRules.settleable(c.separationDate(), c.employmentStatus())
                .ifPresent(
                        d -> {
                            throw new FciException(
                                    SettlementErrorCode.NOT_SETTLEABLE,
                                    new Object[] {d},
                                    List.of(),
                                    null);
                        });
        if (!List.of("INITIATED", "COMPUTED").contains(c.status())) {
            throw stage(c, Stage.CLEARANCES_RAISED);
        }
        clearances.refresh(c.caseId(), c.employeeId());
        Stage now = Stage.of(stageOf(c.caseId()));
        if (now.number() < Stage.CLEARANCES_RAISED.number()) {
            throw stage(c, Stage.CLEARANCES_RAISED);
        }
        BigDecimal pendingSalary = pendingSalary(c);
        BigDecimal leave = leaveEncashment(c);
        BigDecimal arrears = arrears(c);
        BigDecimal exGratia =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(ex_gratia_amount), 0) FROM fs_ex_gratia_entry WHERE"
                                + " settlement_id = ? AND entry_status = 'APPROVED'",
                        BigDecimal.class,
                        c.settlementId());
        itemise(context.loginId(), c);
        BigDecimal fromPayroll = recoveries(c.settlementId(), "PAYROLL_DUES");
        BigDecimal fromGratuity = recoveries(c.settlementId(), "GRATUITY");
        BigDecimal net =
                SettlementRules.netPayable(pendingSalary, leave, arrears, exGratia, fromPayroll);
        jdbc.update(
                "UPDATE final_settlement SET pending_salary_wage = ?, leave_encashment_amount = ?,"
                        + " total_arrears = ?, ex_gratia_amount = ?, gratuity_amount = 0,"
                        + " total_recoveries = ?, recovered_from_gratuity = ?, net_payable = ?,"
                        + " settlement_status = 'COMPUTED' WHERE settlement_id = ?",
                pendingSalary,
                leave,
                arrears,
                exGratia,
                fromPayroll.add(fromGratuity),
                fromGratuity,
                net,
                c.settlementId());
        jdbc.update(
                "UPDATE settlement_case SET case_stage = ?, owner_role_code = 'PAYROLL_MAKER',"
                        + " owner_user = ? WHERE settlement_case_id = ?",
                Stage.COMPUTED.number(),
                context.loginId(),
                c.caseId());
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                c.reference(),
                                "net_payable",
                                null,
                                net.toPlainString(),
                                "Settlement computed: pending salary "
                                        + plain(pendingSalary)
                                        + ", leave encashment "
                                        + plain(leave)
                                        + ", arrears "
                                        + plain(arrears)
                                        + ", ex-gratia "
                                        + plain(exGratia)
                                        + ", recoveries "
                                        + plain(fromPayroll)
                                        + (SettlementRules.isNetRecovery(net)
                                                ? " (net recovery)"
                                                : ""))
                        .module(SettlementCaseOpener.SCREEN));
    }

    /**
     * SRS-10-27: Establishment / HR answers the establishment no-dues: nil, or the dues (quarters,
     * licence fee) to recover, itemised on the settlement.
     */
    @Transactional
    public void establishmentNoDues(
            AccessContext context, long caseId, BigDecimal dues, String remark) {
        Case c = load(caseId);
        policy.requireEmployeeRecord(
                context, SettlementCaseOpener.SCREEN, "CLEAR", c.employeeId(), c.locationId());
        if (dues == null || dues.signum() < 0 || remark == null || remark.isBlank()) {
            throw entry("the dues (zero for nil) and a remark are required");
        }
        if (!List.of("INITIATED", "COMPUTED").contains(c.status())) {
            throw stage(c, Stage.CLEARANCES_RAISED);
        }
        String before =
                jdbc
                        .queryForList(
                                "SELECT clearance_status FROM settlement_clearance WHERE"
                                        + " settlement_case_id = ? AND clearance_type ="
                                        + " 'ESTABLISHMENT_NO_DUES'",
                                String.class,
                                caseId)
                        .stream()
                        .findFirst()
                        .orElse(null);
        boolean excluded = "EXCLUDED".equals(before);
        // Wave C review: one the Checker excluded stays EXCLUDED with the exclusion's reason; the
        // answer is recorded apart (received, its remark in answer_remark), so that a returned or
        // rejected settlement restores the clearance as RECEIVED with the answer's remark.
        jdbc.update(
                "UPDATE settlement_clearance SET clearance_status = CASE WHEN clearance_status ="
                    + " 'EXCLUDED' THEN clearance_status ELSE 'RECEIVED' END, received_at = ?,"
                    + " remark = CASE WHEN clearance_status = 'EXCLUDED' THEN remark ELSE ? END,"
                    + " answer_remark = CASE WHEN clearance_status = 'EXCLUDED' THEN ? ELSE NULL"
                    + " END, clearing_authority = ? WHERE settlement_case_id = ? AND clearance_type"
                    + " = 'ESTABLISHMENT_NO_DUES'",
                Timestamp.from(clock.instant()),
                trim(remark.strip(), 1000),
                trim(remark.strip(), 1000),
                trim(context.loginId(), 150),
                caseId);
        if (dues.signum() > 0) {
            upsert(context.loginId(), c, "LICENCE_FEE", "establishment", "NO_DUES", dues);
            if ("COMPUTED".equals(c.status())) {
                reopenComputation(context.loginId(), c);
            }
        }
        clearances.advance(caseId);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "settlement_clearance",
                                c.reference() + "|ESTABLISHMENT_NO_DUES",
                                excluded ? "answer_remark" : "clearance_status",
                                excluded ? null : before,
                                excluded ? trim(remark.strip(), 1000) : "RECEIVED",
                                (excluded
                                                ? "Establishment no-dues while excluded: "
                                                : "Establishment no-dues: ")
                                        + plain(dues)
                                        + " - "
                                        + remark.strip())
                        .module(SettlementCaseOpener.SCREEN));
    }

    /**
     * SRS-10-3 ("compute the net payable after all recoveries") with SRS-10-14 (US-GAP-218): a
     * settlement may be computed while a clearance is outstanding, so dues itemised by a later
     * clearance answer send it back to the Maker for recomputation, as a return does (SRS-10-30):
     * status Initiated, the stage back to Clearances raised (the caller moves it on once every
     * clearance is settled). A settlement already submitted is then refused at approval (it is no
     * longer COMPUTED) and cannot be recomputed until the Checker returns it ({@link #compute}).
     */
    private void reopenComputation(String actor, Case c) {
        jdbc.update(
                "UPDATE final_settlement SET settlement_status = 'INITIATED' WHERE"
                        + " settlement_status = 'COMPUTED' AND settlement_case_id = ?",
                c.caseId());
        jdbc.update(
                "UPDATE settlement_case SET case_stage = ? WHERE settlement_case_id = ? AND"
                        + " case_stage = ?",
                Stage.CLEARANCES_RAISED.number(),
                c.caseId(),
                Stage.COMPUTED.number());
        audit.record(
                actor,
                AuditService.Entry.change(
                                ENTITY,
                                c.reference(),
                                "settlement_status",
                                "COMPUTED",
                                "INITIATED",
                                "Dues itemised after the computation: to be recomputed")
                        .module(SettlementCaseOpener.SCREEN));
    }

    // ---------------------------------------------------------------- steps

    Case load(long caseId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT c.settlement_case_id, c.case_reference, c.employee_id,"
                            + " e.location_id, c.separation_date, c.case_stage, s.settlement_id,"
                            + " s.settlement_status, e.employment_status FROM settlement_case c"
                            + " JOIN employee e ON e.employee_id = c.employee_id JOIN"
                            + " final_settlement s ON s.settlement_case_id = c.settlement_case_id"
                            + " WHERE c.settlement_case_id = ?",
                        caseId);
        if (rows.isEmpty()) {
            throw new FciException(in.gov.fci.payroll.platform.error.CommonErrorCode.NOT_FOUND);
        }
        Map<String, Object> r = rows.getFirst();
        Object location = r.get("LOCATION_ID");
        return new Case(
                caseId,
                (String) r.get("CASE_REFERENCE"),
                ((Number) r.get("EMPLOYEE_ID")).longValue(),
                location == null ? null : ((Number) location).longValue(),
                new Date(((java.util.Date) r.get("SEPARATION_DATE")).getTime()).toLocalDate(),
                Stage.of(((Number) r.get("CASE_STAGE")).intValue()),
                ((Number) r.get("SETTLEMENT_ID")).longValue(),
                (String) r.get("SETTLEMENT_STATUS"),
                (String) r.get("EMPLOYMENT_STATUS"));
    }

    private int stageOf(long caseId) {
        Integer s =
                jdbc.queryForObject(
                        "SELECT case_stage FROM settlement_case WHERE settlement_case_id = ?",
                        Integer.class,
                        caseId);
        return s == null ? 1 : s;
    }

    /**
     * SRS-8-16 (l.3053), SRS-10-13 (l.2870): the member's arrears routed to the case, as the
     * Checker approved them (UC-02, A-08: the Checker approves before the merge; R-MC). A detail
     * still CALCULATED can yet be REVERSED by that decision, so it is never paid; and it is not
     * left out either, since the member is in no later cycle: SRS-10-28 (l.2884), never settled on
     * partial data. OPEN-QUESTION: FCI to confirm this hold (PENDING O05-ARREAR-HOLD).
     */
    private BigDecimal arrears(Case c) {
        BigDecimal undecided =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM arrear_detail WHERE employee_id = ? AND arrear_status"
                                + " = 'CALCULATED'",
                        BigDecimal.class,
                        c.employeeId());
        if (undecided != null && undecided.signum() > 0) {
            throw source("an arrear of the member awaits its decision on I-08");
        }
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(arrear_amount), 0) FROM arrear_detail WHERE employee_id = ?"
                        + " AND arrear_status = 'APPROVED'",
                BigDecimal.class,
                c.employeeId());
    }

    /**
     * The separation month's net pay when the regular run has not paid it. SRS-1-41 (l.3581): a
     * Salary Stop suppresses the period's computation and payment, so nothing is due. Anything else
     * the month's computation does not yield (a Salary Withheld or held payment, which accrues
     * until a release order; a member with no Basic pay) is a due that cannot be stated: SRS-10-28
     * (l.2884), never settled on partial data, so it stops the computation and is never read as
     * nil.
     */
    private BigDecimal pendingSalary(Case c) {
        YearMonth month = YearMonth.from(c.separationDate());
        String yymm = String.format("%04d%02d", month.getYear(), month.getMonthValue());
        if (payroll.paidInRegularRun(c.employeeId(), yymm)) {
            return BigDecimal.ZERO;
        }
        if (!c.separationDate().equals(month.atEndOfMonth())) {
            throw source(
                    "part-month pay to "
                            + c.separationDate()
                            + " is not configured (PENDING 11-part-month)");
        }
        PendingSalary.Result r;
        try {
            r = pending.compute(c.employeeId(), month);
        } catch (PendingSalary.NotComputable gap) {
            throw source("pending salary of " + yymm + ": " + gap.getMessage());
        }
        if (r.computed() && r.net() != null) {
            return r.net();
        }
        if (r.salaryStopped()) {
            return BigDecimal.ZERO;
        }
        throw source("pending salary of " + yymm + " not computed: " + r.skipReason());
    }

    private BigDecimal leaveEncashment(Case c) {
        try {
            return encashments.atSeparation(c.employeeId(), c.separationDate()).amount();
        } catch (FciException gap) {
            throw source(
                    "leave encashment: "
                            + gap.getErrorCode().code()
                            + " "
                            + java.util.Arrays.toString(gap.getMessageArgs()));
        }
    }

    /**
     * SRS-10-4 / 10-7: every outstanding account itemised, its amount as the module answers now.
     */
    private void itemise(String actor, Case c) {
        for (LoanClearance.Outstanding o : loans.outstanding(c.employeeId())) {
            upsert(actor, c, o.kind(), "loan_header", o.loanId() + " " + o.reference(), o.amount());
        }
        for (PenaltyClearance.Outstanding o : penalties.outstanding(c.employeeId())) {
            upsert(
                    actor,
                    c,
                    "PENALTY",
                    "penalty_order",
                    o.orderId() + " " + o.penaltyRef(),
                    o.amount());
        }
        // SRS-10-13 / BR-FNF-001: the excess wages - the net pay of each month after the
        // separation month that a regular run paid (the separation month itself is the pending
        // salary, a part month PENDING 11-part-month).
        for (PayrollRecords.PaidMonth m :
                payroll.paidAfter(c.employeeId(), YearMonth.from(c.separationDate()))) {
            if (m.netPay() != null && m.netPay().signum() > 0) {
                upsert(
                        actor,
                        c,
                        "EXCESS_WAGES",
                        "payroll_result",
                        m.payrollYymm() + " salary paid after separation",
                        m.netPay());
            }
        }
        for (AllowanceClearance.Outstanding o : allowances.outstanding(c.employeeId())) {
            upsert(
                    actor,
                    c,
                    "PLI_ADVANCE",
                    "pli_policy",
                    o.policyId() + " " + o.reference(),
                    o.amount());
        }
    }

    private void upsert(
            String actor, Case c, String type, String entity, String key, BigDecimal amount) {
        List<Map<String, Object>> found =
                jdbc.queryForList(
                        "SELECT recovery_detail_id, recovery_amount FROM fs_recovery_detail WHERE"
                                + " settlement_id = ? AND source_entity = ? AND source_key = ?",
                        c.settlementId(),
                        entity,
                        trim(key, 100));
        if (found.isEmpty()) {
            jdbc.update(
                    "INSERT INTO fs_recovery_detail (settlement_id, recovery_type, source_entity,"
                        + " source_key, recovery_period, recovery_amount) VALUES (?, ?, ?, ?, ?,"
                        + " ?)",
                    c.settlementId(),
                    type,
                    entity,
                    trim(key, 100),
                    "AT " + c.separationDate(),
                    amount);
            return;
        }
        BigDecimal before = (BigDecimal) found.getFirst().get("RECOVERY_AMOUNT");
        if (before.compareTo(amount) != 0) {
            jdbc.update(
                    "UPDATE fs_recovery_detail SET recovery_amount = ? WHERE recovery_detail_id ="
                            + " ?",
                    amount,
                    found.getFirst().get("RECOVERY_DETAIL_ID"));
            audit.record(
                    actor,
                    AuditService.Entry.change(
                                    "fs_recovery_detail",
                                    c.reference() + "|" + key,
                                    "recovery_amount",
                                    plain(before),
                                    plain(amount),
                                    "Recovery re-read at computation")
                            .module(SettlementCaseOpener.SCREEN));
        }
    }

    private BigDecimal recoveries(long settlementId, String source) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(recovery_amount), 0) FROM fs_recovery_detail WHERE"
                        + " settlement_id = ? AND recovery_source = ?",
                BigDecimal.class,
                settlementId,
                source);
    }

    static FciException stage(Case c, Stage needed) {
        return new FciException(
                SettlementErrorCode.STAGE,
                new Object[] {c.stage().name(), needed.name()},
                List.of(),
                null);
    }

    static FciException entry(String why) {
        return new FciException(SettlementErrorCode.ENTRY, new Object[] {why}, List.of(), null);
    }

    static FciException source(String why) {
        return new FciException(SettlementErrorCode.SOURCE, new Object[] {why}, List.of(), null);
    }

    static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    static <T> List<T> nonNull(List<T> values) {
        List<T> out = new ArrayList<>();
        values.stream().filter(Objects::nonNull).forEach(out::add);
        return out;
    }
}
