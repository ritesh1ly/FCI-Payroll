package in.gov.fci.payroll.configuration.business;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Function;

/**
 * Whether a rule's scope covers who asks (KB RE-10, SRS-19-8: a site-based exception is a
 * site-scoped rule, never hard-coded logic). Target DDL v4.0 {@code rule_definition}: {@code
 * payroll_type_scope} lists the engines a rule applies to (comma-separated; null = every engine)
 * and {@code org_scope_location_id} the office it is limited to, with the offices below it (null =
 * every office). Pure rules.
 */
public final class RuleScope {

    private RuleScope() {}

    /**
     * @param payrollTypes the rule's payroll types, comma-separated; null or blank = every engine
     * @param office the office the rule is limited to; null = every office
     * @param payrollType the engine asking; null = not known, so only a rule for every engine
     *     applies
     * @param location the member's office; null = not known, so only a rule for every office
     *     applies
     * @param subtree the offices under an office, the office itself included
     */
    public static boolean covers(
            String payrollTypes,
            Long office,
            String payrollType,
            Long location,
            Function<Long, Set<Long>> subtree) {
        if (payrollTypes != null && !payrollTypes.isBlank()) {
            boolean listed =
                    payrollType != null
                            && Arrays.stream(payrollTypes.split(","))
                                    .map(String::strip)
                                    .anyMatch(payrollType::equals);
            if (!listed) {
                return false;
            }
        }
        return office == null || (location != null && subtree.apply(office).contains(location));
    }
}
