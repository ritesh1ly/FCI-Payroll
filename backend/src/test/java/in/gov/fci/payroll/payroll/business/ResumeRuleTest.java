package in.gov.fci.payroll.payroll.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.payroll.business.ResumeRule.Change;
import org.junit.jupiter.api.Test;

/** SRS-15-26 / BR-CYC-013: a run continues only with the parameters and versions it started on. */
class ResumeRuleTest {

    @Test
    void BR_CYC_013_nothingChangedTheRunMayContinue() {
        assertThat(ResumeRule.change("p", "v", "p", "v")).isEmpty();
    }

    @Test
    void BR_CYC_013_aVersionActivatedSinceTheStartRefusesTheResume() {
        assertThat(ResumeRule.change("p", "v", "p", "w")).contains(Change.RULE_VERSIONS);
    }

    @Test
    void BR_CYC_013_changedParametersRefuseTheResumeAndAreNamedFirst() {
        assertThat(ResumeRule.change("p", "v", "q", "v")).contains(Change.PARAMETERS);
        assertThat(ResumeRule.change("p", "v", "q", "w")).contains(Change.PARAMETERS);
    }

    @Test
    void BR_CYC_013_anUnknownSideIsNeverTakenAsUnchanged() {
        assertThat(ResumeRule.change("p", "v", "p", null)).contains(Change.RULE_VERSIONS);
        assertThat(ResumeRule.change(null, "v", "p", "v")).contains(Change.PARAMETERS);
        assertThat(ResumeRule.change("p", null, "p", "v")).contains(Change.RULE_VERSIONS);
    }
}
