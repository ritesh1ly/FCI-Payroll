package in.gov.fci.payroll.platform.security;

/**
 * {@code access_group.access_mode} (target DDL v4.0 S27). FRS V0.5 §6.10.6: read-only report access
 * groups for Divisional Offices and the IR-L Section, limited to their own office hierarchy.
 */
public enum AccessMode {
    /** Every permission of the group's role. */
    FULL,
    /** Only the role's VIEW and EXPORT permissions. */
    READ_ONLY,
    /** Only the role's VIEW and EXPORT permissions on report screens (R-*). */
    REPORTS_ONLY;

    /** Whether a role permission ({@code SCREEN:ACTION}) is conferred under this mode. */
    public boolean admits(String permission) {
        if (this == FULL) {
            return true;
        }
        int colon = permission.indexOf(':');
        String screen = colon < 0 ? permission : permission.substring(0, colon);
        String action = colon < 0 ? "" : permission.substring(colon + 1);
        boolean readOnly = "VIEW".equals(action) || "EXPORT".equals(action);
        return readOnly && (this == READ_ONLY || screen.startsWith("R-"));
    }
}
