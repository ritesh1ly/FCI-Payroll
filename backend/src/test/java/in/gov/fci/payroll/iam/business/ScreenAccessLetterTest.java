package in.gov.fci.payroll.iam.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The X-01 Screen-Level Access Matrix letter of a role on a screen (SRS-22-8). */
class ScreenAccessLetterTest {

    @Test
    void SRS_22_8_withoutViewTheScreenIsNotInTheMenuWhateverElseTheRoleHolds() {
        assertThat(ScreenAccessLetter.of(Set.of())).isEmpty();
        assertThat(ScreenAccessLetter.of(Set.of("APPROVE", "EDIT", "EXPORT"))).isEmpty();
    }

    @Test
    void SRS_22_8_approvingOutranksEditingAndViewing() {
        assertThat(ScreenAccessLetter.of(Set.of("VIEW", "APPROVE"))).contains("A");
        assertThat(ScreenAccessLetter.of(Set.of("VIEW", "EDIT", "APPROVE"))).contains("A");
    }

    @Test
    void SRS_22_8_anyActionBeyondViewAndExportIsAnEdit() {
        assertThat(ScreenAccessLetter.of(Set.of("VIEW", "EDIT"))).contains("E");
        assertThat(ScreenAccessLetter.of(Set.of("VIEW", "CREATE"))).contains("E");
        assertThat(ScreenAccessLetter.of(Set.of("VIEW", "EXPORT", "SUBMIT"))).contains("E");
    }

    @Test
    void SRS_22_8_viewingAndExportingAloneIsAView() {
        assertThat(ScreenAccessLetter.of(Set.of("VIEW"))).contains("V");
        assertThat(ScreenAccessLetter.of(Set.of("VIEW", "EXPORT"))).contains("V");
    }
}
