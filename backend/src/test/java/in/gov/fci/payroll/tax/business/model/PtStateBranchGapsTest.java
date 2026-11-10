package in.gov.fci.payroll.tax.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The M-18 audit image of a PT state (SRS-7-4, SRS-20-2): a state not yet given its approved values
 * shows no dates, and an approved one shows both ends of its period.
 */
class PtStateBranchGapsTest {

    @Test
    void SRS_7_4_aStateWithoutItsValuesShowsNoDatesAndAnApprovedOneShowsBoth() {
        Map<String, String> fresh = PtState.of("KA").image();
        assertThat(fresh).containsEntry("effective_from", null).containsEntry("effective_to", null);
        assertThat(fresh).containsEntry("pt_applicable_flag", "N");

        PtState ka = PtState.of("KA");
        ka.apply(
                "Karnataka",
                true,
                "PT-KA-1",
                "PT.SLAB",
                "MONTHLY",
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2027, 3, 31));
        assertThat(ka.image())
                .containsEntry("effective_from", "2026-04-01")
                .containsEntry("effective_to", "2027-03-31")
                .containsEntry("pt_applicable_flag", "Y");
    }
}
