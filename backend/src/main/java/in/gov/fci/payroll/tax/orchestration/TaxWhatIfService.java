package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.SecureTokens;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import in.gov.fci.payroll.tax.orchestration.TaxComputationService.Forecast;
import in.gov.fci.payroll.tax.orchestration.TaxComputationService.IncomeView;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The what-if Income Tax Forecast (plan 08 phase 08-C09; SRS-7-28, FRS V0.5 §7.12.4, §17.6): the
 * member's forecast of a year computed again with a declaration amount, the regime or the income
 * varied, beside the actual one. It is a General (Simulation) Run: the run record (who, the member
 * and year, the what-if change, when) is kept in {@code rule_simulation} for audit, the result is
 * returned once and never stored, and actual TDS, payslips and posted figures are never touched.
 *
 * <ul>
 *   <li>the Payroll Maker or Checker runs it for a member of their offices (C-09 RUN, the run
 *       rights of the simulation screen);
 *   <li>the employee runs it on their own year (E-01 VIEW, where the declarations are made).
 * </ul>
 *
 * The run record is not a C-09 cycle simulation ({@code population_scope} "TAX;…", not "C09;…"), so
 * the C-09 tiles count the payroll simulations only (plan 08 D-08-10).
 */
@Service
@Loggable
public class TaxWhatIfService {

    static final String SCREEN = "C-09";
    static final String MEMBER_SCREEN = "E-01";

    /** The what-if income head shown in the varied inputs (never a stored head). */
    static final String SALARY_CHANGE = "WHAT-IF SALARY";

    static final String OTHER_INCOME = "WHAT-IF OTHER INCOME";

    private static final Pattern SECTION = Pattern.compile("^[0-9A-Za-z()]{1,20}$");
    private static final BigDecimal LIMIT = new BigDecimal("9999999999.99");
    private static final int MAX_CLAIMS = 50;

    /** A section's claimed amount instead of the approved one. */
    public record Claim(String sectionCode, BigDecimal amount) {}

    /**
     * The what-if change (SRS-7-28: "vary declarations, regime or income").
     *
     * @param regime another regime, or null for the approved one
     * @param claims sections whose claimed amount replaces the approved one (0 drops a claim)
     * @param salaryChange the year's taxable salary up (+) or down (-), or null
     * @param otherIncome other income added for the year, or null
     */
    public record WhatIf(
            Regime regime, List<Claim> claims, BigDecimal salaryChange, BigDecimal otherIncome) {}

    /** The run's record id and the two forecasts (actual first). */
    public record Result(long runId, Forecast actual, Forecast whatIf) {}

    private final TaxComputationService computation;
    private final EmployeeDirectory employees;
    private final Authorization policy;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public TaxWhatIfService(
            TaxComputationService computation,
            EmployeeDirectory employees,
            Authorization policy,
            JdbcTemplate jdbc,
            ObjectMapper json,
            Clock clock) {
        this.computation = computation;
        this.employees = employees;
        this.policy = policy;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** SRS-7-28: a Payroll Maker's or Checker's what-if for a member of their offices. */
    @Transactional
    public Result forMember(AccessContext context, String eis, String finYear, WhatIf change) {
        policy.require(context, SCREEN, "RUN");
        EmployeeDirectory.Member m =
                employees
                        .memberByEis(eis)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireRecordAt(context, SCREEN, "RUN", m.locationId());
        return run(context, m, finYear, change);
    }

    /** SRS-7-28: the employee's what-if on their own year. */
    @Transactional
    public Result mine(AccessContext context, String finYear, WhatIf change) {
        policy.require(context, MEMBER_SCREEN, "SELF");
        EmployeeDirectory.Member m =
                employees
                        .member(context.employeeId())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        return run(context, m, finYear, change);
    }

    private Result run(
            AccessContext context, EmployeeDirectory.Member m, String finYear, WhatIf change) {
        if (!amount(change.salaryChange(), true) || !amount(change.otherIncome(), false)) {
            throw invalid("an income amount is not valid");
        }
        Map<String, BigDecimal> claims = claims(change);
        List<IncomeView> extra = new ArrayList<>();
        if (change.salaryChange() != null && change.salaryChange().signum() != 0) {
            extra.add(new IncomeView(SALARY_CHANGE, change.salaryChange(), BigDecimal.ZERO));
        }
        if (change.otherIncome() != null && change.otherIncome().signum() != 0) {
            extra.add(new IncomeView(OTHER_INCOME, change.otherIncome(), BigDecimal.ZERO));
        }
        if (change.regime() == null && claims.isEmpty() && extra.isEmpty()) {
            throw invalid("nothing is varied");
        }
        String year = computation.yearOr(finYear);
        Forecast[] both = computation.whatIf(m, year, change.regime(), claims, extra);
        String body = json.writeValueAsString(change);
        long id = record(context.loginId(), "TAX;" + m.eis() + ";" + year, body);
        return new Result(id, both[0], both[1]);
    }

    /** The claims as a map, each checked: a section code, an amount in rupees and paise. */
    private static Map<String, BigDecimal> claims(WhatIf change) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (change.claims() == null) {
            return out;
        }
        if (change.claims().size() > MAX_CLAIMS) {
            throw invalid("at most " + MAX_CLAIMS + " sections");
        }
        for (Claim c : change.claims()) {
            if (c == null
                    || c.sectionCode() == null
                    || !SECTION.matcher(c.sectionCode()).matches()) {
                throw invalid("a section code is not valid");
            }
            if (c.amount() == null || !amount(c.amount(), false)) {
                throw invalid("the amount of " + c.sectionCode() + " is not valid");
            }
            if (out.put(c.sectionCode(), c.amount()) != null) {
                throw invalid(c.sectionCode() + " is given twice");
            }
        }
        return out;
    }

    /** Rupees and paise within NUMBER(12,2); negative only where {@code signed}; null passes. */
    private static boolean amount(BigDecimal v, boolean signed) {
        return v == null
                || (v.stripTrailingZeros().scale() <= 2
                        && v.abs().compareTo(LIMIT) <= 0
                        && (signed || v.signum() >= 0));
    }

    private static FciException invalid(String why) {
        return new FciException(TaxErrorCode.WHAT_IF, new Object[] {why}, List.of(), null);
    }

    /** The run record (requester, member and year, the change, when); results not retained. */
    private long record(String login, String scope, String change) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        String hash = SecureTokens.sha256Hex(scope + "|" + change);
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO rule_simulation (requested_by, requested_at,"
                                            + " population_scope, population_count, input_hash,"
                                            + " impact_summary, results_retained) VALUES (?, ?,"
                                            + " ?, 1, ?, ?, 'N')",
                                    new String[] {"simulation_id"});
                    ps.setString(1, login.length() <= 50 ? login : login.substring(0, 50));
                    ps.setTimestamp(2, Timestamp.from(clock.instant()));
                    ps.setString(3, scope);
                    ps.setString(4, hash);
                    ps.setCharacterStream(5, new StringReader(change), change.length());
                    return ps;
                },
                key);
        return GeneratedKeys.id(key);
    }
}
