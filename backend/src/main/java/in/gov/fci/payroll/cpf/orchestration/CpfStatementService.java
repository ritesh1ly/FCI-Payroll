package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.cpf.business.VpfRules;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E-01 CPF balance and statement (plan 14 phase 14e; SRS-17-16, SRS-17-17, BR-INT-007): the
 * member's CPF ledger of a financial year as Payroll keeps it - the opening and closing balance of
 * each part (employee, employer, VPF), the interest credited, and the year's postings month by
 * month. Read only; the CPF Slip layout is FCI's (PENDING 07j).
 */
@Service
@Loggable
public class CpfStatementService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern FIN_YEAR = Pattern.compile("^\\d{4}-\\d{2}$");

    /** One ledger of the year (a parent-zone change may have opened a second). */
    public record Ledger(
            long ledgerId,
            String memberStatus,
            BigDecimal openingEmployee,
            BigDecimal openingEmployer,
            BigDecimal openingVpf,
            BigDecimal closingEmployee,
            BigDecimal closingEmployer,
            BigDecimal closingVpf,
            BigDecimal interestCredited) {}

    /** One posting of the year. */
    public record Posting(
            String yymm,
            String paymentType,
            BigDecimal employee,
            BigDecimal employer,
            BigDecimal vpf) {}

    /** The statement of the year. */
    public record Statement(String finYear, List<Ledger> ledgers, List<Posting> postings) {}

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final Clock clock;

    public CpfStatementService(JdbcTemplate jdbc, Authorization policy, Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.clock = clock;
    }

    /** E-01: the member's own statement of {@code finYear} (null: the current year). */
    @Transactional(readOnly = true)
    public Statement mine(AccessContext context, String finYear) {
        policy.require(context, "E-01", "SELF");
        if (context.employeeId() == null) {
            throw new FciException(
                    ApiErrorCode.PERMISSION_DENIED, new Object[] {"E-01", "SELF"}, List.of(), null);
        }
        String year =
                finYear == null || finYear.isBlank()
                        ? VpfRules.finYear(YearMonth.from(LocalDate.now(clock.withZone(IST))))
                        : finYear.strip();
        if (!consecutive(year)) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        long me = context.employeeId();
        List<Ledger> ledgers =
                jdbc.query(
                        "SELECT pf_header_id, member_status, opening_balance_employee,"
                                + " opening_balance_employer, opening_balance_vpf,"
                                + " closing_balance_employee, closing_balance_employer,"
                                + " closing_balance_vpf, interest_credited FROM"
                                + " employee_pf_header WHERE employee_id = ? AND fin_year = ? AND"
                                + " fund_type = 'CPF' ORDER BY pf_header_id",
                        (rs, i) ->
                                new Ledger(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getBigDecimal(3),
                                        rs.getBigDecimal(4),
                                        rs.getBigDecimal(5),
                                        rs.getBigDecimal(6),
                                        rs.getBigDecimal(7),
                                        rs.getBigDecimal(8),
                                        rs.getBigDecimal(9)),
                        me,
                        year);
        List<Posting> postings =
                jdbc.query(
                        "SELECT d.payroll_yymm, d.payment_type, d.employee_contribution,"
                                + " d.employer_contribution, d.vpf_contribution FROM"
                                + " employee_pf_detail d JOIN employee_pf_header h ON"
                                + " h.pf_header_id = d.pf_header_id WHERE d.employee_id = ? AND"
                                + " h.fin_year = ? AND h.fund_type = 'CPF' ORDER BY d.payroll_yymm,"
                                + " d.pf_detail_id",
                        (rs, i) ->
                                new Posting(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getBigDecimal(3),
                                        rs.getBigDecimal(4),
                                        rs.getBigDecimal(5)),
                        me,
                        year);
        return new Statement(year, ledgers, postings);
    }

    /**
     * SRS-17-16 (UI audit): a financial year is YYYY-YY whose second part is the year after the
     * first ("2025-26"); "2025-30" or "2025-25" is refused, not read as some other year.
     */
    static boolean consecutive(String year) {
        if (year == null || !FIN_YEAR.matcher(year).matches()) {
            return false;
        }
        int start = Integer.parseInt(year.substring(0, 4));
        return Integer.parseInt(year.substring(5)) == (start + 1) % 100;
    }
}
