package in.gov.fci.payroll.workflow.business;

import in.gov.fci.payroll.platform.error.Violation;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction.OfficeLevel;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The rules of an approval route proposed on A-08 (Module 02 F-1). Pure: no I/O; the role catalogue
 * is passed in as a predicate. Every broken rule is reported (one {@link Violation} each, field and
 * code), not only the first, so the screen can mark them all at once.
 */
public final class WorkflowRules {

    /** One level of a proposed route: 1 MAKER, 2 CHECKER, 3 APPROVER. */
    public record ProposedLevel(
            int levelNo, String roleCode, OfficeLevel officeLevel, boolean otpAtLevel) {}

    /** A proposed route (a new approval action or a new version of one). */
    public record ProposedRoute(
            String actionCode,
            String actionName,
            String moduleCode,
            String screenCode,
            OfficeLevel officeLevel,
            int levelCount,
            OtpGatedAction otpAction,
            boolean attachmentRequired,
            Integer targetTatHours,
            LocalDate effectiveFrom,
            List<ProposedLevel> levels,
            boolean bulkApprovalAllowed) {

        /** A route without bulk approval (the default of an action, DDL). */
        public ProposedRoute(
                String actionCode,
                String actionName,
                String moduleCode,
                String screenCode,
                OfficeLevel officeLevel,
                int levelCount,
                OtpGatedAction otpAction,
                boolean attachmentRequired,
                Integer targetTatHours,
                LocalDate effectiveFrom,
                List<ProposedLevel> levels) {
            this(
                    actionCode,
                    actionName,
                    moduleCode,
                    screenCode,
                    officeLevel,
                    levelCount,
                    otpAction,
                    attachmentRequired,
                    targetTatHours,
                    effectiveFrom,
                    levels,
                    false);
        }

        /** SRS-G-13 (US-GAP-21): the same route with bulk approval allowed or not. */
        public ProposedRoute bulk(boolean allowed) {
            return new ProposedRoute(
                    actionCode,
                    actionName,
                    moduleCode,
                    screenCode,
                    officeLevel,
                    levelCount,
                    otpAction,
                    attachmentRequired,
                    targetTatHours,
                    effectiveFrom,
                    levels,
                    allowed);
        }
    }

    static final Pattern ACTION_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,39}$");
    static final Pattern MODULE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    static final Pattern SCREEN_CODE = Pattern.compile("^[A-Z]-\\d{2}$");

    /** target_tat_hours is NUMBER(5): whole hours, 1 to 99,999. */
    static final int MAX_TARGET_HOURS = 99_999;

    private WorkflowRules() {}

    /**
     * Every rule a proposed route breaks; empty if it may be submitted.
     *
     * @param activeRole the role catalogue: true for a role in force today
     * @param thirdLevelNamed SRS-22-12: true for an action the matrix names a third level for (the
     *     M-16 list THIRD_LEVEL_ACTION on the route's effective date)
     * @param takenDates effective-from dates already used by versions of the same action and office
     *     level (DDL uk_appr_wf_action_eff)
     */
    public static List<Violation> violations(
            ProposedRoute route,
            LocalDate today,
            Predicate<String> activeRole,
            Predicate<String> thirdLevelNamed,
            Collection<LocalDate> takenDates) {
        List<Violation> out = new ArrayList<>();
        if (route.actionCode() == null || !ACTION_CODE.matcher(route.actionCode()).matches()) {
            out.add(v("actionCode", "ACTION_CODE"));
        }
        if (route.actionName() == null
                || route.actionName().isBlank()
                || route.actionName().length() > 200) {
            out.add(v("actionName", "ACTION_NAME"));
        }
        if (route.moduleCode() == null || !MODULE_CODE.matcher(route.moduleCode()).matches()) {
            out.add(v("moduleCode", "MODULE_CODE"));
        }
        if (route.screenCode() != null && !SCREEN_CODE.matcher(route.screenCode()).matches()) {
            out.add(v("screenCode", "SCREEN_CODE"));
        }
        if (route.officeLevel() == null) {
            out.add(v("officeLevel", "OFFICE_LEVEL"));
        }
        levels(route, activeRole, out);
        // SRS-22-12: "Apply a third level only where the matrix names one" (NEW-G1-09).
        if (route.levelCount() == 3
                && route.actionCode() != null
                && !thirdLevelNamed.test(route.actionCode())) {
            out.add(v("levelCount", "THIRD_LEVEL_NOT_NAMED"));
        }
        // SRS-G-21: an OTP only with one of the 18 gated actions (DDL chk_appr_wf_otp_code); a
        // level
        // asks for it only when the action names one.
        if (route.otpAction() == null
                && route.levels() != null
                && route.levels().stream().anyMatch(ProposedLevel::otpAtLevel)) {
            out.add(v("levels", "OTP_WITHOUT_ACTION"));
        }
        if (route.targetTatHours() != null
                && (route.targetTatHours() < 1 || route.targetTatHours() > MAX_TARGET_HOURS)) {
            out.add(v("targetTatHours", "TARGET_HOURS"));
        }
        if (route.effectiveFrom() == null || route.effectiveFrom().isBefore(today)) {
            out.add(v("effectiveFrom", "EFFECTIVE_FROM_PAST"));
        } else if (takenDates.contains(route.effectiveFrom())) {
            out.add(v("effectiveFrom", "EFFECTIVE_FROM_TAKEN"));
        }
        return out;
    }

    /** SRS-22-17 / BR-APPR-003 and BR-APPR-002 / SRS-22-16: the levels of the route. */
    private static void levels(
            ProposedRoute route, Predicate<String> activeRole, List<Violation> out) {
        if (route.levelCount() < 2 || route.levelCount() > 3) {
            out.add(v("levelCount", "LEVEL_COUNT"));
            return;
        }
        List<ProposedLevel> levels = route.levels() == null ? List.of() : route.levels();
        if (levels.size() != route.levelCount()) {
            out.add(v("levels", "LEVELS_MISMATCH"));
            return;
        }
        for (int i = 0; i < levels.size(); i++) {
            ProposedLevel level = levels.get(i);
            String field = "levels[" + i + "]";
            if (level.levelNo() != i + 1) {
                out.add(v(field, "LEVEL_ORDER"));
            }
            if (level.roleCode() == null || !activeRole.test(level.roleCode())) {
                out.add(v(field, "LEVEL_ROLE"));
            }
            if (level.officeLevel() == null) {
                out.add(v(field, "LEVEL_OFFICE"));
            }
            // The maker's OTP is the action's own (otp_required); a level's OTP is a checker's.
            if (level.levelNo() == 1 && level.otpAtLevel()) {
                out.add(v(field, "OTP_AT_MAKER"));
            }
        }
    }

    /** The next version number after {@code existing} (1 for a new action). */
    public static int nextVersion(Collection<Integer> existing) {
        return existing.stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
    }

    private static Violation v(String field, String code) {
        return new Violation(field, code, "a08.rule." + code);
    }
}
