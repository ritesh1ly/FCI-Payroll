package in.gov.fci.payroll.settlement.business;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Period;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Full &amp; Final Settlement rules (plan 11; SRS-10-5, 10-8, 10-13, 10-14, 10-15, 10-18, 10-23;
 * D11-4). Pure: no framework, no I/O.
 */
public final class SettlementRules {

    /** The UC-06 steps of DDL {@code case_stage} and the SRS-10-8 names (D11-4, CF-33). */
    public enum Stage {
        SEPARATION_RECEIVED(1),
        CLEARANCES_RAISED(2),
        CLEARANCES_RECEIVED(3),
        COMPUTED(4),
        CPF_LINKED(5),
        APPROVED(6),
        POSTED(7),
        CLOSED(8);

        private final int number;

        Stage(int number) {
            this.number = number;
        }

        public int number() {
            return number;
        }

        public static Stage of(int number) {
            for (Stage s : values()) {
                if (s.number == number) {
                    return s;
                }
            }
            throw new IllegalArgumentException("No case stage " + number);
        }
    }

    /** The clearances raised for every case (SRS-10-27; DDL settlement_clearance comment). */
    public static final Set<String> CLEARANCES =
            Set.of(
                    "LOANS_ADVANCES",
                    "PENALTY_RECOVERY",
                    "ALLOWANCES",
                    "ESTABLISHMENT_NO_DUES",
                    "CPF_FINAL_STATUS");

    /** The clearance statuses that no longer hold a case back (SRS-10-14). */
    private static final Set<String> SETTLED = Set.of("RECEIVED", "NOT_APPLICABLE", "EXCLUDED");

    private static final BigDecimal FIFTEEN = BigDecimal.valueOf(15);
    private static final BigDecimal TWENTY_SIX = BigDecimal.valueOf(26);

    private SettlementRules() {}

    /** SRS-10-18: the stage moves forward only, one completing event at a time (or stays). */
    public static boolean mayMove(Stage from, Stage to) {
        return to.number() >= from.number();
    }

    /** SRS-10-14: whether every clearance is received, not applicable or excluded with a reason. */
    public static boolean clearancesSettled(Collection<String> statuses) {
        return statuses.stream().allMatch(SETTLED::contains);
    }

    /**
     * SRS-10-13: payroll net payable = pending salary + leave encashment + arrears + ex-gratia -
     * the recoveries adjusted against payroll dues. Gratuity is never part of it.
     */
    public static BigDecimal netPayable(
            BigDecimal pendingSalary,
            BigDecimal leaveEncashment,
            BigDecimal arrears,
            BigDecimal exGratia,
            BigDecimal recoveriesFromPayroll) {
        return zero(pendingSalary)
                .add(zero(leaveEncashment))
                .add(zero(arrears))
                .add(zero(exGratia))
                .subtract(zero(recoveriesFromPayroll));
    }

    /** SRS-10-6 / 10-23: a negative net payable is a net recovery. */
    public static boolean isNetRecovery(BigDecimal net) {
        return net.signum() < 0;
    }

    /**
     * SRS-10-15 (BR-FNF-008): gratuity = last drawn (Basic + DA) x 15 / 26 x completed years of
     * qualifying service, a part-year of more than six months counted as a year, at most the
     * ceiling, rounded once to the rupee half-up. Empty when the qualifying service or the ceiling
     * is not there: never a default (SRS-10-14, Q-087).
     *
     * <p>Deep review DR-4: the part-year is judged on months and days, so six months and one day is
     * more than six months; six months exactly is not.
     *
     * @param service the qualifying service as years, months (0 - 11) and days (0 - 30), e.g.
     *     {@code Period.between(start, end)}
     */
    public static Optional<BigDecimal> gratuity(
            BigDecimal lastBasicAndDa, Period service, Optional<BigDecimal> ceiling) {
        if (lastBasicAndDa == null || service == null || ceiling.isEmpty()) {
            return Optional.empty();
        }
        if (service.isNegative() || service.getMonths() > 11 || service.getDays() > 30) {
            return Optional.empty();
        }
        boolean partYear =
                service.getMonths() > 6 || (service.getMonths() == 6 && service.getDays() > 0);
        int counted = service.getYears() + (partYear ? 1 : 0);
        BigDecimal amount =
                lastBasicAndDa
                        .multiply(FIFTEEN)
                        .multiply(BigDecimal.valueOf(counted))
                        .divide(TWENTY_SIX, 10, RoundingMode.HALF_UP)
                        .setScale(0, RoundingMode.HALF_UP);
        return Optional.of(amount.min(ceiling.get()));
    }

    /** SRS-10-5: settlement runs only for a member with a separation date who is not in service. */
    public static Optional<String> settleable(
            java.time.LocalDate separationDate, String employmentStatus) {
        if (separationDate == null) {
            return Optional.of("NO_SEPARATION_DATE");
        }
        if ("IN_SERVICE".equals(employmentStatus)) {
            return Optional.of("ACTIVE_MEMBER");
        }
        return Optional.empty();
    }

    /** The case reference: FNF-&lt;year&gt;-&lt;number, five digits&gt;. */
    public static String caseReference(int year, long number) {
        return String.format("FNF-%04d-%05d", year, number);
    }

    private static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
