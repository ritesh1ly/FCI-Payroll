package in.gov.fci.payroll.employee.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.employee.api.HrmsNewEmployees.NewEmployee;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The checks of a new staff record from HRMS and of its onboarding submission (plan 16 D16-34,
 * D16-35; SRS-1-11, SRS-1-13, SRS-1-17, SRS-1-52).
 */
class OnboardingRulesTest {

    private static NewEmployee valid() {
        return new NewEmployee(
                "104990",
                "Kavita",
                "Onboard",
                "Ramesh Onboard",
                "F",
                LocalDate.of(1994, 3, 12),
                LocalDate.of(2026, 10, 1),
                "AM",
                "E-2",
                "DH001",
                "DMOPO7101K",
                "999900104886",
                "300000007101",
                "SBIN0DEMO01",
                "CPF/DL/7101",
                null,
                "9811007101",
                "kavita.onboard@fci-demo.test");
    }

    private static NewEmployee with(java.util.function.UnaryOperator<Object[]> change) {
        NewEmployee v = valid();
        Object[] f = {
            v.eis(),
            v.firstName(),
            v.lastName(),
            v.fatherName(),
            v.gender(),
            v.birthDate(),
            v.joiningDate(),
            v.designationCode(),
            v.payScaleCode(),
            v.postingCode(),
            v.panNumber(),
            v.aadhaar(),
            v.bankAccountNumber(),
            v.ifsc(),
            v.cpfCode(),
            v.providentFundType(),
            v.mobileNumber(),
            v.emailAddress()
        };
        Object[] g = change.apply(f);
        return new NewEmployee(
                (String) g[0],
                (String) g[1],
                (String) g[2],
                (String) g[3],
                (String) g[4],
                (LocalDate) g[5],
                (LocalDate) g[6],
                (String) g[7],
                (String) g[8],
                (String) g[9],
                (String) g[10],
                (String) g[11],
                (String) g[12],
                (String) g[13],
                (String) g[14],
                (String) g[15],
                (String) g[16],
                (String) g[17]);
    }

    private static String defectWith(int field, Object value) {
        return OnboardingRules.defect(
                        with(
                                f -> {
                                    f[field] = value;
                                    return f;
                                }))
                .orElse(null);
    }

    @Test
    void SRS_1_52_aCompleteRecordHasNoDefect() {
        assertThat(OnboardingRules.defect(valid())).isEmpty();
        assertThat(defectWith(2, null)).as("the last name is optional").isNull();
        assertThat(defectWith(4, " ")).as("gender optional").isNull();
        assertThat(defectWith(14, null)).as("the CPF code may be allotted later").isNull();
        assertThat(defectWith(16, null)).isNull();
        assertThat(defectWith(17, null)).isNull();
    }

    @Test
    void SRS_1_52_eachMissingOrMalformedFieldIsNamed() {
        assertThat(OnboardingRules.defect(null)).contains("EIS");
        assertThat(defectWith(0, "no spaces allowed")).isEqualTo("EIS");
        assertThat(defectWith(1, "9am")).isEqualTo("NAME");
        assertThat(defectWith(2, "X$")).isEqualTo("NAME");
        assertThat(defectWith(3, "R@m")).isEqualTo("NAME");
        assertThat(defectWith(5, null)).isEqualTo("BIRTH_DATE");
        assertThat(defectWith(6, null)).isEqualTo("JOINING_DATE");
        assertThat(defectWith(6, LocalDate.of(1994, 3, 12)))
                .as("joining on the birth day")
                .isEqualTo("JOINING_DATE");
        assertThat(defectWith(4, "X")).isEqualTo("GENDER");
        assertThat(defectWith(7, "")).isEqualTo("DESIGNATION");
        assertThat(defectWith(8, null)).isEqualTo("PAY_SCALE");
        assertThat(defectWith(9, "a code far longer than twenty")).isEqualTo("POSTING");
        assertThat(defectWith(10, "DMOPO7101")).isEqualTo("PAN");
        assertThat(defectWith(11, "123456789012")).as("never starts 0 or 1").isEqualTo("AADHAAR");
        assertThat(defectWith(12, "12AB")).isEqualTo("BANK_ACCOUNT");
        assertThat(defectWith(13, "SBIN1DEMO01")).isEqualTo("IFSC");
        assertThat(defectWith(14, "cpf code?")).isEqualTo("CPF_CODE");
        assertThat(defectWith(16, "5811007101")).isEqualTo("MOBILE");
        assertThat(defectWith(17, "no-at-sign")).isEqualTo("EMAIL");
    }

