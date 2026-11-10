package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.configuration.api.RuleSet;
import in.gov.fci.payroll.cpf.business.EmployerShare;
import in.gov.fci.payroll.payroll.api.StaffDeductions;
import in.gov.fci.payroll.payroll.api.StatutoryPayCodes;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The member's CPF and VPF as deductions of the staff payroll (plan 16 phase 16e-2, plan 07 phase
 * 07i part 1; SRS-6-1, SRS-6-7, SRS-1-6, SRS-1-14, SRS-6-59, BR-CPF-040):
 *
 * <ul>
 *   <li>only a member whose provident fund is CPF (an NPS member's deduction is received from HRMS,
 *       module 18);
 *   <li>the CPF code must be present before CPF is deducted (SRS-1-14 ii, SRS-6-59): without it the
 *       member is refused;
 *   <li>employee contribution = M-18 CPF.RATE.EMPLOYEE % of the CPF wage base (the month's earnings
 *       whose pay code is in the CPF base, M-07 {@code in_cpf_base_flag}), rounded to the rupee;
 *   <li>VPF = the member's subscription of the financial year ({@code
 *       employee_pf_header.vcpf_subscription_amt}); employee + VPF above CPF.VPF.MAX % of the wage
 *       base refuses the member, never capped silently;
 *   <li>recovered under the STATUTORY_DEDUCTION pay codes CPF and VPF.
 * </ul>
 *
 * The employer share and its EPS / higher-pension split ({@link EmployerShare}) are checked here
 * and booked to the ledger when the cycle locks ({@link CpfLockEffect}, plan 07 07i part 2). A run
 * pins the rules and the pay codes of its day once.
 */
@Component
public class CpfContributionDeduction implements StaffDeductions {

    /** First in the order of recovery (SRS-1-6). */
    static final int ORDER = 10;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final RuleEngine rules;
    private final JdbcTemplate jdbc;

    public CpfContributionDeduction(RuleEngine rules, JdbcTemplate jdbc) {
        this.rules = rules;
        this.jdbc = jdbc;
    }

    @Override
    public int order() {
        return ORDER;
    }

    /** Gate G5 (SRS-15-18, SRS l.1524): this source is the CPF / PF sub-process. */
    @Override
    public java.util.Optional<String> statutoryProcess() {
        return java.util.Optional.of("CPF_PF");
    }

