package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.general.business.model.OffCyclePayment;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The I-20 screen status, derived because the DDL keeps none: Draft, Pending Checker, Approved,
 * Blocked, Returned, Rejected, Cancelled (SRS v0.5 I-20 "Status" field).
 */
class OffCycleStatusTest {

    private static OffCyclePayment c(String failure) {
        OffCyclePayment p =
                OffCyclePayment.capture(
                        1L, null, "OTHER", "202609", "202610", BigDecimal.TEN, "ref");
        p.duplicateCheck(failure, Instant.EPOCH);
        return p;
    }

    @Test
    void srsG10_theApprovalItemDrivesTheStatusOfAPassingCase() {
        assertThat(OffCycleService.derived(c(null), null)).isEqualTo("DRAFT");
        assertThat(OffCycleService.derived(c(null), ApprovalStatus.WITHDRAWN)).isEqualTo("DRAFT");
        assertThat(OffCycleService.derived(c(null), ApprovalStatus.PENDING_APPROVAL))
                .isEqualTo("PENDING_CHECKER");
        assertThat(OffCycleService.derived(c(null), ApprovalStatus.PENDING_ATTACHMENT))
                .isEqualTo("PENDING_CHECKER");
        assertThat(OffCycleService.derived(c(null), ApprovalStatus.APPROVED)).isEqualTo("APPROVED");
        assertThat(OffCycleService.derived(c(null), ApprovalStatus.RETURNED)).isEqualTo("RETURNED");
        assertThat(OffCycleService.derived(c(null), ApprovalStatus.REJECTED)).isEqualTo("REJECTED");
    }

    @Test
    void srsG10_aFailingDuplicateCheckShowsBlockedWhateverTheApproval() {
        assertThat(OffCycleService.derived(c("paid"), null)).isEqualTo("BLOCKED");
        assertThat(OffCycleService.derived(c("paid"), ApprovalStatus.RETURNED))
                .isEqualTo("BLOCKED");
    }

    @Test
    void srsG37_cancelledOutranksEverything() {
        OffCyclePayment p = c("paid");
        p.cancel("withdrawn");
        assertThat(OffCycleService.derived(p, ApprovalStatus.REJECTED)).isEqualTo("CANCELLED");
    }
}
