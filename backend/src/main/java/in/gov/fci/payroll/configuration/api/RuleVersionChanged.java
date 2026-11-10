package in.gov.fci.payroll.configuration.api;

import java.time.LocalDate;
import java.util.Objects;

/**
 * An M-08 rule change took effect: published in the Rule Approver's decision transaction, after the
 * versions are written, so a listener sees the new state and a failing listener rolls the decision
 * back. The extension point for what follows a rule change: the arrear engine raises arrears for a
 * version back-dated before the day it was approved (SRS-19-16, module 20); a rule cache evicts the
 * key (HLD §3.12); event rules fire (SRS-19-16 "DA reaches 50 %", phase 06f).
 *
 * @param kind APPROVED: a new version is in force from {@code effectiveFrom}; ENDED: the version
 *     ends on {@code effectiveTo} with no successor (SRS-19-6, KB RE-4)
 * @param decidedOn the business day of the decision
 */
public record RuleVersionChanged(
        Kind kind,
        String ruleKey,
        String ruleType,
        Long versionId,
        int versionNo,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        LocalDate decidedOn) {

    /** What changed. */
    public enum Kind {
        APPROVED,
        ENDED
    }

    public RuleVersionChanged {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(ruleKey, "ruleKey");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(decidedOn, "decidedOn");
    }

    /**
     * A version approved to start before the day it was approved: periods computed with the earlier
     * version are owed arrears (SRS-19-16), never recomputed silently (SRS-19-13).
     */
    public boolean retrospective() {
        return kind == Kind.APPROVED && effectiveFrom.isBefore(decidedOn);
    }
}
