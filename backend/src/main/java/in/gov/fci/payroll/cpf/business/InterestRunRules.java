package in.gov.fci.payroll.cpf.business;

import in.gov.fci.payroll.cpf.business.InterestRateRules.FinYear;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader.MemberStatus;
import in.gov.fci.payroll.cpf.business.model.PfInterestRate;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The rules of a C-12 CPF interest run (SRS-6-45, SRS-6-46, SRS-6-11, SRS-6-9; FRS V0.5 §13.3; DDL
 * {@code cpf_interest_run}): which members a run covers, the rate each needs, the refusal of an
 * undeclared or missing rate, the interest stop, and why a member is not yet calculated.
 *
 * <p>How interest is computed from these inputs (monthly running balance or product, rounding,
 * credit timing) is in no source: Q-072, decision D-4. Every member a run covers is therefore
 * recorded as not calculated with {@link #METHOD_NOT_CONFIGURED}; nothing is credited.
 */
public final class InterestRunRules {

    /** {@code cpf_interest_run.run_scope} (DDL CHECK). */
    public enum Scope {
        ALL_MEMBERS,
        INDIVIDUAL
    }

    /** Why a member is not calculated (shown on the "not yet calculated" report). */
    public static final String METHOD_NOT_CONFIGURED = "INTEREST_METHOD_NOT_CONFIGURED";

    /** SRS-6-59: no CPF code from HRMS, so nothing is posted for the member. */
    public static final String CPF_CODE_MISSING = "CPF_CODE_MISSING";

    private InterestRunRules() {}

    /**
     * SRS-6-11 / BR-CPF-033: the FCI-declared rate while the member is active, including on
     * deputation; the EPFO-declared rate while inactive. A finally settled ledger, or one closed by
     * a parent-zone change, takes no year-end run: its interest runs to the sanction date
     * (BR-CPF-014 / 015) or to the change's effective date (SRS-6-52) on those paths.
     */
    public static Optional<PfInterestRate.Source> sourceOf(MemberStatus status) {
        return switch (status) {
            case ACTIVE, DEPUTATION -> Optional.of(PfInterestRate.Source.FCI_TRUST);
            case EX_MEMBER -> Optional.of(PfInterestRate.Source.RPFC);
            case FINALLY_SETTLED, CLOSED_ZONE_CHANGE -> Optional.empty();
        };
    }

    /**
     * DDL {@code pf_interest_rate}: "an interest run must refuse to execute against an undeclared
     * rate rather than fall back to last year's". The run needs, for each source its members need,
     * the approved rates of the year, every one declared.
     *
     * @param ofYear the approved rates of the year and fund by source
     */
    public static void requireRates(
            FinYear year,
            Iterable<PfInterestRate.Source> needed,
            Map<PfInterestRate.Source, List<PfInterestRate>> ofYear) {
        for (PfInterestRate.Source source : needed) {
            List<PfInterestRate> rates = ofYear.getOrDefault(source, List.of());
            if (rates.isEmpty()) {
                throw new FciException(
                        CpfErrorCode.RATE_MISSING,
                        new Object[] {year.code(), source.name()},
                        List.of(),
                        null);
            }
            if (rates.stream().anyMatch(r -> !r.isIsDeclared())) {
                throw new FciException(
                        CpfErrorCode.RATE_UNDECLARED,
                        new Object[] {year.code(), source.name()},
                        List.of(),
                        null);
            }
        }
    }

    /**
     * SRS-6-9 / DDL {@code interest_end_date}: no interest beyond the end date, so a member whose
     * interest ends before the year does is stopped inside (or before) it.
     */
    public static boolean stoppedIn(FinYear year, LocalDate interestEnd) {
        return interestEnd != null && interestEnd.isBefore(year.end());
    }

    /** Why a covered member is not calculated: the CPF code first, then the open method. */
    public static String notCalculatedReason(boolean hasCpfCode) {
        return hasCpfCode ? METHOD_NOT_CONFIGURED : CPF_CODE_MISSING;
    }

    /**
     * SRS-6-45: "all members / Individual (EIS number)" - an individual run names its member, an
     * all-members run names none.
     */
    public static void requireScope(Scope scope, String eis) {
        boolean named = eis != null && !eis.isBlank();
        if (scope == null || (scope == Scope.INDIVIDUAL) != named) {
            throw new FciException(CpfErrorCode.RUN_SCOPE);
        }
    }

    /** The key of a zone's year in the audit trail and the approval queue: "2026-27|NA11". */
    public static String yearKey(String finYear, String zoneCode) {
        return finYear + "|" + zoneCode;
    }
}
