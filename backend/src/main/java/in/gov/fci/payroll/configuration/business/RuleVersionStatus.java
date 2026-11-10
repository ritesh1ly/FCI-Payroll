package in.gov.fci.payroll.configuration.business;

import java.time.LocalDate;

/**
 * The status of a rule or parameter version, derived and never stored (SRS-19-11, KB RE-9; DDL view
 * {@code v_rule_version_status}): from its approval state and its effective dates on a given day.
 * The DDL's vocabulary is kept (CF-28, plan 06 D-5); the screens label it Draft / Pending Approval
 * / Active / Superseded.
 *
 * <p>OPEN-QUESTION CLR-127 (BMAD TR-013, KB CF-28): SRS-19-11 allows four values (Draft, Pending
 * Approval, Active, Superseded); how an approved future-dated version (APPROVED_FUTURE) and a
 * rejected one (REJECTED) are shown within them is not stated - kept as built until FCI answers.
 */
public enum RuleVersionStatus {
    DRAFT,
    PENDING_APPROVAL,
    REJECTED,
    APPROVED_FUTURE,
    ACTIVE,
    SUPERSEDED;

    /**
     * As the view: an unapproved state is the status; an approved version is SUPERSEDED once its
     * end date has passed, APPROVED_FUTURE before its start, otherwise ACTIVE.
     *
     * @param approvalState {@code rule_version.approval_state} (DRAFT, PENDING_APPROVAL, REJECTED,
     *     APPROVED)
     */
    public static RuleVersionStatus of(
            String approvalState, LocalDate from, LocalDate to, LocalDate day) {
        switch (approvalState) {
            case "DRAFT" -> {
                return DRAFT;
            }
            case "PENDING_APPROVAL" -> {
                return PENDING_APPROVAL;
            }
            case "REJECTED" -> {
                return REJECTED;
            }
            case "APPROVED" -> {
                if (to != null && to.isBefore(day)) {
                    return SUPERSEDED;
                }
                return from.isAfter(day) ? APPROVED_FUTURE : ACTIVE;
            }
            default -> throw new IllegalArgumentException("approval_state " + approvalState);
        }
    }

    /** In force on {@code day}: approved, started, not ended (what the engine resolves). */
    public static boolean inForce(
            String approvalState, LocalDate from, LocalDate to, LocalDate day) {
        return "APPROVED".equals(approvalState) && of(approvalState, from, to, day) == ACTIVE;
    }
}
