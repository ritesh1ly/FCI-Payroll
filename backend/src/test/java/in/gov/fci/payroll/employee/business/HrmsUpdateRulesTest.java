package in.gov.fci.payroll.employee.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.employee.business.HrmsUpdateRules.FetchResult;
import in.gov.fci.payroll.employee.business.HrmsUpdateRules.Status;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** HRMS Update Request rules (plan 16 phase 16c; SRS-1-70). */
class HrmsUpdateRulesTest {

    @Test
    void SRS_1_70_aDifferentHrmsValueDiffersAndNoneOrTheSameIsNoChange() {
        assertThat(HrmsUpdateRules.compare(Optional.of("ABCDE1234F"), "ABCDE9999F"))
                .isEqualTo(FetchResult.DIFFERS);
        assertThat(HrmsUpdateRules.compare(Optional.of(" 61200 "), "61200"))
                .as("blanks around the value")
                .isEqualTo(FetchResult.NO_CHANGE);
        assertThat(HrmsUpdateRules.compare(Optional.of("61200"), null))
                .isEqualTo(FetchResult.DIFFERS);
        assertThat(HrmsUpdateRules.compare(Optional.empty(), "61200"))
                .isEqualTo(FetchResult.NO_CHANGE);
        assertThat(HrmsUpdateRules.compare(Optional.of("  "), "61200"))
                .isEqualTo(FetchResult.NO_CHANGE);
    }

    @Test
    void SRS_1_70_aRequestNamesAFieldWithPayEffectAndADate() {
        assertThat(HrmsUpdateRules.defect(true, true, null)).isEmpty();
        assertThat(HrmsUpdateRules.defect(true, true, "R".repeat(60))).isEmpty();
        assertThat(HrmsUpdateRules.defect(true, true, " " + "R".repeat(60) + " ")).isEmpty();
        assertThat(HrmsUpdateRules.defect(true, true, "R".repeat(61)))
                .contains("REFERENCE_TOO_LONG");
        assertThat(HrmsUpdateRules.defect(false, true, null)).contains("NOT_PAY_EFFECT");
        assertThat(HrmsUpdateRules.defect(true, false, null)).contains("NO_DATE");
    }

    @Test
    void SRS_1_70_onlyAFailedFetchIsFetchedAgain() {
        for (Status s : Status.values()) {
            assertThat(HrmsUpdateRules.refetchable(s)).isEqualTo(s == Status.FETCH_FAILED);
        }
        assertThat(HrmsUpdateRules.requestNo(2026, 7)).isEqualTo("HUR-2026-0007");
        assertThat(HrmsUpdateRules.requestNo(2026, 12345)).isEqualTo("HUR-2026-12345");
    }
}
