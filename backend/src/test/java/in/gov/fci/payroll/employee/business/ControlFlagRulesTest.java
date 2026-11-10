package in.gov.fci.payroll.employee.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * SRS-1-7, SRS-1-11, FRS §7.8.1 Scenario 4 (US-GAP-4): a Hold / Stop-Payment change names a known
 * flag, Y or N different from the current value, a date not later than today (CLR-17) and not
 * before joining, and a reason of at most 500 characters.
 */
class ControlFlagRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);
    private static final LocalDate JOINED = LocalDate.of(2020, 1, 1);

    private static String defect(
            String flag, String value, String current, LocalDate from, String reason) {
        return ControlFlagRules.defect(flag, value, current, from, JOINED, TODAY, reason)
                .orElse("OK");
    }

    @Test
    void SRS_1_7_aValidSetAndClearPass() {
        assertThat(defect("HOLD", "Y", "N", TODAY, "order")).isEqualTo("OK");
        assertThat(defect("STOP_PAYMENT", "N", "Y", JOINED, "order")).isEqualTo("OK");
        assertThat(ControlFlagRules.defect("HOLD", "Y", "N", TODAY, null, TODAY, "r")).isEmpty();
    }

    @Test
    void SRS_1_7_eachDefectIsNamed() {
        assertThat(defect(null, "Y", "N", TODAY, "r")).isEqualTo("UNKNOWN_FLAG");
        assertThat(defect("ABSCONDING", "Y", "N", TODAY, "r")).isEqualTo("UNKNOWN_FLAG");
        assertThat(defect("HOLD", "X", "N", TODAY, "r")).isEqualTo("VALUE");
        assertThat(defect("HOLD", null, "N", TODAY, "r")).isEqualTo("VALUE");
        assertThat(defect("HOLD", "Y", "N", null, "r")).isEqualTo("NO_DATE");
        assertThat(defect("HOLD", "Y", "N", TODAY.plusDays(1), "r")).isEqualTo("FUTURE_DATE");
        assertThat(defect("HOLD", "Y", "N", JOINED.minusDays(1), "r")).isEqualTo("BEFORE_JOINING");
        assertThat(defect("HOLD", "Y", "N", TODAY, null)).isEqualTo("NO_REASON");
        assertThat(defect("HOLD", "Y", "N", TODAY, "   ")).isEqualTo("NO_REASON");
        assertThat(defect("HOLD", "Y", "N", TODAY, "x".repeat(501))).isEqualTo("REASON_TOO_LONG");
        assertThat(defect("HOLD", "Y", "N", TODAY, "x".repeat(500))).isEqualTo("OK");
        assertThat(defect("HOLD", "Y", "Y", TODAY, "r")).isEqualTo("NO_CHANGE");
    }
}
