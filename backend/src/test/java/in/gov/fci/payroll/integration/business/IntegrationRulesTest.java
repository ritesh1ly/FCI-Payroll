package in.gov.fci.payroll.integration.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The integration layer's rules (plan 23; SRS-INT-6, SRS-INT-12, SRS-INT-31, SRS-INT-32). */
class IntegrationRulesTest {

    @Test
    void SRS_INT_32_aWindowThatDoesNotBalanceIsFailed() {
        assertThat(IntegrationRules.windowStatus(10, 10, 0)).isEqualTo("OK");
        assertThat(IntegrationRules.windowStatus(0, 0, 0)).as("an empty window").isEqualTo("OK");
        assertThat(IntegrationRules.windowStatus(10, 8, 2)).isEqualTo("PARTIAL");
        assertThat(IntegrationRules.windowStatus(10, 0, 10)).isEqualTo("PARTIAL");
        assertThat(IntegrationRules.windowStatus(10, 7, 2))
                .as("one record lost")
                .isEqualTo("FAILED");
        assertThat(IntegrationRules.windowStatus(10, 9, 2)).as("one too many").isEqualTo("FAILED");
        assertThat(IntegrationRules.windowStatus(-1, -1, 0)).isEqualTo("FAILED");
        assertThat(IntegrationRules.windowStatus(1, 2, -1)).isEqualTo("FAILED");
        assertThat(IntegrationRules.windowStatus(1, -1, 2)).isEqualTo("FAILED");
    }

    @Test
    void SRS_INT_6_theBackOffFollowsItsStrategy() {
        assertThat(IntegrationRules.backoffSeconds("FIXED", 30, 1)).isEqualTo(30);
        assertThat(IntegrationRules.backoffSeconds("FIXED", 30, 4)).isEqualTo(30);
        assertThat(IntegrationRules.backoffSeconds("LINEAR", 30, 1)).isEqualTo(30);
        assertThat(IntegrationRules.backoffSeconds("LINEAR", 30, 3)).isEqualTo(90);
        assertThat(IntegrationRules.backoffSeconds("EXPONENTIAL", 30, 1)).isEqualTo(30);
        assertThat(IntegrationRules.backoffSeconds("EXPONENTIAL", 30, 3)).isEqualTo(120);
        assertThat(IntegrationRules.backoffSeconds("EXPONENTIAL", 1, 21)).isEqualTo(1L << 20);
        assertThat(IntegrationRules.backoffSeconds("EXPONENTIAL", 1, 999))
                .as("capped, never overflowing")
                .isEqualTo(1L << 20);
        assertThat(IntegrationRules.backoffSeconds("FIXED", 0, 1)).isZero();
        assertThatThrownBy(() -> IntegrationRules.backoffSeconds("FIXED", 30, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IntegrationRules.backoffSeconds("FIXED", -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IntegrationRules.backoffSeconds("RANDOM", 30, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("back-off strategy RANDOM");
    }

    @Test
    void SRS_INT_6_aRequiredBreakerOpensAtItsThreshold() {
        assertThat(IntegrationRules.opens(true, 3, 3)).isTrue();
        assertThat(IntegrationRules.opens(true, 3, 4)).isTrue();
        assertThat(IntegrationRules.opens(true, 3, 2)).isFalse();
        assertThat(IntegrationRules.opens(false, 3, 9)).as("no breaker required").isFalse();
        assertThat(IntegrationRules.opens(true, null, 9)).as("no threshold set").isFalse();
    }

    @Test
    void SRS_INT_31_theAdministratorsReasonIsMandatory() {
        assertThat(IntegrationRules.reasonDefect("Corrected at source")).isEmpty();
        assertThat(IntegrationRules.reasonDefect("r".repeat(1000))).isEmpty();
        assertThat(IntegrationRules.reasonDefect("  padded  ")).isEmpty();
        assertThat(IntegrationRules.reasonDefect(null)).contains("REASON");
        assertThat(IntegrationRules.reasonDefect(" ")).contains("REASON");
        assertThat(IntegrationRules.reasonDefect("r".repeat(1001))).contains("REASON");
        assertThat(IntegrationRules.reasonDefect("कारण")).as("US7ASCII").contains("REASON");
    }

    @Test
    void SRS_INT_12_everyInboundRecordCarriesItsSourceKey() {
        assertThat(IntegrationRules.recordDefect("EMP_OTA", "REQ-1", "{}")).isEmpty();
        assertThat(IntegrationRules.recordDefect("E".repeat(40), "k".repeat(100), "x")).isEmpty();
        assertThat(IntegrationRules.recordDefect(null, "REQ-1", "{}")).contains("TXN_TYPE");
        assertThat(IntegrationRules.recordDefect(" ", "REQ-1", "{}")).contains("TXN_TYPE");
        assertThat(IntegrationRules.recordDefect("E".repeat(41), "REQ-1", "{}"))
                .contains("TXN_TYPE");
        assertThat(IntegrationRules.recordDefect("EMP_OTA", null, "{}")).contains("SOURCE_KEY");
        assertThat(IntegrationRules.recordDefect("EMP_OTA", " ", "{}")).contains("SOURCE_KEY");
        assertThat(IntegrationRules.recordDefect("EMP_OTA", "k".repeat(101), "{}"))
                .contains("SOURCE_KEY");
        assertThat(IntegrationRules.recordDefect("EMP_OTA", "अनुरोध", "{}")).contains("SOURCE_KEY");
        assertThat(IntegrationRules.recordDefect("EMP_OTA", "REQ-1", null)).contains("PAYLOAD");
        assertThat(IntegrationRules.recordDefect("EMP_OTA", "REQ-1", "")).contains("PAYLOAD");
    }

    @Test
    void cutKeepsWithinTheColumn() {
        assertThat(IntegrationRules.cut(null, 5)).isNull();
        assertThat(IntegrationRules.cut("abc", 5)).isEqualTo("abc");
        assertThat(IntegrationRules.cut("abcde", 5)).isEqualTo("abcde");
        assertThat(IntegrationRules.cut("abcdef", 5)).isEqualTo("abcde");
    }

    /** SRS-INT-31 / R-ASCII: an X-03 reason from the reason dialog keeps its line breaks. */
    @Test
    void SRS_INT_31_aReasonOverSeveralLinesIsAccepted() {
        assertThat(IntegrationRules.reasonDefect("Corrected at source\r\nresent")).isEmpty();
        // trim() takes control characters off the ends, so the bell is inside the text
        assertThat(IntegrationRules.reasonDefect("Corr\u0007ected")).contains("REASON");
    }
}
