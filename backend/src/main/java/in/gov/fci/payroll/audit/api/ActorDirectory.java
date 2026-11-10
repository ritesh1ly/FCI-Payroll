package in.gov.fci.payroll.audit.api;

import java.util.Collection;
import java.util.Map;

/**
 * The name and Employee No. of the users behind audit rows (X-04 Maker and Checker: "taken from the
 * signed-in users"). Implemented by the identity module.
 */
public interface ActorDirectory {

    /** The users with these login ids, by login id; unknown ids (system, batch) are left out. */
    Map<String, Actor> actors(Collection<String> loginIds);

    /**
     * X-04 "All users" filter: the login ids of a user group of the M-16 list AUDIT_USER_GROUP
     * (MAKER, CHECKER, ADMINISTRATOR). {@code audit_event} keeps no role, so this is who holds such
     * a role today, not who held it when the row was written (PENDING X04-ROLE).
     *
     * @return the logins, empty for a group nobody holds
     */
    default java.util.Set<String> loginsOf(String userGroup) {
        return java.util.Set.of();
    }

    /**
     * @param employeeNumber the Employee No., or null for a user without an employee record
     */
    record Actor(String loginId, String name, String employeeNumber) {}
}
