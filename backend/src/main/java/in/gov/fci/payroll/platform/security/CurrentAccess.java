package in.gov.fci.payroll.platform.security;

import java.time.LocalDate;

/**
 * The access context of the current request's session (implemented by the iam module, which
 * rebuilds it from the session and the user's current assignments).
 */
public interface CurrentAccess {

    /** The caller's context; 401 if the request has no usable session. */
    AccessContext current();

    /** Today in the organisation's time zone (effective dates of assignments and overrides). */
    LocalDate today();
}
