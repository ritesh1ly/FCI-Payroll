package in.gov.fci.payroll.payroll.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * A deduction of the staff payroll, computed by the module that owns it (plan 16 phase 16e-2;
 * SRS-1-6: "CPF/PF and VPF, Income Tax (TDS), Professional Tax, licence fee, loan/advance recovery,
 * CPF advance recovery and penalty recovery … Net pay = total earnings less total deductions"). The
 * payroll engine owns this port (the owning modules depend on {@code payroll::api}, never the
 * reverse) and applies every implementation in {@link #order()} after the earnings.
 *
 * <p>Deterministic (BR-CYC-011 … 017): a source {@link #open opens} a run on the rules in force on
 * the period's first day, once per computation run; the run's {@link Run#versions()} enter the
 * run's rule-version-set hash, and every member of the run is computed on the same versions.
 *
 * <p>Never a default (R-RULE): a source whose rates or pay code are not configured, or whose data
 * is missing, throws {@link NotComputable} and the member is not computed (C-08), never paid
 * without the deduction.
 */
public interface StaffDeductions {

    /** One earning of the month as the engine computed it. */
    record Earning(int payCode, BigDecimal amount) {}

    /**
     * The member and the month's earnings the deduction is computed on.
     *
     * @param deducted the deductions (and employer contributions) the earlier sources took
     */
    record Member(
            long employeeId,
            Long locationId,
            String payPattern,
            YearMonth month,
            List<Earning> earnings,
            BigDecimal gross,
            BigDecimal deducted) {

        public Member {
            earnings = List.copyOf(earnings);
            deducted = deducted == null ? BigDecimal.ZERO : deducted;
        }

        /** A member before any deduction of the month. */
        public Member(
                long employeeId,
                Long locationId,
                String payPattern,
                YearMonth month,
                List<Earning> earnings,
                BigDecimal gross) {
            this(employeeId, locationId, payPattern, month, earnings, gross, BigDecimal.ZERO);
        }

        /**
         * The same member with the deductions the sources before this one took (plan 13 phase 13a:
         * a recovery that must keep the net pay above the floor, SRS-8-17).
         */
        public Member withDeducted(BigDecimal total) {
            return new Member(employeeId, locationId, payPattern, month, earnings, gross, total);
        }
    }

    /**
     * One deduction line, as a positive amount; the engine writes it signed (SRS-DM-6).
     *
     * @param payCode the legacy pay code it is recovered under (M-07)
     * @param rate the rate applied, or null
     * @param basis the amount it was computed on, or null
     * @param exact an instalment of a recovery schedule, taken as scheduled with its paise, so the
     *     deduction and the ledger agree and a final instalment closes it at zero (SRS-G-19);
     *     otherwise rounded once to the rupee (SRS-G-17)
     */
    record Line(int payCode, BigDecimal amount, BigDecimal rate, BigDecimal basis, boolean exact) {

        public Line(int payCode, BigDecimal amount, BigDecimal rate, BigDecimal basis) {
            this(payCode, amount, rate, basis, false);
        }
    }

    /**
     * An employer contribution shown under both earnings and deductions, so net pay is unchanged
     * (plan 18 phase 18b; SRS-9-21: the employer NPS "flows into gross, total deductions and the IT
     * forecast"). The sources after this one see it among the member's earnings.
     *
     * @param earningPayCode the code of the earning line (M-07)
     * @param deductionPayCode the code of the deduction line (M-07)
     * @param amount a positive amount
     */
    record Contribution(int earningPayCode, int deductionPayCode, BigDecimal amount) {}

    /**
     * A reduction of one earning line of the month (plan 19 phase 19d), as a positive amount: the
     * line is paid at its amount less the reduction, never below zero.
     *
     * @param payCode the earning's pay code (M-07)
     */
    record Reduction(int payCode, BigDecimal amount) {}

    /** The deduction cannot be computed for this member; the reason names what is missing. */
    class NotComputable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotComputable(String reason) {
            super(reason);
        }
    }

    /** A source pinned to the rules of one day, for every member of a run. */
    interface Run {

        /** The versions of the rules this run reads (enters the run's hash). */
        String versions();

        /** The member's deductions of the month; empty when none applies. */
        List<Line> deductions(Member member);

        /**
         * The member's employer contributions of the month, taken before this source's deductions;
         * none by default.
         */
        default List<Contribution> contributions(Member member) {
            return List.of();
        }

        /**
         * Reductions of earnings the engine computed for the month (plan 19 phase 19d; SRS-LV-15,
         * SRS-LV-27, D19-4: leave without pay, half-pay and extraordinary leave reduce pay), taken
         * first, before the one-time earnings and every source's contributions and deductions, so
         * CPF, TDS and Professional Tax are computed on the reduced pay; none by default.
         */
        default List<Reduction> reductions(Member member) {
            return List.of();
        }

        /**
         * One-time earnings paid in the month (plan 10 D10-6; SRS-11-12: an approved claim, the
         * month's cafeteria allowance), taken before every source's contributions and deductions,
         * so the later sources (TDS) see them; none by default.
         */
        default List<Earning> earnings(Member member) {
            return List.of();
        }
    }

    /** Where it comes in the order of recovery (lower first). */
    int order();

    /**
     * Gate G5 (SRS-15-18, SRS l.1524): the statutory sub-process this source computes ({@code
     * cycle_statutory_status.statutory_process}: CPF_PF, INCOME_TAX, PROFESSIONAL_TAX,
     * NPS_DEDUCTION), empty for a source that is none (loans, penalties, leave, claims).
     */
    default java.util.Optional<String> statutoryProcess() {
        return java.util.Optional.empty();
    }

    /** A run on the rules in force on {@code asOf} (the period's first day). */
    Run open(LocalDate asOf);
}
