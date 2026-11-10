package in.gov.fci.payroll.employee.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The employee record (M-01; target DDL v4.0 S03): names as shown, the display status that flags
 * missing statutory and bank data, approved changes of the only Payroll-owned fields, and the
 * effects of approved events and transfers.
 */
class EmployeeModelTest {

    private static final Instant NOW = Instant.parse("2026-10-21T05:00:00Z");

    private static Employee employee(String first, String middle, String last) {
        Employee e = BeanUtils.instantiateClass(Employee.class);
        ReflectionTestUtils.setField(e, "firstName", first);
        ReflectionTestUtils.setField(e, "middleName", middle);
        ReflectionTestUtils.setField(e, "lastName", last);
        ReflectionTestUtils.setField(e, "recordStatus", RecordStatus.ACTIVE);
        ReflectionTestUtils.setField(e, "panNumber", "ABCDE1234F");
        ReflectionTestUtils.setField(e, "cpfCode", "CPF1");
        ReflectionTestUtils.setField(e, "bankBranchId", 9L);
        ReflectionTestUtils.setField(e, "labour", false);
        return e;
    }

    /**
     * SRS I-04 field table (UAN, verified at tier 1); SRS-6-51 (Aadhaar: last four digits only).
     */
    @Test
    void SRS_6_51_theCpfChainSeesTheUanAndOnlyTheLastFourDigitsOfTheAadhaar() {
        Employee e = employee("Sourav", null, "Banerjee");
        ReflectionTestUtils.setField(e, "uanNumber", " 100000105220 ");
        ReflectionTestUtils.setField(e, "aadhaarNumber", "9999 0010 4886");
        assertThat(e.uanOrNull()).isEqualTo("100000105220");
        assertThat(e.aadhaarLastFourMasked()).isEqualTo("XXXX-XXXX-4886").doesNotContain("9999");
        // Nothing on record, or too short to be an Aadhaar: nothing is shown.
        ReflectionTestUtils.setField(e, "uanNumber", "  ");
        ReflectionTestUtils.setField(e, "aadhaarNumber", null);
        assertThat(e.uanOrNull()).isNull();
        assertThat(e.aadhaarLastFourMasked()).isNull();
        ReflectionTestUtils.setField(e, "aadhaarNumber", "4886");
        assertThat(e.aadhaarLastFourMasked()).isNull();
    }

    @Test
    void SRS_6_51_onlyWhetherAUanOrAadhaarIsOnRecordLeavesTheModule() {
        Employee e = employee("Sourav", null, "Banerjee");
        for (String none : new String[] {null, "", "   "}) {
            ReflectionTestUtils.setField(e, "uanNumber", none);
            ReflectionTestUtils.setField(e, "aadhaarNumber", none);
            assertThat(e.hasUan()).as(String.valueOf(none)).isFalse();
            assertThat(e.hasAadhaar()).as(String.valueOf(none)).isFalse();
        }
        ReflectionTestUtils.setField(e, "uanNumber", "100200300400");
        ReflectionTestUtils.setField(e, "aadhaarNumber", "999988887777");
        assertThat(e.hasUan()).isTrue();
        assertThat(e.hasAadhaar()).isTrue();
    }

    @Test
    void M_01_theFullNameSkipsMissingAndBlankParts() {
        assertThat(employee("Sourav", "K", "Banerjee").fullName()).isEqualTo("Sourav K Banerjee");
        assertThat(employee("Sourav", null, "Banerjee").fullName()).isEqualTo("Sourav Banerjee");
        assertThat(employee("Sourav", " ", null).fullName()).isEqualTo("Sourav");
        assertThat(employee("Sourav", "", "").fullName()).isEqualTo("Sourav");
    }

    @Test
    void M_01_theShortNameIsTheInitialAndSurname() {
        assertThat(employee("Sourav", null, "Banerjee").shortName()).isEqualTo("S. Banerjee");
        assertThat(employee("Prakash", null, null).shortName()).isEqualTo("Prakash");
    }

