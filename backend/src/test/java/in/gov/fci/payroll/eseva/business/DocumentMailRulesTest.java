package in.gov.fci.payroll.eseva.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Rules of the e-mailed documents (OD-148; SRS-INT-16 retries and back-off as settings; SRS-17-12;
 * PENDING MAIL-1 password rule as a setting, MAIL-2).
 */
class DocumentMailRulesTest {

    @Test
    void SRS_INT_16_theRetryIsTheBackOffStretchedByAtMostTheJitter() {
        assertThat(DocumentMailRules.retryDelaySeconds("FIXED", 100, 1, 20, 0.0)).isEqualTo(100);
        assertThat(DocumentMailRules.retryDelaySeconds("FIXED", 100, 1, 20, 0.5)).isEqualTo(110);
        assertThat(DocumentMailRules.retryDelaySeconds("FIXED", 100, 1, 20, 0.999)).isEqualTo(119);
        assertThat(DocumentMailRules.retryDelaySeconds("LINEAR", 100, 2, 0, 0.9)).isEqualTo(200);
        assertThat(DocumentMailRules.retryDelaySeconds("EXPONENTIAL", 100, 3, 100, 0.5))
                .isEqualTo(600);
    }

    @Test
    void SRS_17_1_onlyAUsableAddressIsSentTo() {
        assertThat(DocumentMailRules.validAddress("rakesh.sharma@fci-demo.test")).isTrue();
        assertThat(DocumentMailRules.validAddress("o'brien+pay@mail.fci.gov.in")).isTrue();
        assertThat(DocumentMailRules.validAddress(null)).isFalse();
        assertThat(DocumentMailRules.validAddress("")).isFalse();
        assertThat(DocumentMailRules.validAddress("rakesh sharma@fci.in")).isFalse();
        assertThat(DocumentMailRules.validAddress("rakesh@localhost")).isFalse();
        assertThat(DocumentMailRules.validAddress("rakesh@@fci.in")).isFalse();
        assertThat(DocumentMailRules.validAddress("rakesh..s@fci.in")).isFalse();
        assertThat(DocumentMailRules.validAddress("rakesh@न.in")).as("R-ASCII").isFalse();
        assertThat(DocumentMailRules.validAddress("a".repeat(65) + "@fci.in")).isFalse();
        String longest =
                "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(60);
        assertThat(longest).hasSize(DocumentMailRules.ADDRESS_MAX - 1);
        assertThat(DocumentMailRules.validAddress(longest)).isTrue();
        String tooLong = longest + ".ef";
        assertThat(tooLong.length()).isGreaterThan(DocumentMailRules.ADDRESS_MAX);
        assertThat(DocumentMailRules.validAddress(tooLong)).isFalse();
    }

    private static final LocalDate BIRTH = LocalDate.of(1990, 1, 5);

