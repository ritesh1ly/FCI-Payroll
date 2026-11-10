package in.gov.fci.payroll.allowance.business;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Claim adjudication of I-03 (plan 10 phase 10a; SRS-11-6, SRS-11-9, SRS-11-11, SRS-11-14,
 * SRS-11-16, SRS-16-14; KB AL-1 … AL-4). Pure rules, no data access.
 */
public final class ClaimRules {

    /** The M-07 Taxable values (DDL {@code pay_component.taxable_flag}). */
    private static final Set<String> TAXABLE = Set.of("Y", "N", "PARTLY");

    /** The M-15 periodicities (DDL {@code allowance_ceiling_norm.periodicity}). */
    public enum Periodicity {
        MONTHLY,
        QUARTERLY,
        ANNUAL,
        BLOCK_YEAR_4,
        ONE_TIME_PER_EVENT
    }

    /** A claim period, both days included. */
    public record Period(LocalDate from, LocalDate to) {

        public boolean overlaps(Period other) {
            return !from.isAfter(other.to()) && !other.from().isAfter(to);
        }
    }

    /**
     * The adjudication of one claim.
     *
     * @param entitlement what is still claimable in the periodicity window
     * @param payable the lesser of claimed and entitlement (SRS-11-9)
     * @param capped the payable is below the claimed amount (SRS-11-11: sent at the cap, never
     *     rejected for it)
     */
    public record Adjudication(BigDecimal entitlement, BigDecimal payable, boolean capped) {}

    /**
     * The tax impact of a claim (use case main flow l.2769, US-GAP-209; OD-61): what its payable
     * adds to the member's taxable earnings of the year.
     *
     * @param treatment Y, N or PARTLY (M-07 Taxable of the claim type's pay code, or the confirmed
     *     taxable flag of a medical claim, SRS-11-19); null when not configured
     * @param taxableAmount the payable added to the taxable earnings (zero when not taxable); null
     *     when the payable or the treatment is not known
     */
    public record TaxImpact(String treatment, BigDecimal taxableAmount) {}

    private ClaimRules() {}

    /**
     * US-GAP-209 (OD-61): the confirmed taxable flag of a medical claim rules (SRS-11-19);
     * otherwise the M-07 Taxable of the claim type's pay code: N adds nothing, Y and PARTLY add the
     * payable (the exempt part of a PARTLY component is the tax computation's, as for every
     * earning). No pay code, or no payable yet: not known, never assumed.
     */
    public static TaxImpact taxImpact(
            BigDecimal payable, String componentTaxable, Boolean confirmedTaxable) {
        String treatment =
                confirmedTaxable != null
                        ? (confirmedTaxable ? "Y" : "N")
                        : TAXABLE.contains(String.valueOf(componentTaxable))
                                ? componentTaxable
                                : null;
        if (treatment == null || payable == null) {
            return new TaxImpact(treatment, null);
        }
        return new TaxImpact(
                treatment, "N".equals(treatment) ? BigDecimal.ZERO.setScale(2) : payable);
    }

    /**
     * D10-2: the window the ceiling applies to, around the claim's start: the calendar month, the
     * quarter of the financial year, the financial year, four financial years from April of a year
     * divisible by four, or the claim alone.
     */
    public static Period window(Periodicity periodicity, Period claim) {
        LocalDate start = claim.from();
        return switch (periodicity) {
            case MONTHLY -> {
                YearMonth m = YearMonth.from(start);
                yield new Period(m.atDay(1), m.atEndOfMonth());
            }
            case QUARTERLY -> {
                LocalDate fy = financialYearStart(start);
                int quarter = (int) (ChronoUnit.MONTHS.between(fy, start.withDayOfMonth(1)) / 3);
                LocalDate from = fy.plusMonths(3L * quarter);
                yield new Period(from, from.plusMonths(3).minusDays(1));
            }
            case ANNUAL -> {
                LocalDate fy = financialYearStart(start);
                yield new Period(fy, fy.plusYears(1).minusDays(1));
            }
            case BLOCK_YEAR_4 -> {
                int fyYear = financialYearStart(start).getYear();
                LocalDate from = LocalDate.of(fyYear - Math.floorMod(fyYear, 4), Month.APRIL, 1);
                yield new Period(from, from.plusYears(4).minusDays(1));
            }
            case ONE_TIME_PER_EVENT -> claim;
        };
    }

    /** The first of April starting the financial year of {@code day}. */
    public static LocalDate financialYearStart(LocalDate day) {
        int year = day.getMonthValue() >= 4 ? day.getYear() : day.getYear() - 1;
        return LocalDate.of(year, Month.APRIL, 1);
    }

    /**
     * SRS-11-9 / SRS-11-11 (AL-1, AL-2): the entitlement is the ceiling less what the member's
     * other live claims of the type already take in the window (never below zero); the payable is
     * the lesser of the claimed amount and the entitlement, capped not rejected.
     */
    public static Adjudication adjudicate(
            BigDecimal ceiling, BigDecimal alreadyPayable, BigDecimal claimed) {
        if (ceiling == null || ceiling.signum() <= 0) {
            throw new IllegalArgumentException("the ceiling is a positive amount");
        }
        if (claimed == null || claimed.signum() <= 0) {
            throw new IllegalArgumentException("the claimed amount is a positive amount");
        }
        BigDecimal taken = alreadyPayable == null ? BigDecimal.ZERO : alreadyPayable;
        BigDecimal entitlement = ceiling.subtract(taken).max(BigDecimal.ZERO);
        BigDecimal payable = claimed.min(entitlement).setScale(2, RoundingMode.HALF_UP);
        return new Adjudication(
                entitlement.setScale(2, RoundingMode.HALF_UP),
                payable,
                payable.compareTo(claimed) < 0);
    }

