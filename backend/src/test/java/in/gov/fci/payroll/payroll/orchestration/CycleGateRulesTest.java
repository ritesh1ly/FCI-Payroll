package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * SRS-G-29 / SRS-15-16: the reason a Checker gives for accepting a gate or acknowledging a warn
 * check is required, printable ASCII (R-ASCII) and at most 1,000 characters; a check detail kept
 * for the panel is clipped to its column.
 */
class CycleGateRulesTest {

    private static ErrorCode code(Throwable t) {
        return ((FciException) t).getErrorCode();
    }

    @Test
    void SRS_G_29_aReasonIsRequiredAndKeptTrimmed() {
        assertThat(CycleGateService.reason("  Exceptions reviewed with the DDO  "))
                .isEqualTo("Exceptions reviewed with the DDO");
        assertThat(CycleGateService.reason("x".repeat(CycleGateService.MAX_REASON))).hasSize(1000);
        for (String missing : Arrays.asList(null, "", "   ", "\t\n")) {
            assertThatThrownBy(() -> CycleGateService.reason(missing))
                    .satisfies(e -> assertThat(code(e)).isEqualTo(ApiErrorCode.REASON_REQUIRED));
        }
    }

    @Test
    void R_ASCII_aReasonOverTheLimitOrOutsidePrintableAsciiIsRefused() {
        for (String bad :
                Arrays.asList(
                        "x".repeat(CycleGateService.MAX_REASON + 1),
                        "reviewed नमस्ते",
                        "bell\u0007")) {
            assertThatThrownBy(() -> CycleGateService.reason(bad))
                    .satisfies(
                            e -> assertThat(code(e)).isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        }
        // The reason is typed in the reason dialog, a text area: tab and line breaks are plain
        // text (R-ASCII as @PrintableAscii; UI audit 2026-11-02).
        assertThat(CycleGateService.reason("tab\there\r\nnext")).isEqualTo("tab\there\r\nnext");
    }

    @Test
    void SRS_15_16_aCheckDetailIsClippedToItsColumnAndNullStaysNull() {
        assertThat(CycleGateService.clip(null)).isNull();
        assertThat(CycleGateService.clip("")).isEmpty();
        assertThat(CycleGateService.clip("d".repeat(2000))).hasSize(2000);
        assertThat(CycleGateService.clip("d".repeat(2001))).hasSize(2000);
    }
}
