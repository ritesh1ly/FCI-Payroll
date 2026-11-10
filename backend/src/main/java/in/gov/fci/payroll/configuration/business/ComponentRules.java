package in.gov.fci.payroll.configuration.business;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * M-07 Component Catalogue rules (plan 06 phase 06d; SRS M-07 use case and field tables, SRS-G-42,
 * FAP-14): a pay code of up to three digits, a plain-language name, earning or deduction, at least
 * one payroll type, a field source of the seven, Y / N / PARTLY taxable, a natural account, a basis
 * rule key well formed. Uniqueness, the basis rule's existence and the open proposal are checked by
 * the service against the catalogue.
 */
public final class ComponentRules {

    /**
     * What the Rule Administrator defines or revises.
     *
     * @param subjectToLwp SRS-LV-15 / BR-LEAVE-007 (US-GAP-298): the M-07 "Subject to LWP"
     *     attribute; null = not given (a definition takes N, a revision keeps the current value)
     */
    public record Definition(
            int payCode,
            String name,
            String type,
            String payslipGroup,
            String basisRuleKey,
            String source,
            String naturalAccount,
            String taxable,
            boolean inCpfBase,
            Set<String> payrollTypes,
            LocalDate effectiveFrom,
            Boolean subjectToLwp,
            String eligibilityRuleKey) {

        /** A definition that names no eligibility flag (SRS l.1909: not mandatory). */
        public Definition(
                int payCode,
                String name,
                String type,
                String payslipGroup,
                String basisRuleKey,
                String source,
                String naturalAccount,
                String taxable,
                boolean inCpfBase,
                Set<String> payrollTypes,
                LocalDate effectiveFrom,
                Boolean subjectToLwp) {
            this(
                    payCode,
                    name,
                    type,
                    payslipGroup,
                    basisRuleKey,
                    source,
                    naturalAccount,
                    taxable,
                    inCpfBase,
                    payrollTypes,
                    effectiveFrom,
                    subjectToLwp,
                    null);
        }

        /** A definition that does not name the LWP attribute. */
        public Definition(
                int payCode,
                String name,
                String type,
                String payslipGroup,
                String basisRuleKey,
                String source,
                String naturalAccount,
                String taxable,
                boolean inCpfBase,
                Set<String> payrollTypes,
                LocalDate effectiveFrom) {
            this(
                    payCode,
                    name,
                    type,
                    payslipGroup,
                    basisRuleKey,
                    source,
                    naturalAccount,
                    taxable,
                    inCpfBase,
                    payrollTypes,
                    effectiveFrom,
                    null);
        }

        /** The definition with the LWP attribute given. */
        public Definition withSubjectToLwp(boolean value) {
            return new Definition(
                    payCode,
                    name,
                    type,
                    payslipGroup,
                    basisRuleKey,
                    source,
                    naturalAccount,
                    taxable,
                    inCpfBase,
                    payrollTypes,
                    effectiveFrom,
                    value,
                    eligibilityRuleKey);
        }
    }

    private static final Set<String> TYPES = Set.of("EARNING", "DEDUCTION");
    private static final Set<String> SOURCES =
            Set.of("ENT", "CALC", "HRMS", "RULE", "SYS", "ESS", "INTF");
    private static final Set<String> TAXABLE = Set.of("Y", "N", "PARTLY");
    private static final Pattern NAME = Pattern.compile("^[\\x20-\\x7E]{1,100}$");
    private static final Pattern GROUP = Pattern.compile("^[A-Z_]{1,40}$");
    private static final Pattern RULE_KEY = Pattern.compile("^[A-Z0-9_.]{1,100}$");
    private static final Pattern ACCOUNT = Pattern.compile("^[A-Z0-9][A-Z0-9-]{1,19}$");

    /** An eligibility flag code as the catalogue draws it (HILL_ALLOW_FLAG, SPL_PAY_*). */
    private static final Pattern ELIGIBILITY = Pattern.compile("^[A-Z0-9_.*]{1,100}$");

    private ComponentRules() {}

    /**
     * What is wrong with the definition, as the message argument, or empty.
     *
     * <p>FAP-14: at least one payroll type; a labour-only code stays off the staff engine because
     * its engines are exactly the types selected.
     */
    public static Optional<String> defect(Definition d) {
        if (d.payCode() < 1 || d.payCode() > 999) {
            return Optional.of("the pay code is a number of up to three digits");
        }
        if (d.name() == null || d.name().isBlank() || !NAME.matcher(d.name().strip()).matches()) {
            return Optional.of("the component name is required, printable, at most 100 characters");
        }
        if (!TYPES.contains(d.type())) {
            return Optional.of("the component type is an earning or a deduction");
        }
        if (d.payslipGroup() != null && !GROUP.matcher(d.payslipGroup()).matches()) {
            return Optional.of("the payslip group is a code of capital letters");
        }
        // SRS l.1907, l.1925: Driver / Basis is mandatory and resolves from the rule engine.
        if (d.basisRuleKey() == null) {
            return Optional.of("the driver / basis is required");
        }
        if (!RULE_KEY.matcher(d.basisRuleKey()).matches()) {
            return Optional.of("the basis is a rule key of the catalogue (M-08)");
        }
        // SRS l.1909, SC-06-0028: optional; DDL pay_component.eligibility_rule_key.
        if (d.eligibilityRuleKey() != null
                && !ELIGIBILITY.matcher(d.eligibilityRuleKey()).matches()) {
            return Optional.of("the eligibility flag is a code of capital letters and digits");
        }
        if (!SOURCES.contains(d.source())) {
            return Optional.of(
                    "the source of value is one of ENT, CALC, HRMS, RULE, SYS, ESS, INTF");
        }
        if (d.naturalAccount() == null || !ACCOUNT.matcher(d.naturalAccount()).matches()) {
            return Optional.of("the natural account is required, capital letters, digits or '-'");
        }
        if (!TAXABLE.contains(d.taxable())) {
            return Optional.of("taxable is Y, N or PARTLY");
        }
        if (d.payrollTypes() == null
                || d.payrollTypes().isEmpty()
                || d.payrollTypes().stream().anyMatch(t -> PayrollType.of(t) == null)) {
            return Optional.of(
                    "choose at least one payroll type of Employee, Departmental, DPS, NWNP");
        }
        if (d.effectiveFrom() == null) {
            return Optional.of("the effective-from date is required");
        }
        return Optional.empty();
    }

    /** A code used only by the labour engines (no Employee / Staff payroll type). */
    public static boolean labourOnly(Set<String> payrollTypes) {
        return !payrollTypes.contains(PayrollType.STAFF.name());
    }
}
