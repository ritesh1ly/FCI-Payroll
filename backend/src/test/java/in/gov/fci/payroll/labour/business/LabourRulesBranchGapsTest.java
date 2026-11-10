package in.gov.fci.payroll.labour.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.labour.business.LabourRules.Entry;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** SRS-2-30: the mobile number is optional; left out or blank it is no defect. */
class LabourRulesBranchGapsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private static Entry entry(String mobile) {
        return new Entry(
                "DEPT_LABOUR",
                "Ram",
                "Kumar",
                LocalDate.of(1990, 5, 10),
                LocalDate.of(2010, 7, 1),
                "ABCPK1234D",
                "SBIN0001234",
                "12345678",
                mobile,
                true,
                "DL/12345");
    }

    @Test
    void SRS_2_30_aMissingOrBlankMobileIsNoDefectAndAWrongOneIs() {
        assertThat(LabourRules.defect(entry(null), TODAY)).isEmpty();
        assertThat(LabourRules.defect(entry("   "), TODAY)).isEmpty();
        assertThat(LabourRules.defect(entry("9876543210"), TODAY)).isEmpty();
        assertThat(LabourRules.defect(entry("12345"), TODAY)).contains("MOBILE");
    }
}
