package in.gov.fci.payroll.payroll.business;

import in.gov.fci.payroll.configuration.api.AllowanceConfiguration;
import in.gov.fci.payroll.configuration.api.PayConfiguration;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The staff pay rules of the computation (C-02), PROVISIONAL until the full rule engine: earnings
 * Basic (101), DA (111), HRA (121) and CCA (131) from the M-04 rates in force for the period; no
 * deductions yet. Phase-4 events are applied: a salary stop or hold is not paid; a suspended
 * employee is paid the subsistence percentage of Basic (and DA on it), HRA and CCA continue.
 *
 * <p>Plan 16 phase 16d (SRS-1-4): CCA, NPA and Transport are paid only when the member's M-01
 * Payroll allowance flag is set: NPA = NPA % of (Basic basis + Grade Pay), at most the NPA ceiling
 * (M-23), and enters the DA base (SRS l.5409, l.5412, l.720: DA = (Basic + Grade Pay + Stagnation +
 * NPA) x DA rate); Transport = the M-22 monthly amount for the pay pattern and city class x (1 + DA
 * %). The stagnation increment is held inside Basic today (I-05 adds it to the Basic), so it
 * reaches the DA base through Basic and has no term of its own: a second term would count it twice
 * (PENDING Q-17-STAG, REF-3). A flag whose allowance has no rule here yet (washing, conveyance,
 * lunch subsidy, PLI) refuses the member, never pays zero (PENDING 16-allowances).
 *
 * <p>Pay status (SRS-1-7, SRS-1-41): a Salary Stop is not computed; a Salary Withheld (HOLD event)
 * and a member flagged Hold, Stop-Payment or Absconding on M-01 are computed, with the payment held
 * ({@link Outcome#paymentHold()}): the result is kept and the hold raised as an exception.
 *
 * <p>Accommodation (SRS-1-3, SRS l.5410, SRS-11-17): HRA is not paid, no line at all, where the
 * accommodation record in force suppresses it. A member with no record is paid HRA: no source says
 * otherwise (PENDING REF-1).
 *
 * <p>A missing rate or pay code is a configuration gap, never a zero: the member is not computed
 * ({@link PayDataException}, quarantined in the failure log) - v4.0: "fail cleanly, not silently
 * mis-compute". Amounts are rounded to the rupee (half up) per component. Pure: no framework, no
 * I/O.
 */
public final class PayRules {

    public static final int BASIC = 101;
    public static final int DA = 111;
    public static final int HRA = 121;
    public static final int CCA = 131;

    /** The M-01 allowance flags the engine computes (reference list ELIGIBILITY_FLAG). */
    public static final String FLAG_CCA = "CCA";

    public static final String FLAG_NPA = "NPA";
    public static final String FLAG_TRANSPORT = "TRANSPORT";

    public static final String COMPUTED = "COMPUTED";
    public static final String SKIPPED = "SKIPPED";

    /** One employee as the computation sees it. */
    public record Member(
            Long employeeId,
            BigDecimal basic,
            String payPattern,
            String cityClass,
            String employmentStatus,
            BigDecimal subsistencePercent,
            boolean salaryStopped,
            Set<String> allowanceFlags,
            String paymentHold,
            BigDecimal gradePay,
            boolean hraSuppressed) {

        public Member {
            allowanceFlags = allowanceFlags == null ? Set.of() : Set.copyOf(allowanceFlags);
            gradePay = gradePay == null ? BigDecimal.ZERO : gradePay;
        }

        /** A member with no Grade Pay and no accommodation record. */
        public Member(
                Long employeeId,
                BigDecimal basic,
                String payPattern,
                String cityClass,
                String employmentStatus,
                BigDecimal subsistencePercent,
                boolean salaryStopped,
                Set<String> allowanceFlags,
                String paymentHold) {
            this(
                    employeeId,
                    basic,
                    payPattern,
                    cityClass,
                    employmentStatus,
                    subsistencePercent,
                    salaryStopped,
                    allowanceFlags,
                    paymentHold,
                    BigDecimal.ZERO,
                    false);
        }
    }

    /** One signed component line with its provenance. */
    public record Line(
            int payCode, long componentId, BigDecimal amount, BigDecimal rate, BigDecimal basis) {}

    public record Outcome(
            String status,
            String skipReason,
            List<Line> lines,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal net,
            String paymentHold) {

        public Outcome(
                String status,
                String skipReason,
                List<Line> lines,
                BigDecimal gross,
                BigDecimal deductions,
                BigDecimal net) {
            this(status, skipReason, lines, gross, deductions, net, null);
        }
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final Set<String> COMPUTED_FLAGS = Set.of(FLAG_CCA, FLAG_NPA, FLAG_TRANSPORT);

    /**
     * SRS l.5410 "Government, FCI or Spouse Government accommodation", in the codes of DDL {@code
     * lease_accommodation_detail.accommodation_type}: the SRS outranks a row flagged 'N'.
     */
    private static final Set<String> HRA_SUPPRESSING_TYPES =
            Set.of("DEPARTMENTAL_QUARTERS", "COMPANY_LEASE", "SPOUSE");

    private PayRules() {}

    /**
     * Whether an accommodation record stops HRA (SRS-1-3, SRS l.5410, SRS-11-17): one of the types
     * l.5410 names, or a third-party or self-lease whose record is flagged suppressed (DDL {@code
     * hra_suppressed_flag}). The one test of the engine and the C-02 trace. "Adjust" has no rule
     * (KB Q-058).
     */
    public static boolean hraSuppressed(String accommodationType, boolean suppressedFlag) {
        return accommodationType != null
                && (suppressedFlag || HRA_SUPPRESSING_TYPES.contains(accommodationType));
    }

    public static Outcome compute(Member m, PayConfiguration.Snapshot config) {
        if (m.salaryStopped()) {
            return skipped("STOP_PAYMENT");
        }
        if (m.basic() == null) {
            return skipped("NO_BASIC_PAY");
        }
        if (m.basic().signum() <= 0) {
            throw new PayDataException(
                    "Basic pay must be positive (employee " + m.employeeId() + ")");
        }
        if (m.gradePay().signum() < 0) {
            throw new PayDataException(
                    "Grade Pay cannot be negative (employee " + m.employeeId() + ")");
        }
        BigDecimal basis = m.basic();
        BigDecimal gradePay = m.gradePay();
        if ("SUSPENDED".equals(m.employmentStatus())) {
            if (m.subsistencePercent() == null) {
                throw new PayDataException(
                        "Suspended without a subsistence percentage (employee "
                                + m.employeeId()
                                + ")");
            }
            // SRS-G-17: carried at paise, rounded once at the line.
            basis = carried(m.basic(), m.subsistencePercent());
            // OPEN-QUESTION: PENDING REF-2-SUB: no source says how Grade Pay stands in subsistence;
            // it follows the Basic basis, as the DA on that basis does.
            gradePay = carried(gradePay, m.subsistencePercent());
        }
        if (m.payPattern() == null) {
            throw new PayDataException(
                    "No pay pattern (IDA / CDA / FND) on the employee record (employee "
                            + m.employeeId()
                            + ")");
        }
        BigDecimal daRate =
                config.da(m.payPattern())
                        .orElseThrow(
                                () ->
                                        new PayDataException(
                                                "DA rate not seeded for pay pattern "
                                                        + m.payPattern(),
                                                PayDataException.RATE_SET));
        if (m.cityClass() == null) {
            throw new PayDataException(
                    "No city class for the employee's location (employee " + m.employeeId() + ")");
        }
        // SRS-1-3 (US-GAP-41): CDA-pattern staff have their own HRA rule (HRA_CDA).
        BigDecimal hraRate =
                config.hra(m.payPattern(), m.cityClass())
                        .orElseThrow(
                                () ->
                                        new PayDataException(
                                                "HRA rate not seeded for city class "
                                                        + m.cityClass()
                                                        + " ("
                                                        + m.payPattern()
                                                        + " pattern)",
                                                PayDataException.RATE_SET));
        for (String flag : m.allowanceFlags()) {
            if (!COMPUTED_FLAGS.contains(flag)) {
                throw new PayDataException(
                        "Allowance "
                                + flag
                                + " is flagged on M-01 but has no computation rule yet (employee "
                                + m.employeeId()
                                + ")");
            }
        }
        List<Line> lines = new ArrayList<>();
        lines.add(line(BASIC, config, round(basis), null, m.basic()));
        BigDecimal payBase = basis.add(gradePay);
        BigDecimal daBase = payBase;
        Line npa = null;
        if (m.allowanceFlags().contains(FLAG_NPA)) {
            BigDecimal amount = npaAmount(payBase, config);
            int code = flagCode(FLAG_NPA, config);
            npa = line(code, config, round(amount), config.allowances().npaPercent(), payBase);
            // SRS-G-17: the unrounded NPA enters the DA base, not the rupee of its line.
            daBase = payBase.add(amount);
        }
        lines.add(line(DA, config, percent(daBase, daRate), daRate, daBase));
        if (npa != null) {
            lines.add(npa);
        }
        if (!m.hraSuppressed()) {
            lines.add(line(HRA, config, percent(m.basic(), hraRate), hraRate, m.basic()));
        }
        if (m.allowanceFlags().contains(FLAG_CCA)) {
            if (!config.ccaSeeded()) {
                throw new PayDataException("CCA rates not seeded", PayDataException.RATE_SET);
            }
            BigDecimal cca = config.ccaAmount().get(m.cityClass());
            if (cca != null && cca.signum() > 0) {
                lines.add(line(CCA, config, round(cca), cca, null));
            }
        }
        if (m.allowanceFlags().contains(FLAG_TRANSPORT)) {
            lines.add(transport(m, daRate, config));
        }
        BigDecimal gross =
                lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deductions = BigDecimal.ZERO;
        return new Outcome(
                COMPUTED,
                null,
                List.copyOf(lines),
                gross,
                deductions,
                gross.subtract(deductions),
                m.paymentHold());
    }

    /**
     * A deduction as its owning module computed it (positive; written signed, SRS-DM-6). {@code
     * exact}: an instalment of a recovery schedule, taken as the schedule states it, so a final
     * instalment with paise closes the ledger at exactly zero (SRS-G-19, R-07); every other
     * deduction is rounded once to the rupee (SRS-G-17).
     */
    public record Deduction(
            int payCode, BigDecimal amount, BigDecimal rate, BigDecimal basis, boolean exact) {

        public Deduction(int payCode, BigDecimal amount, BigDecimal rate, BigDecimal basis) {
            this(payCode, amount, rate, basis, false);
        }
    }

    /**
     * Plan 16 phase 16e-2 (SRS-1-6, SRS-DM-6): the month's deductions taken from a computed
     * outcome: each a negative line of its pay code, the total deductions, and net = gross less
     * deductions. A deduction that is not positive, a pay code not configured (M-07) or a net below
     * zero refuses the member (SRS-1-8: never paid a negative net); a zero deduction is not
     * written.
     */
    public static Outcome withDeductions(
            Outcome outcome, List<Deduction> deductions, PayConfiguration.Snapshot config) {
        return withDeductions(outcome, List.of(), deductions, config);
    }

    /** An employer contribution: an earning and a deduction of the same positive amount. */
    public record Contribution(int earningPayCode, int deductionPayCode, BigDecimal amount) {}

    /**
     * Plan 18 phase 18b (SRS-9-21, BR-PEN-004): as {@link #withDeductions(Outcome, List,
     * PayConfiguration.Snapshot)}, with the employer contributions first: each an earning line in
     * gross and a deduction line of the same amount, so net pay is unchanged. A contribution that
     * is not positive, or whose pay codes are not configured, refuses the member.
     */
    public static Outcome withDeductions(
            Outcome outcome,
            List<Contribution> contributions,
            List<Deduction> deductions,
            PayConfiguration.Snapshot config) {
        return withDeductions(outcome, List.of(), contributions, deductions, config);
    }

    /** A reduction of an earning of the month (plan 19 phase 19d, D19-4), a positive amount. */
    public record Reduction(int payCode, BigDecimal amount) {}

    /**
     * Plan 19 phase 19d (SRS-LV-15, SRS-LV-27): the outcome with each earning line reduced by the
     * total of its reductions, rounded once to the rupee half-up (SRS-DM-6), never below zero; the
     * line keeps its amount before the reduction as its basis, and gross and net follow. A
     * reduction that is not positive, or of a pay code the month has no earning for, refuses the
     * member.
     */
    public static Outcome reduced(Outcome outcome, List<Reduction> reductions) {
        if (!COMPUTED.equals(outcome.status()) || reductions.isEmpty()) {
            return outcome;
        }
        for (Reduction r : reductions) {
            if (r.amount() == null || r.amount().signum() <= 0) {
                throw new PayDataException(
                        "Reduction of " + r.payCode() + " is not a positive amount: " + r.amount());
            }
            if (outcome.lines().stream()
                    .noneMatch(l -> l.payCode() == r.payCode() && l.amount().signum() > 0)) {
                throw new PayDataException(
                        "Reduction of " + r.payCode() + ": no earning of that pay code this month");
            }
        }
        List<Line> lines = new ArrayList<>();
        BigDecimal gross = outcome.gross();
        for (Line l : outcome.lines()) {
            BigDecimal cut = reductionOf(l.payCode(), reductions);
            if (cut.signum() == 0 || l.amount().signum() <= 0) {
                lines.add(l);
                continue;
            }
            BigDecimal amount = l.amount().subtract(round(cut)).max(BigDecimal.ZERO);
            gross = gross.subtract(l.amount().subtract(amount));
            lines.add(new Line(l.payCode(), l.componentId(), amount, l.rate(), l.amount()));
        }
        return new Outcome(
                COMPUTED,
                null,
                List.copyOf(lines),
                gross,
                outcome.deductions(),
                gross.subtract(outcome.deductions()),
                outcome.paymentHold());
    }

    /** The total reduction of {@code payCode} (zero when none). */
    public static BigDecimal reductionOf(int payCode, List<Reduction> reductions) {
        return reductions.stream()
                .filter(r -> r.payCode() == payCode)
                .map(Reduction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** A one-time earning of the month (plan 10 D10-6): an approved claim, the cafeteria line. */
    public record Earning(int payCode, BigDecimal amount) {}

    /**
     * As above, with the month's one-time earnings first: each an earning line in gross, rounded
     * once to the rupee half-up (SRS-DM-6). An earning that is not positive refuses the member. A
     * net below zero is returned as it is (SRS-1-8: quarantined by the run, not refused).
     */
    public static Outcome withDeductions(
            Outcome outcome,
            List<Earning> earnings,
            List<Contribution> contributions,
            List<Deduction> deductions,
            PayConfiguration.Snapshot config) {
        if (!COMPUTED.equals(outcome.status())
                || (earnings.isEmpty() && contributions.isEmpty() && deductions.isEmpty())) {
            return outcome;
        }
        List<Line> lines = new ArrayList<>(outcome.lines());
        BigDecimal gross = outcome.gross();
        BigDecimal total = BigDecimal.ZERO;
        for (Earning e : earnings) {
            if (e.amount() == null || e.amount().signum() <= 0) {
                throw new PayDataException(
                        "Earning " + e.payCode() + " is not a positive amount: " + e.amount());
            }
            BigDecimal amount = round(e.amount());
            lines.add(line(e.payCode(), config, amount, null, null));
            gross = gross.add(amount);
        }
        List<Line> contributionDeductions = new ArrayList<>();
        for (Contribution c : contributions) {
            if (c.amount() == null || c.amount().signum() < 0) {
                throw new PayDataException(
                        "Contribution "
                                + c.earningPayCode()
                                + " is not a positive amount: "
                                + c.amount());
            }
            if (c.amount().signum() == 0) {
                continue;
            }
            BigDecimal amount = round(c.amount());
            lines.add(line(c.earningPayCode(), config, amount, null, null));
            contributionDeductions.add(
                    line(c.deductionPayCode(), config, amount.negate(), null, null));
            gross = gross.add(amount);
            total = total.add(amount);
        }
        lines.addAll(contributionDeductions);
        for (Deduction d : deductions) {
            if (d.amount() == null || d.amount().signum() < 0) {
                throw new PayDataException(
                        "Deduction " + d.payCode() + " is not a positive amount: " + d.amount());
            }
            if (d.amount().signum() == 0) {
                continue;
            }
            BigDecimal amount = d.exact() ? d.amount() : round(d.amount());
            lines.add(line(d.payCode(), config, amount.negate(), d.rate(), d.basis()));
            total = total.add(amount);
        }
        // SRS-1-8 (BMAD TR-037): a negative net is computed and kept, never refused - the run
        // quarantines it for the Checker's review (CycleRuns NEGATIVE_NET, gate G6, C-08).
        BigDecimal net = gross.subtract(total);
        return new Outcome(
                COMPUTED, null, List.copyOf(lines), gross, total, net, outcome.paymentHold());
    }

    /**
     * NPA before rounding (SRS l.5412, l.720): NPA % of the base, at most the ceiling; both set on
     * M-23. Unrounded because it enters the DA base (SRS-G-17).
     */
    private static BigDecimal npaAmount(BigDecimal base, PayConfiguration.Snapshot config) {
        AllowanceConfiguration.Allowances a = config.allowances();
        if (a.npaPercent() == null) {
            throw new PayDataException("NPA % not configured (M-23 NPA_PCT)");
        }
        if (a.npaCeiling() == null) {
            throw new PayDataException("NPA ceiling not configured (M-23 NPA_CEILING)");
        }
        return carried(base, a.npaPercent()).min(a.npaCeiling());
    }

    /**
     * Transport: the M-22 monthly amount for the pattern and city class x (1 + DA %) (KB formulas).
     */
    private static Line transport(Member m, BigDecimal daRate, PayConfiguration.Snapshot config) {
        BigDecimal monthly =
                config.allowances()
                        .transport(m.payPattern(), m.cityClass())
                        .orElseThrow(
                                () ->
                                        new PayDataException(
                                                "Transport rate not configured for "
                                                        + m.payPattern()
                                                        + " city class "
                                                        + m.cityClass()
                                                        + " (M-22)"));
        BigDecimal amount =
                round(
                        monthly.multiply(
                                BigDecimal.ONE.add(
                                        daRate.divide(HUNDRED, 6, RoundingMode.HALF_UP))));
        return line(flagCode(FLAG_TRANSPORT, config), config, amount, daRate, monthly);
    }

    private static int flagCode(String flag, PayConfiguration.Snapshot config) {
        return config.allowances()
                .payCode(flag)
                .orElseThrow(
                        () ->
                                new PayDataException(
                                        "Pay code of allowance "
                                                + flag
                                                + " not configured (M-16 ELIGIBILITY_FLAG)"));
    }

    private static Line line(
            int payCode,
            PayConfiguration.Snapshot config,
            BigDecimal amount,
            BigDecimal rate,
            BigDecimal basis) {
        Long id = config.componentIds().get(payCode);
        if (id == null) {
            throw new PayDataException("Pay code " + payCode + " is not configured (M-07)");
        }
        return new Line(payCode, id, amount, rate, basis);
    }

    private static BigDecimal percent(BigDecimal amount, BigDecimal pct) {
        return round(amount.multiply(pct).divide(HUNDRED, 4, RoundingMode.HALF_UP));
    }

    /** A percentage carried at two decimals, never to the rupee (SRS-G-17, R-01). */
    private static BigDecimal carried(BigDecimal amount, BigDecimal pct) {
        return amount.multiply(pct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal round(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static Outcome skipped(String reason) {
        return new Outcome(SKIPPED, reason, List.of(), null, null, null);
    }
}