    @Test
    void SRS_1_17_idaIsCpfAndCdaOrFnbIsGpfOrNpsAsHrmsHoldsIt() {
        // BR-PEN-001: every member is classified (CPF / GPF / NPS) as HRMS holds it; a missing
        // classification is a defect, never defaulted.
        assertThat(OnboardingRules.providentFund("IDA", "NPS")).contains("CPF");
        assertThat(OnboardingRules.providentFund("CDA", " gpf ")).contains("GPF");
        assertThat(OnboardingRules.providentFund("FNB", "NPS")).contains("NPS");
        assertThat(OnboardingRules.providentFund("CDA", null)).isEmpty();
        assertThat(OnboardingRules.providentFund("CDA", "CPF")).isEmpty();
        assertThat(OnboardingRules.providentFund(null, "NPS")).isEmpty();
    }

    @Test
    void SRS_1_11_aCdaMemberNeedsItsStaffCodeAndEverySubmissionItsRemark() {
        assertThat(OnboardingRules.submissionDefect("CDA", "CDA/DL/7", "Ready")).isEmpty();
        assertThat(OnboardingRules.submissionDefect("IDA", null, "Ready")).isEmpty();
        assertThat(OnboardingRules.submissionDefect("CDA", " ", "Ready"))
                .contains("CDA_STAFF_CODE");
        assertThat(OnboardingRules.submissionDefect("IDA", "CDA/1", "Ready"))
                .contains("CDA_STAFF_CODE");
        assertThat(OnboardingRules.submissionDefect("CDA", "a code with spaces", "Ready"))
                .contains("CDA_STAFF_CODE");
        assertThat(OnboardingRules.submissionDefect("IDA", null, null)).contains("REASON");
        assertThat(OnboardingRules.submissionDefect("IDA", null, "x".repeat(501)))
                .contains("REASON");
        assertThat(OnboardingRules.submissionDefect("IDA", null, "नाम")).contains("REASON");
    }

    @Test
    void SRS_1_11_aRemarkOf500CharactersIsAcceptedAndOneMoreIsNot() {
        assertThat(OnboardingRules.submissionDefect("IDA", null, "r".repeat(500))).isEmpty();
        assertThat(OnboardingRules.submissionDefect("IDA", null, "r".repeat(501)))
                .contains("REASON");
    }

    /** SRS-1-11 / R-ASCII: the onboarding reason is a text area: line breaks kept, 500 at most. */
    @Test
    void SRS_1_11_anOnboardingReasonOverSeveralLinesIsAccepted() {
        assertThat(OnboardingRules.submissionDefect("IDA", null, "Ready\r\nchecked")).isEmpty();
        assertThat(OnboardingRules.submissionDefect("IDA", null, "x".repeat(500))).isEmpty();
        assertThat(OnboardingRules.submissionDefect("IDA", null, "x".repeat(501)))
                .contains("REASON");
        assertThat(OnboardingRules.submissionDefect("IDA", null, "Ready\u0007")).contains("REASON");
    }

    /** SRS-1-52: a PAN or IFSC not given is named as the defect (no value to upper-case). */
    @Test
    void SRS_1_52_aMissingPanOrIfscIsNamed() {
        assertThat(OnboardingRules.defect(with(f -> set(f, 10, null)))).contains("PAN");
        assertThat(OnboardingRules.defect(with(f -> set(f, 13, null)))).contains("IFSC");
        // Lower case is read upper case: a valid PAN and IFSC typed in small letters pass.
        assertThat(
                        OnboardingRules.defect(
                                with(f -> set(set(f, 10, "dmopo7101k"), 13, "sbin0demo01"))))
                .isEmpty();
    }

    private static Object[] set(Object[] f, int at, Object value) {
        f[at] = value;
        return f;
    }
}
