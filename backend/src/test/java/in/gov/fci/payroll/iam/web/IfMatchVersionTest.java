package in.gov.fci.payroll.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.error.FciException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SRS-15-29: the If-Match version of an A-01 contact save. */
class IfMatchVersionTest {

    @ParameterizedTest
    @ValueSource(strings = {"7", "\"7\"", "W/\"7\"", " 7 ", "W/7"})
    void SRS_15_29_aVersionIsReadQuotedWeakOrBare(String header) {
        assertThat(AccessAdminController.version(header)).isEqualTo(7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\"\"", "abc", "\"7", "-1", "1234567890123456789", "१"})
    void SRS_15_29_anythingElseIsRefused(String header) {
        assertThatThrownBy(() -> AccessAdminController.version(header))
                .isInstanceOf(FciException.class);
    }
}
