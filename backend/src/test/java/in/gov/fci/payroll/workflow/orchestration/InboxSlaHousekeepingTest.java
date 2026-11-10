package in.gov.fci.payroll.workflow.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * W-01 target turnaround (SRS-22-4): the batch housekeeping saves a pending inbox item only when it
 * is newly flagged as past its target turnaround and counts those items.
 */
class InboxSlaHousekeepingTest {

    private static final Instant NOW = Instant.parse("2026-10-28T06:00:00Z");

    @Test
    void SRS_22_4_onlyNewlyBreachedItemsAreFlaggedAndCounted() {
        WorkflowRepositories.Inbox inbox = mock(WorkflowRepositories.Inbox.class);
        WorkflowInboxItem late = mock(WorkflowInboxItem.class);
        when(late.flagIfBreached(NOW)).thenReturn(true);
        WorkflowInboxItem onTime = mock(WorkflowInboxItem.class);
        when(inbox.findByStatus(WorkflowInboxItem.Status.PENDING))
                .thenReturn(List.of(late, onTime));
        InboxSlaHousekeeping housekeeping = new InboxSlaHousekeeping(inbox);

        assertThat(housekeeping.name()).isEqualTo("slaBreaches");
        assertThat(housekeeping.run(NOW)).isEqualTo(1);
        verify(inbox).save(late);
        verify(inbox, never()).save(onTime);
    }
}
