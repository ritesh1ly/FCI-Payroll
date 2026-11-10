package in.gov.fci.payroll.workflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.workflow.business.model.WorkflowAction;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction.OfficeLevel;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SRS-22-16 / SRS-22-19: the office level of each approval route is configuration; a screen whose
 * routes in force are all at Headquarters is proposed at Headquarters only, so its screens show the
 * action disabled with that reason elsewhere.
 */
class HeadquartersOnlyScreensTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    private static WorkflowAction route(
            String code, String screen, OfficeLevel level, LocalDate from) {
        WorkflowAction a =
                WorkflowAction.propose(
                        code, code, "GENERAL", screen, level, 2, null, false, null, 1, from);
        a.approve(from);
        return a;
    }

    @Test
    void SRS_22_16_aScreenWhoseRoutesInForceAreAllAtHqIsHqOnly() {
        List<WorkflowAction> routes =
                List.of(
                        route("M24_CREATE", "M-24", OfficeLevel.HQ, TODAY.minusDays(30)),
                        route("M16_CREATE", "M-16", OfficeLevel.HQ, TODAY.minusDays(30)),
                        route("M16_EDIT", "M-16", OfficeLevel.HQ, TODAY.minusDays(30)),
                        route("I20_CREATE", "I-20", OfficeLevel.PAYING_UNIT, TODAY.minusDays(30)));
        assertThat(HeadquartersOnlyScreens.of(routes, TODAY)).containsExactly("M-16", "M-24");
    }

    @Test
    void SRS_22_16_oneRouteBelowHqLeavesTheScreenOpenToItsOffices() {
        List<WorkflowAction> routes =
                List.of(
                        route("M25_CREATE", "M-25", OfficeLevel.HQ, TODAY.minusDays(30)),
                        route("M25_EDIT", "M-25", OfficeLevel.PAYING_UNIT, TODAY.minusDays(30)));
        assertThat(HeadquartersOnlyScreens.of(routes, TODAY)).isEmpty();
    }

    @Test
    void SRS_22_16_onlyRoutesInForceTodayCount() {
        WorkflowAction pending =
                WorkflowAction.propose(
                        "A08_EDIT",
                        "A08_EDIT",
                        "GENERAL",
                        "A-08",
                        OfficeLevel.PAYING_UNIT,
                        2,
                        null,
                        false,
                        null,
                        2,
                        TODAY);
        List<WorkflowAction> routes =
                List.of(
                        route("A08_CREATE", "A-08", OfficeLevel.HQ, TODAY.minusDays(30)),
                        // Not yet approved, and not yet in force: neither opens A-08 to offices.
                        pending,
                        route("A08_LATER", "A-08", OfficeLevel.PAYING_UNIT, TODAY.plusDays(5)),
                        // A route without a screen belongs to no screen.
                        route("X_NO_SCREEN", null, OfficeLevel.HQ, TODAY.minusDays(30)));
        assertThat(HeadquartersOnlyScreens.of(routes, TODAY)).containsExactly("A-08");
    }
}
