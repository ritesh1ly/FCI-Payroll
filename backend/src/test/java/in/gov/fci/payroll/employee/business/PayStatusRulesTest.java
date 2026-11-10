package in.gov.fci.payroll.employee.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.employee.business.PayStatusRules.PayStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * M-01 Pay Status (SRS l.3738, US-GAP-275; OD-53): derived from the employment status and the Hold
 * flag, the employment status first; the five values the SRS lists and Absconding.
 */
class PayStatusRulesTest {

    @ParameterizedTest
    @CsvSource({
        "IN_SERVICE,false,ACTIVE",
        "IN_SERVICE,true,HOLD",
        "SUSPENDED,false,SUSPENDED",
        "SUSPENDED,true,SUSPENDED",
        "ON_DEPUTATION,false,DEPUTATION",
        "ON_DEPUTATION,true,DEPUTATION",
        "SEPARATED,false,SEPARATED",
        "SEPARATED,true,SEPARATED",
        "DISMISSED,false,SEPARATED",
        "ABSCONDING,false,ABSCONDING",
        "ABSCONDING,true,ABSCONDING",
        "UNKNOWN,false,ACTIVE",
        ",false,ACTIVE",
        ",true,HOLD"
    })
    void SRS_1_52_thePayStatusIsDerivedEmploymentStatusFirst(
            String employmentStatus, boolean hold, PayStatus expected) {
        assertThat(PayStatusRules.of(employmentStatus, hold)).isEqualTo(expected);
    }
}
