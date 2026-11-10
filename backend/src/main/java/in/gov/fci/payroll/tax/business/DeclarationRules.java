package in.gov.fci.payroll.tax.business;

import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.tax.business.model.ItDeclaration.Kind;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The rules of a member's income-tax declaration (I-07 / E-01; SRS-7-1, SRS-7-15, SRS-7-16,
 * SRS-7-20, SRS-7-24; BR-TAX-001, BR-TAX-002, BR-TAX-014). Limits come from the section master; a
 * declared amount above its limit is capped and shown to the member, never refused or cut without
 * notice.
 */
public final class DeclarationRules {

    /** {@code declared_amount NUMBER(14,2)}. */
    private static final BigDecimal AMOUNT_MAX = new BigDecimal("999999999999.99");

    private DeclarationRules() {}

    /** One line as the member declares it. */
    public record Line(Kind kind, String sectionCode, BigDecimal declared) {}

    /** The financial year of {@code day}, April to March, as {@code fin_year} holds it: 2026-27. */
    public static String finYearOf(LocalDate day) {
        int start =
                day.getMonthValue() >= Month.APRIL.getValue() ? day.getYear() : day.getYear() - 1;
        return String.format("%d-%02d", start, (start + 1) % 100);
    }

    /** SRS-7-1: a regime is chosen; a frozen regime stays as it is. */
    public static Regime requireRegime(Regime chosen, Optional<Regime> frozen, String finYear) {
        if (frozen.isPresent()) {
            if (chosen != null && chosen != frozen.get()) {
                throw new FciException(
                        TaxErrorCode.REGIME_FROZEN, new Object[] {finYear}, List.of(), null);
            }
            return frozen.get();
        }
        if (chosen == null) {
            throw new FciException(TaxErrorCode.REGIME_REQUIRED);
        }
        return chosen;
    }

    /** SRS-7-16: a declared amount in rupees and paise, above zero. */
    public static void requireAmount(BigDecimal amount) {
        if (amount == null
                || amount.signum() <= 0
                || amount.scale() > 2
                || amount.compareTo(AMOUNT_MAX) > 0) {
            throw new FciException(TaxErrorCode.DECLARATION_AMOUNT);
        }
    }

    /** One line per section and kind in a round (DDL {@code uk_it_declaration}). */
    public static void requireDistinct(List<Line> lines) {
        Set<String> seen = new HashSet<>();
        for (Line l : lines) {
            if (!seen.add(l.kind() + "|" + l.sectionCode())) {
                throw new FciException(
                        TaxErrorCode.DECLARATION_DUPLICATE,
                        new Object[] {l.sectionCode()},
                        List.of(),
                        null);
            }
        }
    }

    /**
     * SRS-7-20: the section is in the master today and applies under the regime ({@code
     * regimeApplicability} OLD, NEW or BOTH; empty when the section is not in force).
     */
    public static void requireSection(
            String sectionCode, Optional<String> regimeApplicability, Regime regime) {
        boolean applies =
                regimeApplicability
                        .map(r -> "BOTH".equals(r) || regime.name().equals(r))
                        .orElse(false);
        if (!applies) {
            throw new FciException(
                    TaxErrorCode.DECLARATION_SECTION,
                    new Object[] {sectionCode, regime.name().toLowerCase(java.util.Locale.ROOT)},
                    List.of(),
                    null);
        }
    }

    /**
     * SRS-7-16, BR-TAX-002: the allowed amount is the lower of the declared amount and the section
     * limit; a section without a limit allows what is declared.
     */
    public static BigDecimal allowed(BigDecimal declared, BigDecimal limit) {
        return limit == null || declared.compareTo(limit) <= 0 ? declared : limit;
    }

    /** SRS-7-16: the line was capped, so the member is told. */
    public static boolean capped(BigDecimal declared, BigDecimal allowed) {
        return allowed.compareTo(declared) < 0;
    }

    /** SRS-7-24: the Payroll Maker verifies a line at an amount from 0 up to its allowed amount. */
    public static void requireVerified(BigDecimal verified, BigDecimal allowed) {
        if (verified == null
                || verified.signum() < 0
                || verified.scale() > 2
                || verified.compareTo(allowed) > 0) {
            throw new FciException(TaxErrorCode.VERIFIED_AMOUNT);
        }
    }

    /**
     * SRS-7-14: a line of an approved round is reversed at the proof deadline when no proof was
     * furnished. {@code deadline} is the last day proofs are accepted; empty when no deadline is
     * configured (nothing is reversed on an assumed date).
     */
    public static boolean reversible(
            boolean hasProof, Optional<LocalDate> deadline, LocalDate today) {
        return !hasProof && deadline.map(today::isAfter).orElse(false);
    }
}
