package in.gov.fci.payroll.platform.security;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;

/**
 * The authorisation policy every module applies: {@code can(caller, screen, action, target)}.
 * Implemented by the iam module (bean {@code authz}, also the {@code @PreAuthorize} gate); modules
 * depend on this contract only.
 */
public interface Authorization {

    /** Screen-level check: some active group (or the eSeva role) grants the permission. */
    boolean can(AccessContext context, String screen, String action);

    /** Permission at a location: role grant, data scope, consolidated read-only, overrides. */
    boolean canAt(AccessContext context, String screen, String action, Long locationId);

    /** The precise denial of {@code action} on {@code screen} at {@code locationId}, or null. */
    ApiErrorCode denialAt(AccessContext context, String screen, String action, Long locationId);

    /**
     * Throws the precise 403 if {@code action} on {@code screen} at {@code locationId} is denied.
     */
    void requireAt(AccessContext context, String screen, String action, Long locationId);

    /** Screen-level requirement without a location (HQ functions, admin, own record). */
    void require(AccessContext context, String screen, String action);

    /**
     * Record-level check on a record looked up by its key, at the record's own location: as {@link
     * #requireAt}, except that a record outside the data scope is 404 (never 403), so it reads
     * exactly as a record that does not exist (OWASP A01; project owner, 2026-10-24). The attempt
     * is still a security event. {@link #requireAt} stays the check of an office the user names (a
     * job for an office, the session's cycle): there is nothing to disclose.
     */
    default void requireRecordAt(
            AccessContext context, String screen, String action, Long locationId) {
        requireRecordAt(context, screen, action, locationId, CommonErrorCode.NOT_FOUND);
    }

    /**
     * As {@link #requireRecordAt(AccessContext, String, String, Long)}, answering {@code unknown} -
     * the screen's own "no such record" code - so the two answers are byte-for-byte the same.
     */
    void requireRecordAt(
            AccessContext context,
            String screen,
            String action,
            Long locationId,
            ErrorCode unknown);

    /**
     * Record-level check on an employee's record: own record for eSeva, data scope (the record's
     * location) otherwise; outside it, 404 as {@link #requireRecordAt}.
     */
    void requireEmployeeRecord(
            AccessContext context, String screen, String action, Long employeeId, Long locationId);

    /**
     * SRS-17-9: a member asked for a record that exists and is another member's. The answer stays
     * the caller's plain "not found" (OD-102, RBAC-SEC-026: it must read as a record that does not
     * exist), so this is no access offence, which would end the session and tell the two apart:
     * only the security event is written, in a transaction of its own that survives the refusal.
     */
    void recordForeignAttempt(
            AccessContext context, String screen, String action, String entity, Object key);

    /** The session's login location is the Headquarters office. */
    boolean isAtHeadquarters(AccessContext context);
}
