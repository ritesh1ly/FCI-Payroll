package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.platform.security.AccessContext;
import java.util.Set;

/** The offices a session's worklists cover. */
final class Scopes {

    /** No location id is negative: an empty data scope then matches no row (never "all rows"). */
    private static final Set<Long> NOTHING = Set.of(-1L);

    private Scopes() {}

    /** A scope matching no row. */
    static Set<Long> nothing() {
        return NOTHING;
    }

    /** The session's data scope, or a scope matching nothing when it is empty. */
    static Set<Long> of(AccessContext context) {
        Set<Long> scope = context.dataScope();
        return scope == null || scope.isEmpty() ? NOTHING : scope;
    }
}
