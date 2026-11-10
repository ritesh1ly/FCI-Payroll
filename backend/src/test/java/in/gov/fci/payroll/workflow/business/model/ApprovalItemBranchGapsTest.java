package in.gov.fci.payroll.workflow.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * R-MC branch gaps of an approval item: a decided item cannot move up a level, and an item waiting
 * for its attachment can be withdrawn while a decided one cannot (Wave D review 9).
 */
class ApprovalItemBranchGapsTest {

    private static final Instant NOW = Instant.parse("2026-10-23T05:00:00Z");

    private static ApprovalItem item(int levels) {
        return ApprovalItem.submit(
                "M-01",
                1L,
                levels,
                "employee",
                "106620",
                ActionType.UPDATE,
                "{}",
                null,
                "maker",
                "r",
                null,
                null,
                NOW);
    }

    @Test
    void R_MC_aDecidedItemHasNoFurtherLevel() {
        ApprovalItem decided = item(3);
        decided.decide(ApprovalStatus.REJECTED, "checker", "no", NOW);
        assertThatThrownBy(decided::advance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No further level");
        ApprovalItem pending = item(3);
        pending.advance();
        assertThat(pending.isAtFinalLevel()).isTrue();
    }

    @Test
    void R_MC_anItemWaitingForItsAttachmentIsWithdrawnADecidedOneIsNot() {
        ApprovalItem waiting = item(2);
        ReflectionTestUtils.setField(waiting, "status", ApprovalStatus.PENDING_ATTACHMENT);
        waiting.withdraw("owner", NOW);
        assertThat(waiting.getStatus()).isEqualTo(ApprovalStatus.WITHDRAWN);
        assertThat(waiting.getDecidedBy()).isEqualTo("owner");

        ApprovalItem pending = item(2);
        pending.withdraw("owner", NOW);
        assertThat(pending.getStatus()).isEqualTo(ApprovalStatus.WITHDRAWN);

        ApprovalItem approved = item(2);
        approved.decide(ApprovalStatus.APPROVED, "checker", null, NOW);
        assertThatThrownBy(() -> approved.withdraw("owner", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Not pending");
    }
}
