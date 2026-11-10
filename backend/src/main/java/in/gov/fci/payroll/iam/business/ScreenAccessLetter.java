package in.gov.fci.payroll.iam.business;

import java.util.Optional;
import java.util.Set;

/**
 * The letter of a role on a screen in the X-01 Screen-Level Access Matrix (SRS-22-8, the X-01
 * screen design, Wireframes v6.1): A approves, E edits (any action beyond viewing and exporting), V
 * views. A role without VIEW has no letter: the screen is not in its menu, whatever else it holds.
 */
public final class ScreenAccessLetter {

    private static final String VIEW = "VIEW";
    private static final String EXPORT = "EXPORT";
    private static final String APPROVE = "APPROVE";

    private ScreenAccessLetter() {}

    /** The letter for the actions a role holds on one screen, or empty for no access. */
    public static Optional<String> of(Set<String> actions) {
        if (!actions.contains(VIEW)) {
            return Optional.empty();
        }
        if (actions.contains(APPROVE)) {
            return Optional.of("A");
        }
        boolean edits = actions.stream().anyMatch(a -> !VIEW.equals(a) && !EXPORT.equals(a));
        return Optional.of(edits ? "E" : "V");
    }
}
