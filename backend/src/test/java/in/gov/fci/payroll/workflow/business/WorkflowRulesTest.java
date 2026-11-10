package in.gov.fci.payroll.workflow.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.error.Violation;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.workflow.business.WorkflowRules.ProposedLevel;
import in.gov.fci.payroll.workflow.business.WorkflowRules.ProposedRoute;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction.OfficeLevel;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Module 02 F-1: the rules of a route proposed on the Approval Workflow Master (A-08). */
class WorkflowRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    private static final Predicate<String> ROLES =
            Set.of("PAYROLL_MAKER", "PAYROLL_CHECKER", "AGM_CPF")::contains;

    /** SRS-22-12: in these tests the matrix names a third level for every action. */
    private static final Predicate<String> THIRD = code -> true;

    private static ProposedLevel level(int no, String role) {
        return new ProposedLevel(no, role, OfficeLevel.PAYING_UNIT, false);
    }

    private static ProposedRoute route(
            int levelCount, List<ProposedLevel> levels, OtpGatedAction otp, Integer hours) {
        return new ProposedRoute(
                "I20_SUBMIT",
                "Off-cycle payment",
                "GENERAL",
                "I-20",
                OfficeLevel.PAYING_UNIT,
                levelCount,
                otp,
                false,
                hours,
                TODAY,
                levels);
    }

    private static final List<ProposedLevel> TWO =
            List.of(level(1, "PAYROLL_MAKER"), level(2, "PAYROLL_CHECKER"));

    private static List<String> codes(ProposedRoute r) {
        return codes(r, Set.of());
    }

    private static List<String> codes(ProposedRoute r, Set<LocalDate> taken) {
        return WorkflowRules.violations(r, TODAY, ROLES, THIRD, taken).stream()
                .map(Violation::code)
                .toList();
    }

    @Test
    void SRS_G_13_aRouteWithBulkApprovalChangesNothingElse() {
        ProposedRoute single = route(2, TWO, null, 72);
        assertThat(single.bulkApprovalAllowed()).as("off unless set").isFalse();
        ProposedRoute bulk = single.bulk(true);
        assertThat(bulk.bulkApprovalAllowed()).isTrue();
        assertThat(bulk.bulk(false)).isEqualTo(single);
        assertThat(bulk.actionCode()).isEqualTo("I20_SUBMIT");
        assertThat(bulk.actionName()).isEqualTo("Off-cycle payment");
        assertThat(bulk.moduleCode()).isEqualTo("GENERAL");
        assertThat(bulk.screenCode()).isEqualTo("I-20");
        assertThat(bulk.officeLevel()).isEqualTo(OfficeLevel.PAYING_UNIT);
        assertThat(bulk.levelCount()).isEqualTo(2);
        assertThat(bulk.otpAction()).isNull();
        assertThat(bulk.attachmentRequired()).isFalse();
        assertThat(bulk.targetTatHours()).isEqualTo(72);
        assertThat(bulk.effectiveFrom()).isEqualTo(TODAY);
        assertThat(bulk.levels()).isEqualTo(TWO);
        assertThat(codes(bulk)).isEmpty();
    }

    @Test
    void SRS_22_16_aCompleteTwoLevelRouteIsValid() {
        assertThat(codes(route(2, TWO, null, 72))).isEmpty();
        assertThat(codes(route(2, TWO, null, null))).as("no target is allowed").isEmpty();
    }

    @Test
    void SRS_22_17_twoOrThreeLevelsOnlyEachInItsPlace() {
        assertThat(codes(route(1, List.of(level(1, "PAYROLL_MAKER")), null, null)))
                .containsExactly("LEVEL_COUNT");
        assertThat(codes(route(4, TWO, null, null))).containsExactly("LEVEL_COUNT");
        assertThat(codes(route(3, TWO, null, null))).containsExactly("LEVELS_MISMATCH");
        assertThat(codes(route(2, null, null, null))).containsExactly("LEVELS_MISMATCH");
        assertThat(
                        codes(
                                route(
                                        2,
                                        List.of(
                                                level(2, "PAYROLL_CHECKER"),
                                                level(1, "PAYROLL_MAKER")),
                                        null,
                                        null)))
                .containsExactly("LEVEL_ORDER", "LEVEL_ORDER");
        assertThat(
                        codes(
                                route(
                                        3,
                                        List.of(
                                                level(1, "PAYROLL_MAKER"),
                                                level(2, "PAYROLL_CHECKER"),
                                                level(3, "AGM_CPF")),
                                        null,
                                        null)))
                .isEmpty();
    }

    @Test
    void BR_APPR_002_everyLevelNamesARoleInForceAndAnOfficeLevel() {
        assertThat(
                        codes(
                                route(
                                        2,
                                        List.of(
                                                level(1, "PAYROLL_MAKER"),
                                                new ProposedLevel(2, "NO_SUCH_ROLE", null, false)),
                                        null,
                                        null)))
                .containsExactly("LEVEL_ROLE", "LEVEL_OFFICE");
        assertThat(
                        codes(
                                route(
                                        2,
                                        List.of(level(1, null), level(2, "PAYROLL_CHECKER")),
                                        null,
                                        null)))
                .containsExactly("LEVEL_ROLE");
    }

    @Test
    void SRS_G_21_anOtpOnlyWithAGatedActionAndOnlyAtACheckersLevel() {
        List<ProposedLevel> checkerOtp =
                List.of(
                        level(1, "PAYROLL_MAKER"),
                        new ProposedLevel(2, "PAYROLL_CHECKER", OfficeLevel.PAYING_UNIT, true));
        assertThat(codes(route(2, checkerOtp, null, null))).containsExactly("OTP_WITHOUT_ACTION");
        assertThat(codes(route(2, checkerOtp, OtpGatedAction.SUPPLEMENTARY_POST_LOCK, null)))
                .isEmpty();
        List<ProposedLevel> makerOtp =
                List.of(
                        new ProposedLevel(1, "PAYROLL_MAKER", OfficeLevel.PAYING_UNIT, true),
                        level(2, "PAYROLL_CHECKER"));
        assertThat(codes(route(2, makerOtp, OtpGatedAction.SUPPLEMENTARY_POST_LOCK, null)))
                .containsExactly("OTP_AT_MAKER");
    }

    @Test
    void SRS_22_16_theTargetIsWholeHoursWithinItsColumn() {
        assertThat(codes(route(2, TWO, null, 0))).containsExactly("TARGET_HOURS");
        assertThat(codes(route(2, TWO, null, 1))).isEmpty();
        assertThat(codes(route(2, TWO, null, 99_999))).isEmpty();
        assertThat(codes(route(2, TWO, null, 100_000))).containsExactly("TARGET_HOURS");
    }

    @Test
    void BR_APPR_005_aVersionStartsTodayOrLaterOnADateNoOtherVersionUses() {
        ProposedRoute yesterday =
                new ProposedRoute(
                        "I20_SUBMIT",
                        "x",
                        "GENERAL",
                        "I-20",
                        OfficeLevel.PAYING_UNIT,
                        2,
                        null,
                        false,
                        null,
                        TODAY.minusDays(1),
                        TWO);
        assertThat(codes(yesterday)).containsExactly("EFFECTIVE_FROM_PAST");
        assertThat(codes(route(2, TWO, null, null), Set.of(TODAY)))
                .containsExactly("EFFECTIVE_FROM_TAKEN");
        ProposedRoute undated =
                new ProposedRoute(
                        "I20_SUBMIT",
                        "x",
                        "GENERAL",
                        null,
                        OfficeLevel.PAYING_UNIT,
                        2,
                        null,
                        false,
                        null,
                        null,
                        TWO);
        assertThat(codes(undated)).containsExactly("EFFECTIVE_FROM_PAST");
    }

    @Test
    void theIdentityOfTheActionIsCheckedFieldByField() {
        ProposedRoute bad =
                new ProposedRoute(
                        "i20", " ", "general", "I20", null, 2, null, false, null, TODAY, TWO);
        assertThat(codes(bad))
                .containsExactly(
                        "ACTION_CODE", "ACTION_NAME", "MODULE_CODE", "SCREEN_CODE", "OFFICE_LEVEL");
        ProposedRoute longName =
                new ProposedRoute(
                        "I20_SUBMIT",
                        "n".repeat(201),
                        "GENERAL",
                        null,
                        OfficeLevel.HQ,
                        2,
                        null,
                        false,
                        null,
                        TODAY,
                        TWO);
        assertThat(codes(longName)).containsExactly("ACTION_NAME");
        ProposedRoute longest =
                new ProposedRoute(
                        "I20_SUBMIT",
                        "n".repeat(200),
                        "GENERAL",
                        null,
                        OfficeLevel.HQ,
                        2,
                        null,
                        false,
                        null,
                        TODAY,
                        TWO);
        assertThat(codes(longest)).as("a name of 200 characters").isEmpty();
        ProposedRoute missing =
                new ProposedRoute(
                        null, null, null, null, OfficeLevel.HQ, 2, null, false, null, TODAY, TWO);
        assertThat(codes(missing))
                .as("an identity not given at all")
                .containsExactly("ACTION_CODE", "ACTION_NAME", "MODULE_CODE");
        assertThat(WorkflowRules.violations(bad, TODAY, ROLES, THIRD, Set.of()).get(0).message())
                .isEqualTo("a08.rule.ACTION_CODE");
    }

    @Test
    void SRS_22_12_versionsAreNumberedOnFromTheLatest() {
        assertThat(WorkflowRules.nextVersion(List.of())).isEqualTo(1);
        assertThat(WorkflowRules.nextVersion(List.of(1, 3, 2))).isEqualTo(4);
    }

    // NEW-G1-09 (US-02-06 AC5, SC-02-0012): a third level only for an action the matrix names
    // one for (M-16 THIRD_LEVEL_ACTION); two levels are never affected by the list.
    @Test
    void SRS_22_12_aThirdLevelOnlyWhereTheMatrixNamesOne() {
        ProposedRoute three =
                route(
                        3,
                        List.of(
                                level(1, "PAYROLL_MAKER"),
                                level(2, "PAYROLL_CHECKER"),
                                level(3, "AGM_CPF")),
                        null,
                        null);
        Predicate<String> named = Set.of("I22_CREATE")::contains;
        assertThat(WorkflowRules.violations(three, TODAY, ROLES, named, Set.of()))
                .as("I20_SUBMIT: not named, refused")
                .extracting(Violation::field, Violation::code)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("levelCount", "THIRD_LEVEL_NOT_NAMED"));
        assertThat(
                        WorkflowRules.violations(
                                three, TODAY, ROLES, code -> code.equals("I20_SUBMIT"), Set.of()))
                .as("named: allowed")
                .isEmpty();
        assertThat(
                        WorkflowRules.violations(
                                route(2, TWO, null, null), TODAY, ROLES, named, Set.of()))
                .as("two levels never need the list")
                .isEmpty();
        assertThat(
                        WorkflowRules.violations(
                                        new ProposedRoute(
                                                null,
                                                "x",
                                                "GENERAL",
                                                null,
                                                OfficeLevel.HQ,
                                                3,
                                                null,
                                                false,
                                                null,
                                                TODAY,
                                                three.levels()),
                                        TODAY,
                                        ROLES,
                                        named,
                                        Set.of())
                                .stream()
                                .map(Violation::code)
                                .toList())
                .as("no action code: the code rule alone reports it")
                .containsExactly("ACTION_CODE");
        assertThat(WorkflowRules.violations(three, TODAY, ROLES, named, Set.of()).get(0).message())
                .isEqualTo("a08.rule.THIRD_LEVEL_NOT_NAMED");
    }
}