    /** SRS-11-6 (AL-3): the first live claim whose period overlaps the new one, or -1. */
    public static int overlapping(Period claim, List<Period> live) {
        for (int i = 0; i < live.size(); i++) {
            if (claim.overlaps(live.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * SRS-11-14 (AL-4, D10-4): the required document types of the claim type not attached, in the
     * order configured. {@code required} holds list values {@code <CLAIM_TYPE>.<DOCUMENT_TYPE>}.
     */
    public static List<String> missingDocuments(
            String claimType, Collection<String> required, Set<String> attachedTypes) {
        List<String> missing = new ArrayList<>();
        String prefix = claimType + ".";
        for (String r : required) {
            if (r.startsWith(prefix)) {
                String type = r.substring(prefix.length());
                if (!type.isEmpty() && !attachedTypes.contains(type)) {
                    missing.add(type);
                }
            }
        }
        return missing;
    }

    /**
     * A claim's period and amount as they must be: the end not before the start, a positive amount
     * of at most two decimals; empty when valid, else what is wrong.
     */
    public static Optional<String> defect(Period period, BigDecimal amount) {
        if (period == null || period.from() == null || period.to() == null) {
            return Optional.of("the claim period is required");
        }
        if (period.to().isBefore(period.from())) {
            return Optional.of("the claim period ends before it starts");
        }
        if (amount == null || amount.signum() <= 0) {
            return Optional.of("the claimed amount is a positive amount");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            return Optional.of("the claimed amount has at most two decimals");
        }
        if (amount.compareTo(new BigDecimal("9999999999.99")) > 0) {
            return Optional.of("the claimed amount is too large");
        }
        return Optional.empty();
    }

    /**
     * SRS-11-5: no payment of a medical claim to a member who has retired or separated: the member
     * is not in service on the claim's end, or retired before it.
     *
     * <p>OPEN-QUESTION (CLR-67, US-GAP-214): a claim whose period runs past the retirement date is
     * not refused (paid as approved in HRMS) until FCI says otherwise.
     */
    public static boolean retired(String employmentStatus, LocalDate retirementDate, Period claim) {
        boolean out = "SEPARATED".equals(employmentStatus) || "DISMISSED".equals(employmentStatus);
        return out || (retirementDate != null && retirementDate.isBefore(claim.from()));
    }

    /**
     * I-03 Employee / Labour Number (SRS l.2796, US-GAP-216; OD-62): an active member is one in
     * service; every other employment status (separated, dismissed, retired, absconding, suspended,
     * transferred out) is not.
     */
    public static boolean active(String employmentStatus) {
        return "IN_SERVICE".equals(employmentStatus);
    }

    /**
     * SRS-11-16 (D10-11): the month's amount of a monthly selection in force from {@code from} to
     * {@code to} (null = open): full for a whole month, days in force ÷ days of the month for a
     * part month, rounded once to the rupee half-up; zero outside it.
     */
    public static BigDecimal proRata(
            BigDecimal monthly, LocalDate from, LocalDate to, YearMonth month) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        LocalDate start = from.isAfter(first) ? from : first;
        LocalDate end = to == null || to.isAfter(last) ? last : to;
        if (end.isBefore(start)) {
            return BigDecimal.ZERO;
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        return monthly.multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(month.lengthOfMonth()), 0, RoundingMode.HALF_UP);
    }

    /**
     * SRS-11-16, SRS-G-33: the differential of one locked month of a cafeteria selection, pay code
     * by pay code: what is due in the month less what was already paid for it, in the run, through
     * Supplementary Payment or by an earlier arrear. A supplementary payment entered without a pay
     * code ({@code unattributed}) counts against the amounts still due, in pay-code order, so it is
     * never paid again. Codes with no difference are left out.
     */
    public static Map<Integer, BigDecimal> cafeteriaDifferential(
            Map<Integer, BigDecimal> due, Map<Integer, BigDecimal> paid, BigDecimal unattributed) {
        Map<Integer, BigDecimal> out = new TreeMap<>();
        Set<Integer> codes = new TreeSet<>(due.keySet());
        codes.addAll(paid.keySet());
        BigDecimal left = unattributed;
        for (Integer code : codes) {
            BigDecimal difference =
                    due.getOrDefault(code, BigDecimal.ZERO)
                            .subtract(paid.getOrDefault(code, BigDecimal.ZERO));
            if (difference.signum() > 0 && left.signum() > 0) {
                BigDecimal taken = difference.min(left);
                difference = difference.subtract(taken);
                left = left.subtract(taken);
            }
            if (difference.signum() != 0) {
                out.put(code, difference);
            }
        }
        return out;
    }
}