    @Override
    public Run open(LocalDate asOf) {
        RuleSet set = rules.snapshot(asOf);
        Optional<Integer> cpfCode = StatutoryPayCodes.of(jdbc, "CPF", asOf);
        Optional<Integer> vpfCode = StatutoryPayCodes.of(jdbc, "VPF", asOf);
        Map<Integer, Boolean> inBase = new HashMap<>();
        jdbc.query(
                "SELECT legacy_pay_code, in_cpf_base_flag FROM pay_component",
                rs -> {
                    inBase.put(rs.getInt(1), "Y".equals(rs.getString(2)));
                });
        int start = asOf.getMonthValue() >= 4 ? asOf.getYear() : asOf.getYear() - 1;
        String finYear = String.format("%d-%02d", start, (start + 1) % 100);
        String versions =
                set.hash() + "|cpf=" + cpfCode.orElse(null) + "|vpf=" + vpfCode.orElse(null);
        return new Run() {
            @Override
            public String versions() {
                return versions;
            }

            @Override
            public List<Line> deductions(Member member) {
                Map<String, Object> m =
                        jdbc
                                .queryForList(
                                        "SELECT e.provident_fund_type, e.cpf_code,"
                                            + " e.parent_zone_id, (SELECT"
                                            + " MAX(h.vcpf_subscription_amt) FROM"
                                            + " employee_pf_header h WHERE h.employee_id ="
                                            + " e.employee_id AND h.fin_year = ? AND h.fund_type ="
                                            + " 'CPF') AS vpf FROM employee e WHERE e.employee_id ="
                                            + " ?",
                                        finYear,
                                        member.employeeId())
                                .stream()
                                .findFirst()
                                .orElseThrow(() -> new NotComputable("CPF: employee not found"));
                if (!"CPF".equals(m.get("PROVIDENT_FUND_TYPE"))) {
                    return List.of();
                }
                Object code = m.get("CPF_CODE");
                if (code == null || code.toString().isBlank()) {
                    throw new NotComputable(
                            "CPF: no CPF code on the record (SRS-1-14, SRS-6-59; from HRMS)");
                }
                if (m.get("PARENT_ZONE_ID") == null) {
                    // The ledger the lock posts to is the parent zone's (SRS-6-59).
                    throw new NotComputable(
                            "CPF: no CPF parent zone on the record (SRS-6-59; from HRMS)");
                }
                BigDecimal base =
                        member.earnings().stream()
                                .filter(e -> inBase.getOrDefault(e.payCode(), false))
                                .map(Earning::amount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                RuleEngine.Context ctx = new RuleEngine.Context("STAFF", member.locationId());
                BigDecimal rate = scalar(set, "CPF.RATE.EMPLOYEE", ctx);
                BigDecimal employee = base.multiply(rate).divide(HUNDRED, 0, RoundingMode.HALF_UP);
                List<Line> lines = new ArrayList<>();
                if (employee.signum() > 0) {
                    lines.add(new Line(code(cpfCode, "CPF"), employee, rate, base));
                    // 07i part 2: the employer share is booked to the ledger at the lock from the
                    // same rules; a member whose share cannot be computed is refused here, before
                    // the lock (plan 07 D-17).
                    share(set, ctx, base);
                }
                BigDecimal vpf = (BigDecimal) m.get("VPF");
                if (vpf != null && vpf.signum() > 0) {
                    BigDecimal max = scalar(set, "CPF.VPF.MAX", ctx);
                    BigDecimal limit = base.multiply(max).divide(HUNDRED, 2, RoundingMode.HALF_UP);
                    if (employee.add(vpf).compareTo(limit) > 0) {
                        throw new NotComputable(
                                "CPF: employee contribution "
                                        + employee
                                        + " and VPF "
                                        + vpf
                                        + " exceed the VPF maximum of "
                                        + max
                                        + " % of the wage base "
                                        + base);
                    }
                    lines.add(
                            new Line(
                                    code(vpfCode, "VPF"),
                                    vpf.setScale(0, RoundingMode.HALF_UP),
                                    null,
                                    base));
                }
                return lines;
            }
        };
    }

    /**
     * SRS-6-1, SRS-6-7: the employer share and its pension split at the M-18 rates in force for the
     * member; refused when a rate is not configured or the split exceeds the share.
     */
    static EmployerShare share(RuleSet set, RuleEngine.Context ctx, BigDecimal base) {
        EmployerShare share =
                EmployerShare.of(
                        base,
                        scalar(set, "CPF.RATE.EMPLOYER", ctx),
                        scalar(set, "CPF.EPS.RATE", ctx),
                        scalar(set, "CPF.HIGHERPENSION.RATE", ctx));
        if (share.splitExceeds()) {
            throw new NotComputable(
                    "CPF: EPS and higher pension "
                            + share.pension()
                            + " exceed the employer contribution "
                            + share.employer()
                            + " (M-18 CPF.EPS.RATE, CPF.HIGHERPENSION.RATE, CPF.RATE.EMPLOYER)");
        }
        return share;
    }

    /** The scalar of a rule in force for the member; not configured or not applying refuses. */
    private static BigDecimal scalar(RuleSet set, String key, RuleEngine.Context ctx) {
        try {
            return set.resolve(key, ctx)
                    .map(RuleEngine.Resolved::scalar)
                    .filter(v -> v != null)
                    .orElseThrow(() -> new NotComputable("CPF: " + key + " does not apply here"));
        } catch (FciException notConfigured) {
            throw new NotComputable("CPF: " + key + " not configured (M-18)");
        }
    }

    private static int code(Optional<Integer> code, String deduction) {
        return code.orElseThrow(
                () ->
                        new NotComputable(
                                "CPF: pay code not configured (M-16 STATUTORY_DEDUCTION "
                                        + deduction
                                        + ")"));
    }
}
