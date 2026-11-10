package in.gov.fci.payroll.cpf.business;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * SRS-6-9 (FRS V0.5 §13.3, DDL {@code employee_pf_header.interest_end_date}): for an ex-member the
 * CPF interest end date is set at the configured number of whole years from separation (three, rule
 * {@code CPF.INTEREST.EXMEMBER_YEARS}); never for a member on deputation; cleared on reinstatement.
 */
public final class InterestStop {

    /** The rule holding the number of years after separation. */
    public static final String YEARS_RULE = "CPF.INTEREST.EXMEMBER_YEARS";

    private InterestStop() {}

    /**
     * The interest end date of a member separated on {@code separation}: empty for a member on
     * deputation, or when the rule's value is not a positive whole number of years (a value the
     * rule cannot mean is never rounded into one).
     */
    public static Optional<LocalDate> exMemberEnd(
            LocalDate separation, BigDecimal years, boolean onDeputation) {
        if (onDeputation || separation == null || years == null || years.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal whole = years.stripTrailingZeros();
        if (whole.scale() > 0) {
            return Optional.empty();
        }
        return Optional.of(separation.plusYears(whole.longValueExact()));
    }
}
