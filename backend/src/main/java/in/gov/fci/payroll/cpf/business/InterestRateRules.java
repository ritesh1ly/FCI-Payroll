package in.gov.fci.payroll.cpf.business;

import in.gov.fci.payroll.cpf.business.model.PfInterestRate;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules of an M-19 PF interest rate (SRS-6-11, BR-CPF-033; SRS M-19 field table; DDL {@code
 * pf_interest_rate}). Pure: the service validates a proposal with them at submission and again at
 * approval.
 */
public final class InterestRateRules {

    /** The funds a rate is declared for (DDL {@code pf_interest_rate.fund_type} CHECK). */
    public static final List<String> FUNDS = List.of("CPF", "PF", "VPF", "GPF");

    private static final Pattern FIN_YEAR = Pattern.compile("^(\\d{4})-(\\d{2})$");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private InterestRateRules() {}

    /** A financial year April to March, as M-19 shows it (e.g. 2026-27). */
    public record FinYear(String code, LocalDate start, LocalDate end) {

        public boolean contains(LocalDate day) {
            return !day.isBefore(start) && !day.isAfter(end);
        }

        /** The financial year (April to March) that {@code day} falls in. */
        public static FinYear containing(LocalDate day) {
            int first = day.getMonthValue() >= 4 ? day.getYear() : day.getYear() - 1;
            return new FinYear(
                    String.format("%d-%02d", first, (first + 1) % 100),
                    LocalDate.of(first, 4, 1),
                    LocalDate.of(first + 1, 3, 31));
        }

        /** The financial year before this one. */
        public FinYear previous() {
            return containing(start.minusDays(1));
        }
    }

    /** M-19 Financial Year: YYYY-YY, the second year the one after the first. */
    public static FinYear finYear(String code) {
        Matcher m = code == null ? null : FIN_YEAR.matcher(code);
        if (m == null || !m.matches()) {
            throw new FciException(CpfErrorCode.FIN_YEAR);
        }
        int first = Integer.parseInt(m.group(1));
        if (Integer.parseInt(m.group(2)) != (first + 1) % 100) {
            throw new FciException(CpfErrorCode.FIN_YEAR);
        }
        return new FinYear(code, LocalDate.of(first, 4, 1), LocalDate.of(first + 1, 3, 31));
    }

    /** M-19 Rate %: from 0 to 100, at most two decimals (Numeric 5,2). */
    public static void requireRate(BigDecimal rate) {
        if (rate == null
                || rate.signum() < 0
                || rate.compareTo(HUNDRED) > 0
                || rate.stripTrailingZeros().scale() > 2) {
            throw new FciException(CpfErrorCode.RATE_VALUE);
        }
    }

    /** M-19 Applies to period: within the financial year; the end, if any, not before the start. */
    public static void requirePeriod(FinYear year, LocalDate from, LocalDate to) {
        if (from == null
                || !year.contains(from)
                || (to != null && (to.isBefore(from) || !year.contains(to)))) {
            throw new FciException(CpfErrorCode.RATE_PERIOD);
        }
    }

    /**
     * M-19 Bonus interest ("2.5% over 9.50% on Trust balance"): a percentage, over the FCI-declared
     * (Trust) rate only.
     */
    public static void requireBonus(PfInterestRate.Source source, BigDecimal bonus) {
        if (bonus == null) {
            return;
        }
        if (source != PfInterestRate.Source.FCI_TRUST) {
            throw new FciException(CpfErrorCode.BONUS_SOURCE);
        }
        requireRate(bonus);
    }

    /**
     * SRS-6-11 (settled in DDL v4.0): the FCI-declared rate applies to active and deputed members,
     * the EPFO-declared rate to inactive members.
     */
    public static String population(PfInterestRate.Source source) {
        return source == PfInterestRate.Source.FCI_TRUST ? "ACTIVE_AND_DEPUTED" : "INACTIVE";
    }

    /**
     * M-19 versions: a new rate of a year, fund and source starts after the latest approved one,
     * which it replaces from that date (no rate is slipped in before an approved one).
     */
    public static void requireAfter(LocalDate from, Optional<LocalDate> latestApprovedFrom) {
        if (latestApprovedFrom.isPresent() && !from.isAfter(latestApprovedFrom.get())) {
            throw new FciException(
                    CpfErrorCode.RATE_ORDER,
                    new Object[] {latestApprovedFrom.get().toString()},
                    List.of(),
                    null);
        }
    }

    /** The key one series of rates is versioned under: year, fund and source. */
    public static String seriesKey(String finYear, String fundType, PfInterestRate.Source source) {
        return finYear + "|" + fundType + "|" + source.name();
    }
}