    // SC-16-0130: a record without a valid bank account or IFSC is flagged before month-end
    // processing.
    @Test
    void M_01_theDisplayStatusFlagsPendingSetupChangesAndMissingData() {
        Employee e = employee("A", null, "B");
        assertThat(e.displayStatus()).isEqualTo("ACTIVE");
        ReflectionTestUtils.setField(e, "bankBranchId", null);
        assertThat(e.displayStatus()).isEqualTo("BANK_INVALID");
        ReflectionTestUtils.setField(e, "cpfCode", null);
        assertThat(e.displayStatus()).isEqualTo("PAN_CPF_MISSING");
        ReflectionTestUtils.setField(e, "labour", true);
        assertThat(e.displayStatus()).as("labour have no CPF code").isEqualTo("BANK_INVALID");
        ReflectionTestUtils.setField(e, "panNumber", null);
        assertThat(e.displayStatus()).isEqualTo("PAN_CPF_MISSING");
        ReflectionTestUtils.setField(e, "recordStatus", RecordStatus.CHANGE_PENDING);
        assertThat(e.displayStatus()).isEqualTo("CHANGE_PENDING");
        ReflectionTestUtils.setField(e, "recordStatus", RecordStatus.PAYROLL_SETUP_PENDING);
        assertThat(e.displayStatus()).isEqualTo("PAYROLL_SETUP_PENDING");
    }

    @Test
    void R_MC_aPendingChangeIsClearedOnlyFromChangePending() {
        Employee e = employee("A", null, "B");
        ReflectionTestUtils.setField(e, "recordStatus", RecordStatus.CHANGE_PENDING);
        assertThat(e.displayStatus()).isEqualTo("CHANGE_PENDING");
        e.clearChangePending(NOW.plusSeconds(1));
        assertThat(ReflectionTestUtils.getField(e, "recordStatus")).isEqualTo(RecordStatus.ACTIVE);
        assertThat(ReflectionTestUtils.getField(e, "updatedAt")).isEqualTo(NOW.plusSeconds(1));
        ReflectionTestUtils.setField(e, "recordStatus", RecordStatus.PAYROLL_SETUP_PENDING);
        e.clearChangePending(NOW.plusSeconds(2));
        assertThat(ReflectionTestUtils.getField(e, "recordStatus"))
                .isEqualTo(RecordStatus.PAYROLL_SETUP_PENDING);
        assertThat(ReflectionTestUtils.getField(e, "updatedAt")).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void I_17_anApprovedEventSetsTheEmploymentStatus() {
        Employee e = employee("A", null, "B");
        e.applyEmploymentStatus("SUSPENDED", NOW);
        assertThat(ReflectionTestUtils.getField(e, "employmentStatus")).isEqualTo("SUSPENDED");
        assertThat(ReflectionTestUtils.getField(e, "updatedAt")).isEqualTo(NOW);
        assertThatThrownBy(() -> e.applyEmploymentStatus(null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    // SC-16-0108: each transfer is kept on the record's history.
    // SC-04-0034: the origin is the current attachment before the transfer.
    @Test
    void M_11_aTransferMovesTheEmployeeAndRenumbersOnlyWhenGiven() {
        Employee e = employee("A", null, "B");
        ReflectionTestUtils.setField(e, "empNum", "105220");
        e.applyTransfer(77L, "  205220 ", NOW);
        assertThat(ReflectionTestUtils.getField(e, "locationId")).isEqualTo(77L);
        assertThat(ReflectionTestUtils.getField(e, "empNum")).isEqualTo("205220");
        e.applyTransfer(78L, " ", NOW);
        assertThat(ReflectionTestUtils.getField(e, "empNum")).isEqualTo("205220");
        e.applyTransfer(79L, null, NOW.plusSeconds(3));
        assertThat(ReflectionTestUtils.getField(e, "empNum")).isEqualTo("205220");
        assertThat(ReflectionTestUtils.getField(e, "locationId")).isEqualTo(79L);
        assertThat(ReflectionTestUtils.getField(e, "updatedAt")).isEqualTo(NOW.plusSeconds(3));
        assertThatThrownBy(() -> e.applyTransfer(null, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }
}
