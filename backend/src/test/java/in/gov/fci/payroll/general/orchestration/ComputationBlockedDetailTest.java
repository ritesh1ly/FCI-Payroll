package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SRS-G-9: a member the Actual run could not compute is raised on X-05 with the failure as the
 * worklist shows it, within payroll_exception.detail (2000 characters).
 */
class ComputationBlockedDetailTest {

    private static final String PREFIX = "Not computed: ";

    @Test
    void SRS_G_9_theFailureIsShownAsNotComputedOrWithoutAReason() {
        assertThat(ComputationBlockedExceptions.detail("no DA rate for 202610"))
                .isEqualTo("Not computed: no DA rate for 202610");
        assertThat(ComputationBlockedExceptions.detail(null))
                .isEqualTo("Not computed: no reason given");
    }

    @Test
    void SRS_G_9_aDetailOfExactly2000CharactersIsKeptAnd2001IsCut() {
        String exact = "x".repeat(2000 - PREFIX.length());
        assertThat(ComputationBlockedExceptions.detail(exact))
                .isEqualTo(PREFIX + exact)
                .hasSize(2000);
        assertThat(ComputationBlockedExceptions.detail(exact + "y"))
                .hasSize(2000)
                .endsWith("x...")
                .startsWith(PREFIX);
    }
}
