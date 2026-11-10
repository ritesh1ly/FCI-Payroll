package in.gov.fci.payroll.iam.business;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Pure rules of the user-governance reports (Module 02 F-3 and F-7). No I/O; the settings they need
 * are passed in, and an unconfigured setting gives "unknown" (empty), never a guessed default.
 */
public final class UserGovernance {

    /** SRS-G-50 / SRS-22-11: whether a payroll unit can run on its own. */
    public enum Readiness {
        /** The unit's own access groups hold a Maker and a separate Checker. */
        READY,
        /** Only together with groups that reach it from above (zone, national, parent office). */
        COVERED_FROM_ABOVE,
        /** No Maker and separate Checker reach the unit at all. */
        NOT_READY
    }

    private UserGovernance() {}

    /**
     * SRS-G-50: a user is inactive when the last sign-in (or, never signed in, the account's
     * creation) is more than the configured number of days ago. Empty while the threshold is not
     * configured on A-07 (INACTIVE_USER_DAYS): the report says so instead of guessing.
     */
    public static Optional<Boolean> inactive(
            Instant lastLoginAt, Instant createdAt, OptionalLong thresholdDays, Instant now) {
        if (thresholdDays.isEmpty()) {
            return Optional.empty();
        }
        Instant since = lastLoginAt != null ? lastLoginAt : createdAt;
        if (since == null) {
            return Optional.of(true);
        }
        return Optional.of(
                Duration.between(since, now).compareTo(Duration.ofDays(thresholdDays.getAsLong()))
                        > 0);
    }

    /** Whole days since the last sign-in, or null if the user never signed in. */
    public static Long daysSince(Instant lastLoginAt, Instant now) {
        return lastLoginAt == null ? null : Duration.between(lastLoginAt, now).toDays();
    }

    /**
     * SRS-G-50 "a Maker and a separate Checker": true if some Maker and some Checker are different
     * people (one person holding both roles is not enough).
     */
    public static boolean separatePair(Collection<Long> makers, Collection<Long> checkers) {
        for (Long maker : makers) {
            for (Long checker : checkers) {
                if (!maker.equals(checker)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * SRS-G-50 / SRS-22-11: READY with the unit's own separate pair; COVERED_FROM_ABOVE when the
     * pair exists only counting the groups that reach the unit from above; NOT_READY otherwise.
     */
    public static Readiness readiness(
            Collection<Long> ownMakers,
            Collection<Long> ownCheckers,
            Collection<Long> allMakers,
            Collection<Long> allCheckers) {
        if (separatePair(ownMakers, ownCheckers)) {
            return Readiness.READY;
        }
        return separatePair(allMakers, allCheckers)
                ? Readiness.COVERED_FROM_ABOVE
                : Readiness.NOT_READY;
    }

    /**
     * SRS-22-11: whether a Divisional Office has the minimum own staff to run on its own; empty
     * while the minimum (DO_MINIMUM_STAFF) is not configured on A-07.
     */
    public static Optional<Boolean> meetsMinimum(int ownStaff, OptionalLong minimum) {
        return minimum.isEmpty() ? Optional.empty() : Optional.of(ownStaff >= minimum.getAsLong());
    }
}
