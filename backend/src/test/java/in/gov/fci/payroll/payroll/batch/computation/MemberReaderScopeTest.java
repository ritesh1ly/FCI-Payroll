package in.gov.fci.payroll.payroll.batch.computation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * M-01 Casual Labour Indicator (SRS l.3772, US-GAP-275; SRS-G-55): contractual employees are paid
 * in the Employee payroll, Casual Labour in the labour payrolls. The staff engine reads staff
 * records only ({@code employee_type = 'STAFF'}); a labour member - casual or not, held in the
 * Payroll labour master with its own payroll type - is never computed here, and a separated or
 * dismissed member is not read at all.
 */
class MemberReaderScopeTest {

    @Test
    void SRS_G_55_theStaffEngineReadsStaffOnlyNeverLabourAndNoSeparatedMember() {
        assertThat(MemberReader.WHERE)
                .contains("e.employee_type = 'STAFF'")
                .contains("e.employment_status NOT IN ('SEPARATED', 'DISMISSED')")
                .contains("e.record_status = 'ACTIVE'")
                .doesNotContain("casual_labour_flag");
    }
}
