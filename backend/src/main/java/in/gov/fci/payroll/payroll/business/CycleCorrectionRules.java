package in.gov.fci.payroll.payroll.business;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * The rules of the two pre-lock corrections of a payroll cycle (plan 05 phase 05d), as pure
 * functions: each answers what stops the step, or empty when it may go on.
 *
 * <ul>
 *   <li>SRS-15-30 / BR-CYC-016: the Checker returns a computed or verified cycle with a reason and
 *       a scope (the whole instance, an office beneath it, or named members); a return raised in
 *       error is withdrawn explicitly while nothing was done on it.
 *   <li>SRS-15-31 / BR-CYC-017, SRS-15-10: an un-freeze only before month-end lock, from a frozen,
 *       computed or verified cycle (DDL {@code cycle_unfreeze.from_status} excludes LOCKED).
 * </ul>
 */
public final class CycleCorrectionRules {

    /** DDL {@code cycle_return.return_scope}. */
    public enum ReturnScope {
        INSTANCE,
        ORG_UNIT,
        MEMBERS
    }

    private CycleCorrectionRules() {}

    /** SRS-15-30: only a computed or verified cycle is returned (DDL {@code from_status}). */
    public static boolean returnable(String status) {
        return "COMPUTED".equals(status) || "VERIFIED".equals(status);
    }

    /**
     * SRS-15-31, SRS-15-10: an un-freeze before lock, of a cycle whose inputs were frozen; never of
     * an initiated (nothing frozen) or a locked cycle.
     */
    public static boolean unfreezable(String status) {
        return "FROZEN".equals(status) || "COMPUTED".equals(status) || "VERIFIED".equals(status);
    }

    /**
     * SRS-15-30: the detail the scope needs, and nothing it does not: an office for ORG_UNIT, at
     * least one member (each once) for MEMBERS, neither for INSTANCE (DDL {@code
     * chk_return_scope}).
     *
     * @return the defect (ORG_UNIT_REQUIRED, MEMBERS_REQUIRED, MEMBER_TWICE, DETAIL_NOT_ALLOWED)
     */
    public static Optional<String> scopeDefect(
            ReturnScope scope, String orgUnitCode, List<String> members) {
        boolean office = orgUnitCode != null && !orgUnitCode.isBlank();
        boolean named = members != null && !members.isEmpty();
        return switch (scope) {
            case INSTANCE -> office || named ? Optional.of("DETAIL_NOT_ALLOWED") : Optional.empty();
            case ORG_UNIT -> {
                if (!office) {
                    yield Optional.of("ORG_UNIT_REQUIRED");
                }
                yield named ? Optional.of("DETAIL_NOT_ALLOWED") : Optional.empty();
            }
            case MEMBERS -> {
                if (!named) {
                    yield Optional.of("MEMBERS_REQUIRED");
                }
                if (office) {
                    yield Optional.of("DETAIL_NOT_ALLOWED");
                }
                yield new HashSet<>(members).size() == members.size()
                        ? Optional.empty()
                        : Optional.of("MEMBER_TWICE");
            }
        };
    }

    /**
     * SRS-15-30 "withdraw a return raised in error explicitly": only while the return is OPEN and
     * the cycle is still as the return left it (FROZEN, no computation started and no un-freeze
     * since), so the withdrawal can restore exactly what the return reversed.
     *
     * @return why it can no longer be withdrawn (its status, COMPUTATION_STARTED, UNFROZEN, or the
     *     cycle's status)
     */
    public static Optional<String> withdrawalDefect(
            String returnStatus, String cycleStatus, boolean runSince, boolean unfrozenSince) {
        if (!"OPEN".equals(returnStatus)) {
            return Optional.of(returnStatus);
        }
        if (unfrozenSince) {
            return Optional.of("UNFROZEN");
        }
        if (runSince) {
            return Optional.of("COMPUTATION_STARTED");
        }
        return "FROZEN".equals(cycleStatus) ? Optional.empty() : Optional.of(cycleStatus);
    }
}
