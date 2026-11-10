package in.gov.fci.payroll.platform.error;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.security.InputPatterns;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * SRS-20-19 / SRS-20-21: recognising a refusal by the immutability triggers anywhere in a cause
 * chain.
 */
class HistoryTamperAttemptTest {

    @Test
    void SRS_20_19_theTriggerCodeIsFoundAtAnyDepthOfTheCauseChain() {
        Exception deep =
                new RuntimeException(
                        "could not execute",
                        new IllegalStateException(
                                null, new SQLException("ORA-20110: FCIPAY-AUD-001: append-only")));
        assertThat(HistoryTamperAttempt.codeIn(deep)).isEqualTo("FCIPAY-AUD-001");
        assertThat(
                        HistoryTamperAttempt.codeIn(
                                new SQLException("FCIPAY-AUD-002: migrated legacy audit records")))
                .isEqualTo("FCIPAY-AUD-002");
    }

    @Test
    void anyOtherFailureIsNotATamperAttempt() {
        assertThat(HistoryTamperAttempt.codeIn(new SQLException("ORA-00001: unique"))).isNull();
        assertThat(HistoryTamperAttempt.codeIn(new RuntimeException((String) null))).isNull();
        assertThat(HistoryTamperAttempt.codeIn(null)).isNull();
    }

    @Test
    void R_ASCII_printableAsciiIsSpaceToTildeWithoutControlCharacters() {
        assertThat(InputPatterns.isPrintableAscii("Order 12/2026 (HQ) ~")).isTrue();
        assertThat(InputPatterns.isPrintableAscii("")).isTrue();
        assertThat(InputPatterns.isPrintableAscii("tab\t")).isFalse();
        assertThat(InputPatterns.isPrintableAscii("del\u007f")).isFalse();
        assertThat(InputPatterns.isPrintableAscii("आदेश")).isFalse();
        assertThat(InputPatterns.isPrintableAscii(null)).isFalse();
    }
}
