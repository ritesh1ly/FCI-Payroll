package in.gov.fci.payroll.workflow.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * W-01 inbox task (SRS-22-16, BR-APPR-004): a breach is flagged once, only on a pending task past
 * its due time; a task moved to the next level escalates, counting a row recorded without a level
 * as level 0.
 */
class WorkflowInboxItemTest {

    private static final Instant DUE = Instant.parse("2026-10-29T06:00:00Z");

    @Test
    void SRS_22_16_aBreachIsFlaggedOnceOnlyOnAPendingTaskPastItsDueTime() {
        WorkflowInboxItem noTarget = WorkflowInboxItem.open(1L, "PAYROLL_CHECKER", 40L, null);
        assertThat(noTarget.flagIfBreached(DUE.plusSeconds(1))).isFalse();
        WorkflowInboxItem task = WorkflowInboxItem.open(2L, "PAYROLL_CHECKER", 40L, DUE);
        assertThat(task.flagIfBreached(DUE)).as("not yet past due").isFalse();
        assertThat(task.flagIfBreached(DUE.plusSeconds(1))).isTrue();
        assertThat(task.flagIfBreached(DUE.plusSeconds(2))).as("flagged once").isFalse();
        WorkflowInboxItem closed = WorkflowInboxItem.open(3L, "PAYROLL_CHECKER", 40L, DUE);
        closed.close(false);
        assertThat(closed.flagIfBreached(DUE.plusSeconds(1))).isFalse();
    }

    @Test
    void BR_APPR_004_aTaskWithoutARecordedLevelEscalatesFromZero() {
        WorkflowInboxItem legacy = WorkflowInboxItem.open(4L, "PAYROLL_CHECKER", 40L, DUE);
        ReflectionTestUtils.setField(legacy, "escalationLevel", null);
        assertThat(legacy.getEscalationLevel()).isZero();
        legacy.moveTo("DDO", 40L, DUE.plusSeconds(3600), DUE);
        assertThat(legacy.getEscalationLevel()).isEqualTo(1);
        legacy.moveTo("AGM", 40L, DUE.plusSeconds(7200), DUE);
        assertThat(legacy.getEscalationLevel()).isEqualTo(2);
    }
}
