package in.gov.fci.payroll.iam.business;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which acting roles need the user's OTP before the session may work (SRS-G-48, SRS-G-21; Module 02
 * decision D-2, 2026-10-21). Derived from configuration, never from a list of role codes: a role is
 * sensitive when its permissions ({@code app_role_permission}) let it decide other users' changes
 * (APPROVE on any screen) or change access and settings (any action other than VIEW / EXPORT on an
 * administration screen A-nn). Granting or withdrawing such a permission on A-02 changes the
 * answer; nothing is hard-coded per role.
 */
public final class SensitiveRoles {

    private static final Set<String> READ_ACTIONS = Set.of("VIEW", "EXPORT");

    private SensitiveRoles() {}

    /** {@code permissionKeys}: {@code SCREEN:ACTION} keys of the acting role. */
    public static boolean isSensitive(Collection<String> permissionKeys) {
        return permissionKeys.stream().anyMatch(SensitiveRoles::isSensitiveKey);
    }

    /**
     * The screens on which the keys hold a sensitive action: opening or calling them needs a recent
     * OTP step-up.
     */
    public static Set<String> sensitiveScreens(Collection<String> permissionKeys) {
        Set<String> screens = new TreeSet<>();
        for (String key : permissionKeys) {
            if (isSensitiveKey(key)) {
                screens.add(key.substring(0, key.indexOf(':')));
            }
        }
        return Set.copyOf(screens);
    }

    static boolean isSensitiveKey(String key) {
        int colon = key == null ? -1 : key.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String screen = key.substring(0, colon);
        String action = key.substring(colon + 1);
        return "APPROVE".equals(action)
                || (screen.startsWith("A-") && !READ_ACTIONS.contains(action));
    }
}
