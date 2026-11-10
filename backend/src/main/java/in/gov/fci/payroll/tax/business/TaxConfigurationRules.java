package in.gov.fci.payroll.tax.business;

import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The checks of the tax configuration held on M-18 (SRS-7-2, SRS-7-4, SRS-7-20, SRS-7-26;
 * BR-TAX-002, BR-TAX-006; DDL {@code it_section_master}, {@code pt_state_master}). Values are never
 * assumed: a section without a stated limit has none here (the DDL column is nullable), and nothing
 * is seeded.
 */
public final class TaxConfigurationRules {

    /** Section codes as the Act writes them: 80C, 80CCD(1B), 24(b), 10(13A). */
    private static final Pattern SECTION =
            Pattern.compile("^[0-9]{1,3}[A-Z]{0,5}(\\([0-9A-Za-z]{1,4}\\))?$");

    private static final Pattern STATE = Pattern.compile("^[A-Z]{2,10}$");

    /** DDL {@code regime_applicability}. */
    public static final Set<String> REGIMES = Set.of("OLD", "NEW", "BOTH");

    /** {@code max_deduction_amount NUMBER(12,2)}. */
    private static final BigDecimal LIMIT_MAX = new BigDecimal("9999999999.99");

    /** The one PT slab rule; its band sets are qualified by the state code (plan 08 D-7). */
    public static final String PT_SLAB_KEY = "PT.SLAB";

    private TaxConfigurationRules() {}

    /** SRS-7-20: a section code as the Act writes it. */
    public static void requireSectionCode(String code) {
        if (code == null || !SECTION.matcher(code).matches()) {
            throw new FciException(TaxErrorCode.SECTION_CODE);
        }
    }

    /** BR-TAX-002: a limit of rupees and paise, never negative; empty when the section has none. */
    public static void requireLimit(BigDecimal limit) {
        if (limit != null
                && (limit.signum() < 0 || limit.scale() > 2 || limit.compareTo(LIMIT_MAX) > 0)) {
            throw new FciException(TaxErrorCode.SECTION_LIMIT);
        }
    }

    /** DDL it_section_master: the regime the section applies under. */
    public static void requireRegime(String regime) {
        if (regime == null || !REGIMES.contains(regime)) {
            throw new FciException(TaxErrorCode.SECTION_REGIME);
        }
    }

    /**
     * Effective-dated master: a new version starts after the latest version of the section, which
     * then ends the day before; versions never overlap.
     */
    public static void requireAfter(String code, LocalDate from, Optional<LocalDate> latestFrom) {
        if (from == null || latestFrom.map(l -> !from.isAfter(l)).orElse(false)) {
            throw new FciException(
                    TaxErrorCode.SECTION_ORDER,
                    new Object[] {code, latestFrom.map(LocalDate::toString).orElse("-")},
                    List.of(),
                    null);
        }
    }

    /** SRS-7-4: a PT state: its code, name and a window whose end is not before its start. */
    public static void requireState(String code, String name, LocalDate from, LocalDate to) {
        if (code == null
                || !STATE.matcher(code).matches()
                || name == null
                || name.isBlank()
                || from == null
                || (to != null && to.isBefore(from))) {
            throw new FciException(TaxErrorCode.PT_STATE);
        }
    }

    /** BR-TAX-006: a state where PT applies is computed on the PT slab of its code; else none. */
    public static String slabKeyFor(boolean ptApplicable) {
        return ptApplicable ? PT_SLAB_KEY : null;
    }
}
