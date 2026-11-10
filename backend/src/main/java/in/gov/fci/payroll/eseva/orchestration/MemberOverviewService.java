package in.gov.fci.payroll.eseva.orchestration;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E-01 as Wireframes v6.1 draws it (OD-121; SRS-17-1, SRS-17-12, SRS-17-17, BR-ESEVA-004; API-14-2
 * in PENDING: the sources give no contract): the net pay of the last locked period, the five tiles,
 * My Documents and My Requests of one member. Read-only, and the member comes from the session,
 * never from the request: {@link #mine} from the session's own employee record, {@link #ofPreview}
 * from the staff user's own open support session, whose reason and audit entry were written when it
 * opened (SRS-17-6, SRS-17-8, l.496). Locked results only (BR-ESEVA-004). Numbers of the owning
 * modules are read, never recomputed: the eSeva module only reads their tables, so no pay rule
 * lives here.
 */
@Service
@Loggable
public class MemberOverviewService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int APRIL = Month.APRIL.getValue();

    /** The latest locked month; {@code latest} of the overview is null before the first lock. */
    public record Latest(String yymm, BigDecimal gross, BigDecimal deductions, BigDecimal net) {}

    /** The CPF tile: the Payroll ledger of the latest year held; null: no ledger. */
    public record Cpf(String finYear, BigDecimal balance, BigDecimal vpfMonthly) {}

    /** The loan tile: all open loans, the first named with its instalments; null: no open loan. */
    public record Loan(
            BigDecimal outstanding, String number, String type, int paid, int total, int open) {}

    /** The TDS tile of the current year; {@code amount} null: the pay code is not configured. */
    public record Tds(String finYear, BigDecimal amount, String regime) {}

    /** Open requests by the submission type (SRS-17-17). */
    public record Requests(long open, long hrms, long cpf, long other) {}

    /** One row of My Requests, from the member's own submissions. */
    public record RequestRow(String type, String value, String status, Instant submittedAt) {}

    /**
     * One row of My Documents. {@code kind}: PAY_SLIP, FORM_16, FORM_12BA, CPF_SLIP. {@code state}:
     * FINAL, ISSUED, ISSUED_NO_COPY (OD-153: released before issued copies were kept, no PDF),
     * NOT_YET_AVAILABLE, NOT_DUE, NOT_AVAILABLE. No file is offered until LIB-1.
     */
    public record DocumentRow(String kind, String period, Instant issued, String state, Long id) {}

    /** The whole overview. */
    public record Overview(
            Latest latest,
            Cpf cpf,
            Loan loan,
            Tds tds,
            Requests requests,
            List<RequestRow> myRequests,
            List<DocumentRow> documents) {}

    /** The rows of My Requests and the locked months of My Documents. */
    static final int ROWS = 6;

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final Clock clock;

    public MemberOverviewService(JdbcTemplate jdbc, Authorization policy, Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.clock = clock;
    }

    /** E-01: the signed-in member's own overview. */
    @Transactional(readOnly = true)
    public Overview mine(AccessContext context) {
        policy.require(context, "E-01", "SELF");
        if (context.employeeId() == null) {
            throw new FciException(
                    ApiErrorCode.PERMISSION_DENIED, new Object[] {"E-01", "SELF"}, List.of(), null);
        }
        return overview(context.employeeId());
    }

    /**
     * E-01 support preview: the overview of the member of the user's own open session; another
     * user's session, or a closed one, reads as not found.
     */
    @Transactional(readOnly = true)
    public Overview ofPreview(AccessContext context, long sessionId) {
        policy.require(context, "E-01", "VIEW");
        List<Long> member =
                jdbc.queryForList(
                        "SELECT employee_id FROM ess_session WHERE session_id = ? AND session_owner"
                            + " = 'STAFF' AND opened_by_staff_user_id = ? AND closed_at IS NULL",
                        Long.class,
                        sessionId,
                        context.userId());
        if (member.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return overview(member.getFirst());
    }

    private Overview overview(long member) {
        YearMonth month = YearMonth.from(LocalDate.now(clock.withZone(IST)));
        String year = finYear(month);
        Latest latest = latest(member);
        return new Overview(
                latest,
                cpf(member),
                loan(member),
                tds(member, month, year),
                requests(member),
                myRequests(member),
                documents(member, month));
    }

    /** "2026-27" for any month from April 2026 to March 2027. */
    static String finYear(YearMonth month) {
        int start = month.getMonthValue() >= APRIL ? month.getYear() : month.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    private Latest latest(long member) {
        return jdbc
                .query(
                        "SELECT r.payroll_yymm, r.gross_earnings, r.total_deductions, r.net_pay"
                                + " FROM payroll_result r WHERE r.employee_id = ? AND r.run_type ="
                                + " 'ACTUAL' AND r.result_status = 'CURRENT' AND r.is_locked = 'Y'"
                                + " ORDER BY r.payroll_yymm DESC FETCH FIRST 1 ROWS ONLY",
                        (rs, i) ->
                                new Latest(
                                        rs.getString(1),
                                        rs.getBigDecimal(2),
                                        rs.getBigDecimal(3),
                                        rs.getBigDecimal(4)),
                        member)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Cpf cpf(long member) {
        return jdbc
                .query(
                        "SELECT fin_year, COALESCE(closing_balance_employee,"
                                + " opening_balance_employee, 0) + COALESCE("
                                + "closing_balance_employer, opening_balance_employer, 0) +"
                                + " COALESCE(closing_balance_vpf, opening_balance_vpf, 0),"
                                + " vcpf_subscription_amt FROM employee_pf_header WHERE employee_id"
                                + " = ? AND fund_type = 'CPF' ORDER BY fin_year DESC, pf_header_id"
                                + " DESC FETCH FIRST 1 ROWS ONLY",
                        (rs, i) ->
                                new Cpf(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)),
                        member)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Loan loan(long member) {
        List<Loan> open =
                jdbc.query(
                        "SELECT h.principal_outstanding + COALESCE(h.interest_outstanding, 0),"
                                + " h.loan_number, t.loan_type_code, h.paid_instalments,"
                                + " h.num_principal_instalments FROM loan_header h JOIN"
                                + " loan_type_master t ON t.loan_type_id = h.loan_type_id WHERE"
                                + " h.employee_id = ? AND h.loan_status = 'ACTIVE' ORDER BY"
                                + " h.loan_id",
                        (rs, i) ->
                                new Loan(
                                        rs.getBigDecimal(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getInt(4),
                                        rs.getInt(5),
                                        1),
                        member);
        if (open.isEmpty()) {
            return null;
        }
        Loan first = open.getFirst();
        BigDecimal total =
                open.stream().map(Loan::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Loan(
                total, first.number(), first.type(), first.paid(), first.total(), open.size());
    }

    private Tds tds(long member, YearMonth month, String year) {
        LocalDate today = month.atEndOfMonth();
        int start = Integer.parseInt(year.substring(0, 4));
        List<BigDecimal> code =
                jdbc.queryForList(
                        "SELECT v.numeric_attribute FROM reference_value v JOIN reference_list l ON"
                                + " l.list_id = v.list_id WHERE l.list_code ="
                                + " 'STATUTORY_DEDUCTION' AND v.value_code = 'TDS' AND"
                                + " v.numeric_attribute IS NOT NULL AND v.effective_from <= ? AND"
                                + " (v.effective_to IS NULL OR v.effective_to >= ?) ORDER BY"
                                + " v.effective_from DESC",
                        BigDecimal.class,
                        Date.valueOf(today),
                        Date.valueOf(today));
        BigDecimal amount =
                code.isEmpty()
                        ? null
                        : jdbc.queryForObject(
                                "SELECT COALESCE(SUM(ABS(rc.amount)), 0) FROM payroll_result r JOIN"
                                    + " payroll_result_component rc ON rc.payroll_result_id ="
                                    + " r.payroll_result_id AND rc.period_date = r.period_date AND"
                                    + " rc.org_unit_id = r.org_unit_id JOIN pay_component c ON"
                                    + " c.component_id = rc.component_id WHERE r.employee_id = ?"
                                    + " AND r.run_type = 'ACTUAL' AND r.result_status = 'CURRENT'"
                                    + " AND r.is_locked = 'Y' AND r.period_date >= ? AND"
                                    + " r.period_date <= ? AND c.legacy_pay_code = ?",
                                BigDecimal.class,
                                member,
                                Date.valueOf(LocalDate.of(start, APRIL, 1)),
                                Date.valueOf(today),
                                code.getFirst());
        List<String> regime =
                jdbc.queryForList(
                        "SELECT regime_code FROM it_regime_detail WHERE employee_id = ? AND"
                                + " fin_year = ?",
                        String.class,
                        member,
                        year);
        return new Tds(year, amount, regime.isEmpty() ? null : regime.getFirst().strip());
    }

    private Requests requests(long member) {
        List<String> open =
                jdbc.queryForList(
                        "SELECT submission_type FROM ess_submission WHERE employee_id = ? AND"
                                + " submission_status IN ('SUBMITTED', 'ROUTED')",
                        String.class,
                        member);
        long hrms = open.stream().filter("HRMS_UPDATE_REQUEST"::equals).count();
        long cpf = open.stream().filter(t -> t.startsWith("CPF_")).count();
        return new Requests(open.size(), hrms, cpf, open.size() - hrms - cpf);
    }

    private List<RequestRow> myRequests(long member) {
        return jdbc.query(
                "SELECT submission_type, submitted_value, submission_status, submitted_at FROM"
                        + " ess_submission WHERE employee_id = ? ORDER BY submission_id DESC FETCH"
                        + " FIRST "
                        + ROWS
                        + " ROWS ONLY",
                (rs, i) ->
                        new RequestRow(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getTimestamp(4).toInstant()),
                member);
    }

    private List<DocumentRow> documents(long member, YearMonth month) {
        List<DocumentRow> rows = new ArrayList<>();
        String open = yymm(month);
        // BR-ESEVA-004: the month not yet locked is listed so the member knows why it is missing.
        List<Instant> locked = new ArrayList<>();
        List<String> months =
                jdbc.query(
                        "SELECT r.payroll_yymm, c.locked_at FROM payroll_result r JOIN"
                                + " payroll_cycle_instance c ON c.cycle_instance_id ="
                                + " r.cycle_instance_id WHERE r.employee_id = ? AND r.run_type ="
                                + " 'ACTUAL' AND r.result_status = 'CURRENT' AND r.is_locked = 'Y'"
                                + " ORDER BY r.payroll_yymm DESC FETCH FIRST "
                                + ROWS
                                + " ROWS ONLY",
                        (rs, i) -> {
                            java.sql.Timestamp at = rs.getTimestamp(2);
                            locked.add(at == null ? null : at.toInstant());
                            return rs.getString(1);
                        },
                        member);
        if (!months.contains(open)) {
            rows.add(new DocumentRow("PAY_SLIP", open, null, "NOT_YET_AVAILABLE", null));
        }
        for (int i = 0; i < months.size(); i++) {
            rows.add(new DocumentRow("PAY_SLIP", months.get(i), locked.get(i), "FINAL", null));
        }
        rows.addAll(
                jdbc.query(
                        "SELECT fin_year, released_at, form16_id, report_output_id FROM form16"
                                + " WHERE employee_id = ? AND release_status = 'RELEASED' ORDER BY"
                                + " fin_year DESC FETCH FIRST 3 ROWS ONLY",
                        (rs, i) ->
                                new DocumentRow(
                                        "FORM_16",
                                        rs.getString(1),
                                        rs.getTimestamp(2) == null
                                                ? null
                                                : rs.getTimestamp(2).toInstant(),
                                        // OD-153: released before issued copies were
                                        // kept: no PDF on file.
                                        rs.getObject(4) == null ? "ISSUED_NO_COPY" : "ISSUED",
                                        rs.getLong(3)),
                        member));
        rows.add(new DocumentRow("FORM_16", finYear(month), null, "NOT_DUE", null));
        // SRS-17-1: Form 12BA has no member read yet, and the CPF Slip layout is open (Q-07-10).
        rows.add(
                new DocumentRow(
                        "FORM_12BA", finYear(month.minusYears(1)), null, "NOT_AVAILABLE", null));
        rows.add(new DocumentRow("CPF_SLIP", finYear(month), null, "NOT_AVAILABLE", null));
        return rows;
    }

    private static String yymm(YearMonth month) {
        return String.format("%04d%02d", month.getYear(), month.getMonthValue());
    }
}
