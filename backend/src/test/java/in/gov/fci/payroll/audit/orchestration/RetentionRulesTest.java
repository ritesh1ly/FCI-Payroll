package in.gov.fci.payroll.audit.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import org.junit.jupiter.api.Test;

/** SRS-20-5: a retention period is a setting - months or indefinite, with its instrument. */
class RetentionRulesTest {

    private static RetentionService.ChangeRequest request(
            Integer months, boolean indefinite, String basis, String reason) {
        return new RetentionService.ChangeRequest(
                "NATIVE_AUDIT", months, indefinite, basis, reason);
    }

    private static void refused(RetentionService.ChangeRequest request) {
        assertThatThrownBy(() -> RetentionService.validate(request))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.RETENTION_INVALID);
                            assertThat(e.getMessageArgs()).containsExactly(99_999);
                        });
    }

    @Test
    void SRS_20_5_aPeriodIsANumberOfMonthsOrIndefiniteNotBothNotNeither() {
        assertThat(RetentionService.validate(request(120, false, " Order 12 ", "Policy")))
                .isEqualTo(new RetentionService.Proposal(120, false, "Order 12"));
        assertThat(RetentionService.validate(request(null, true, "Order", "Policy")))
                .isEqualTo(new RetentionService.Proposal(null, true, "Order"));
        refused(request(120, true, "Order", "Policy"));
        refused(request(null, false, "Order", "Policy"));
    }

    @Test
    void SRS_20_5_monthsRunFromOneToTheColumnLimit() {
        assertThat(RetentionService.validate(request(1, false, "Order", "Policy")).months())
                .isEqualTo(1);
        assertThat(RetentionService.validate(request(99_999, false, "Order", "Policy")).months())
                .isEqualTo(99_999);
        refused(request(0, false, "Order", "Policy"));
        refused(request(-1, false, "Order", "Policy"));
        refused(request(100_000, false, "Order", "Policy"));
    }

    @Test
    void SRS_20_5_theInstrumentIsPrintableAsciiOfAtMost200AndAReasonIsGiven() {
        refused(request(12, false, null, "Policy"));
        refused(request(12, false, "   ", "Policy"));
        refused(request(12, false, "x".repeat(201), "Policy"));
        refused(request(12, false, "आदेश", "Policy"));
        refused(request(12, false, "Line\nbreak", "Policy"));
        refused(request(12, false, "Order", null));
        refused(request(12, false, "Order", " "));
        assertThat(RetentionService.validate(request(12, false, "x".repeat(200), "Policy")))
                .isNotNull();
    }
}
