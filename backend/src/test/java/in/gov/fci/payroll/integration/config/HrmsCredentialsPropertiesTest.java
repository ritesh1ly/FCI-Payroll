package in.gov.fci.payroll.integration.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SRS-INT-4, R-INT, R-PII: the login Payroll presents to HRMS comes from the environment (user name
 * and password, the password also from a file), is absent when either part is missing, and is never
 * shown.
 */
class HrmsCredentialsPropertiesTest {

    @TempDir Path dir;

    @Test
    void SRS_INT_4_aLoginNeedsBothAUserNameAndAPassword() {
        assertThat(new HrmsCredentialsProperties("payroll", "pw", null, null).login())
                .contains(new HrmsCredentialsProperties.Login("payroll", "pw"));
        assertThat(new HrmsCredentialsProperties(null, "pw", null, null).login()).isEmpty();
        assertThat(new HrmsCredentialsProperties("  ", "pw", null, null).login()).isEmpty();
        assertThat(new HrmsCredentialsProperties("payroll", null, null, null).login()).isEmpty();
        assertThat(new HrmsCredentialsProperties("payroll", " ", "", null).login()).isEmpty();
    }

    @Test
    void SRS_INT_4_theUserNameIsTrimmedAndThePasswordMayComeFromAFile() throws Exception {
        Path file = Files.writeString(dir.resolve("pw.txt"), "  file-secret \n");
        assertThat(new HrmsCredentialsProperties(" payroll ", null, file.toString(), null).login())
                .contains(new HrmsCredentialsProperties.Login("payroll", "file-secret"));
        assertThat(
                        new HrmsCredentialsProperties("payroll", "direct", file.toString(), null)
                                .login()
                                .orElseThrow()
                                .password())
                .as("the setting wins over the file")
                .isEqualTo("direct");
        assertThat(
                        new HrmsCredentialsProperties(
                                        "payroll", null, dir.resolve("missing").toString(), null)
                                .login())
                .isEmpty();
        assertThat(
                        new HrmsCredentialsProperties(
                                        "payroll",
                                        null,
                                        Files.writeString(dir.resolve("empty"), " \n").toString(),
                                        null)
                                .login())
                .isEmpty();
    }

    @Test
    void R_INT_theSkewDefaultsTo60SecondsAndTakesAnyNonNegativeValue() {
        assertThat(new HrmsCredentialsProperties("u", "p", null, null).skew())
                .isEqualTo(Duration.ofSeconds(60));
        assertThat(new HrmsCredentialsProperties("u", "p", null, -1).skew())
                .isEqualTo(Duration.ofSeconds(60));
        assertThat(new HrmsCredentialsProperties("u", "p", null, 0).skew())
                .isEqualTo(Duration.ZERO);
        assertThat(new HrmsCredentialsProperties("u", "p", null, 90).skew())
                .isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void R_INT_tokenRenewalsDefaultToTwoAndAreKeptWithinZeroAndFive() {
        assertThat(new HrmsCredentialsProperties("u", "p", null, null).renewals())
                .as("4-argument constructor")
                .isEqualTo(2);
        assertThat(new HrmsCredentialsProperties("u", "p", null, null, null).renewals())
                .isEqualTo(2);
        assertThat(new HrmsCredentialsProperties("u", "p", null, null, -1).renewals()).isEqualTo(2);
        assertThat(new HrmsCredentialsProperties("u", "p", null, null, 0).renewals()).isZero();
        assertThat(new HrmsCredentialsProperties("u", "p", null, null, 1).renewals()).isEqualTo(1);
        assertThat(new HrmsCredentialsProperties("u", "p", null, null, 5).renewals()).isEqualTo(5);
        assertThat(new HrmsCredentialsProperties("u", "p", null, null, 6).renewals()).isEqualTo(5);
        assertThat(new HrmsCredentialsProperties("u", "p", null, null, 100).renewals())
                .isEqualTo(5);
    }

    @Test
    void R_PII_thePasswordIsNeverInToString() {
        String shown =
                new HrmsCredentialsProperties("payroll", "super-secret", "/etc/x", 5).toString();
        assertThat(shown)
                .contains("payroll")
                .doesNotContain("super-secret")
                .doesNotContain("/etc/x");
    }
}
