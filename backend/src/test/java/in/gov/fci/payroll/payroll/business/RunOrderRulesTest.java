package in.gov.fci.payroll.payroll.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.payroll.business.RunOrderRules.Defect;
import in.gov.fci.payroll.payroll.business.RunOrderRules.Step;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SRS-15-1 / BR-CYC-003, SRS-5-10 (plan 05 D-13): a new run order names every active step once, and
 * no step runs before the step it depends on.
 */
class RunOrderRulesTest {

    private static final List<Step> STEPS =
            List.of(
                    new Step("STAFF_PAYROLL", null),
                    new Step("DEPT_LABOUR_WAGES", null),
                    new Step("NWNP_INITIALISE", null),
                    new Step("NWNP_CREATE_TRANSACTION", "NWNP_INITIALISE"),
                    new Step("LABOUR_INCENTIVE", "DEPT_LABOUR_WAGES"),
                    new Step("ORPHAN", "RETIRED_STEP"));

    @Test
    void SRS_15_1_anyOrderThatKeepsTheDependenciesIsAccepted() {
        assertThat(
                        RunOrderRules.defect(
                                List.of(
                                        "DEPT_LABOUR_WAGES",
                                        "STAFF_PAYROLL",
                                        "LABOUR_INCENTIVE",
                                        "ORPHAN",
                                        "NWNP_INITIALISE",
                                        "NWNP_CREATE_TRANSACTION"),
                                STEPS))
                .isEmpty();
    }

    @Test
    void SRS_15_1_labourIncentiveNeverRunsBeforeTheWageComputation() {
        assertThat(
                        RunOrderRules.defect(
                                List.of(
                                        "LABOUR_INCENTIVE",
                                        "DEPT_LABOUR_WAGES",
                                        "STAFF_PAYROLL",
                                        "ORPHAN",
                                        "NWNP_INITIALISE",
                                        "NWNP_CREATE_TRANSACTION"),
                                STEPS))
                .contains(new Defect("BEFORE_DEPENDENCY", "LABOUR_INCENTIVE", "DEPT_LABOUR_WAGES"));
    }

    @Test
    void SRS_5_10_theNwnpMonthEndStepsKeepTheirChain() {
        assertThat(
                        RunOrderRules.defect(
                                List.of(
                                        "STAFF_PAYROLL",
                                        "DEPT_LABOUR_WAGES",
                                        "LABOUR_INCENTIVE",
                                        "ORPHAN",
                                        "NWNP_CREATE_TRANSACTION",
                                        "NWNP_INITIALISE"),
                                STEPS))
                .contains(
                        new Defect(
                                "BEFORE_DEPENDENCY", "NWNP_CREATE_TRANSACTION", "NWNP_INITIALISE"));
    }

    @Test
    void SRS_15_1_everyStepOnceAndNoOther() {
        List<String> missing =
                List.of(
                        "STAFF_PAYROLL",
                        "DEPT_LABOUR_WAGES",
                        "LABOUR_INCENTIVE",
                        "NWNP_INITIALISE",
                        "NWNP_CREATE_TRANSACTION");
        assertThat(RunOrderRules.defect(missing, STEPS))
                .contains(new Defect("INCOMPLETE", "ORPHAN", null));
        assertThat(RunOrderRules.defect(List.of("STAFF_PAYROLL", "STAFF_PAYROLL"), STEPS))
                .contains(new Defect("INCOMPLETE", "STAFF_PAYROLL", null));
        assertThat(RunOrderRules.defect(List.of("UNKNOWN"), STEPS))
                .contains(new Defect("INCOMPLETE", "UNKNOWN", null));
    }
}