    @Test
    void SRS_INT_16_theBackOffFollowsTheStrategyOfTheSettings() {
        assertThat(DocumentMailRules.backoffSeconds("FIXED", 300, 3)).isEqualTo(300);
        assertThat(DocumentMailRules.backoffSeconds("LINEAR", 300, 3)).isEqualTo(900);
        assertThat(DocumentMailRules.backoffSeconds("EXPONENTIAL", 300, 1)).isEqualTo(300);
        assertThat(DocumentMailRules.backoffSeconds("EXPONENTIAL", 300, 3)).isEqualTo(1200);
        assertThat(DocumentMailRules.backoffSeconds("EXPONENTIAL", 1, 100))
                .as("capped, never overflows")
                .isEqualTo(65_536);
        assertThat(DocumentMailRules.backoffSeconds("FIXED", 0, 1)).isZero();
        assertThatThrownBy(() -> DocumentMailRules.backoffSeconds("FIXED", 300, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentMailRules.backoffSeconds("FIXED", -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentMailRules.backoffSeconds("RANDOM", 300, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SRS_17_12_anEmailIsGivenUpAfterTheFirstAttemptAndTheConfiguredRetries() {
        assertThat(DocumentMailRules.givenUp(3, 3)).isFalse();
        assertThat(DocumentMailRules.givenUp(4, 3)).isTrue();
        assertThat(DocumentMailRules.givenUp(1, 0)).isTrue();
    }

    @Test
    void SRS_17_1_aPasswordRuleMustNameKnownTokensOnly() {
        assertThat(DocumentMailRules.validRule("{pan:4}{dob:ddMMyyyy}")).isTrue();
        assertThat(DocumentMailRules.validRule("FCI-{eis}-{dob:ddMM}")).isTrue();
        assertThat(DocumentMailRules.validRule("{pan}")).isTrue();
        assertThat(DocumentMailRules.validRule("{pan:10}")).isTrue();
        assertThat(DocumentMailRules.validRule("{dob:dd/MM/uuuu}")).isTrue();
        assertThat(DocumentMailRules.validRule(null)).isFalse();
        assertThat(DocumentMailRules.validRule("  ")).isFalse();
        assertThat(DocumentMailRules.validRule("plaintext"))
                .as("no token: a shared password")
                .isFalse();
        assertThat(DocumentMailRules.validRule("{aadhaar}")).isFalse();
        assertThat(DocumentMailRules.validRule("{pan:0}")).isFalse();
        assertThat(DocumentMailRules.validRule("{pan:11}")).isFalse();
        assertThat(DocumentMailRules.validRule("{pan:x}")).isFalse();
        assertThat(DocumentMailRules.validRule("{eis:3}")).isFalse();
        assertThat(DocumentMailRules.validRule("{dob}")).isFalse();
        assertThat(DocumentMailRules.validRule("{dob:ddd}")).as("not a valid pattern").isFalse();
        assertThat(DocumentMailRules.validRule("{dob:--}")).isFalse();
        assertThat(DocumentMailRules.validRule("{dob:HHmm}")).as("not a date").isFalse();
        assertThat(DocumentMailRules.validRule("{dob:dd'x'}")).isFalse();
        assertThat(DocumentMailRules.validRule("{eis}{")).isFalse();
        assertThat(DocumentMailRules.validRule("{eis}}")).isFalse();
        assertThat(DocumentMailRules.validRule("{eis}न")).as("R-ASCII").isFalse();
    }

    @Test
    void SRS_17_1_thePasswordIsBuiltFromTheMembersRecordByTheRule() {
        assertThat(DocumentMailRules.password("{pan:4}{dob:ddMMyyyy}", "abcde1234f", BIRTH, "1"))
                .contains("ABCD05011990");
        assertThat(DocumentMailRules.password("FCI-{eis}", null, null, " 104882 "))
                .contains("FCI-104882");
        assertThat(DocumentMailRules.password("{pan}", "ABCDE1234F", null, null))
                .contains("ABCDE1234F");
        assertThat(DocumentMailRules.password("{eis}$1", null, null, "7"))
                .as("a replacement character is literal")
                .contains("7$1");
    }

    @Test
    void SRS_17_1_noPasswordWhenTheRecordLacksAValueOrTheRuleIsUnusable() {
        assertThat(DocumentMailRules.password("{pan:4}", null, BIRTH, "1")).isEmpty();
        assertThat(DocumentMailRules.password("{pan:4}", "ABC", BIRTH, "1")).isEmpty();
        assertThat(DocumentMailRules.password("{pan}", "  ", BIRTH, "1")).isEmpty();
        assertThat(DocumentMailRules.password("{dob:ddMM}", "ABCDE1234F", null, "1")).isEmpty();
        assertThat(DocumentMailRules.password("{eis}", "ABCDE1234F", BIRTH, " ")).isEmpty();
        assertThat(DocumentMailRules.password("{eis}", "ABCDE1234F", BIRTH, null)).isEmpty();
        assertThat(DocumentMailRules.password("", "ABCDE1234F", BIRTH, "1")).isEmpty();
    }

    @Test
    void R_PII_aStoredFailureIsMaskedAsciiAndCut() {
        assertThat(DocumentMailRules.detail("Bounced: ", new IllegalStateException()))
                .isEqualTo("Bounced: IllegalStateException");
        assertThat(DocumentMailRules.detail("x ", new IllegalStateException(" ")))
                .isEqualTo("x IllegalStateException");
        String detail =
                DocumentMailRules.detail(
                        "", new IllegalStateException("550 emp.rsharma@fci-demo.test unknown न"));
        assertThat(detail).doesNotContain("emp.rsharma@fci-demo.test").matches("[\\x20-\\x7E]*");
        assertThat(DocumentMailRules.detail("", new IllegalStateException("y".repeat(2000))))
                .hasSize(DocumentMailRules.DETAIL_MAX);
    }
}
