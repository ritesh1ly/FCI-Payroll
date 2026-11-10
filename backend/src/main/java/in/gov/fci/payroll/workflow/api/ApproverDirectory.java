package in.gov.fci.payroll.workflow.api;

import java.util.List;

/**
 * Whether anyone can decide an approval level (implemented by the iam module, which owns users,
 * groups and their scope). BR-APPR-007: if no user other than the maker holds the level's role with
 * an access group covering the level's office, the item is flagged to the HQ Administrator; it is
 * never approved automatically.
 */
public interface ApproverDirectory {

    /**
     * True if an active user other than {@code makerLogin} holds {@code roleCode} at {@code
     * locationId} today.
     */
    boolean hasApprover(String roleCode, Long locationId, String makerLogin);

    /**
     * The active users who hold {@code roleCode} at {@code locationId} today (the recipients of a
     * staff notice: SRS-15-30, SRS-1-68).
     */
    default List<Long> holders(String roleCode, Long locationId) {
        return List.of();
    }

    /**
     * Today's approvers, read once to answer many levels in memory (the A-08 unstaffed list: no
     * read of every user per item). Answers as {@link #hasApprover} would at the time it was read.
     * By default it asks {@link #hasApprover} for each level.
     */
    default Staffing staffing() {
        return this::hasApprover;
    }

    /** A snapshot of who holds which role at which office today. */
    @FunctionalInterface
    interface Staffing {

        /** As {@link ApproverDirectory#hasApprover}, from the snapshot. */
        boolean hasApprover(String roleCode, Long locationId, String makerLogin);
    }
}
