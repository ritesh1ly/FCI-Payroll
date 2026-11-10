package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.approval.OtpProof;
import org.junit.jupiter.api.Test;

/** Masking rules shared by the audit trail and the employee views. */
class MaskingRulesTest {

    @Test
    void bankAccountsAreMaskedInTheAuditTrailAndOtherFieldsAreNot() {
        assertThat(EmployeeApprovalEffect.mask("bankAccount", "00001234567"))
                .isEqualTo("*******4567");
        assertThat(EmployeeApprovalEffect.mask("bankAccount", "123")).isEqualTo("123");
        assertThat(EmployeeApprovalEffect.mask("bankAccount", null)).isNull();
        assertThat(EmployeeApprovalEffect.mask("mobileNumber", "9000012345"))
                .isEqualTo("9000012345");
        assertThat(new OtpProof(5L, "123456").toString()).doesNotContain("123456");
    }
}
