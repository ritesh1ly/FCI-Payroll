package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Boundary of the truncation limit of {@link LogSanitizer}. */
class LogSanitizerMutationGapsTest {

    @Test
    void aZeroLimitMeansNoTruncation() {
        assertThat(LogSanitizer.truncate("abcdef", 0)).isEqualTo("abcdef");
    }

    @Test
    void aNegativeLimitMeansNoTruncation() {
        assertThat(LogSanitizer.truncate("abcdef", -1)).isEqualTo("abcdef");
    }

    @Test
    void aValueExactlyAtTheLimitIsKeptAndOneOverIsCut() {
        assertThat(LogSanitizer.truncate("abcdef", 6)).isEqualTo("abcdef");
        assertThat(LogSanitizer.truncate("abcdefg", 6)).isEqualTo("abcdef…(7)");
    }
}
