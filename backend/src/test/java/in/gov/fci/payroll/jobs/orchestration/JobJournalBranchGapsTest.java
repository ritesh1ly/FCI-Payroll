package in.gov.fci.payroll.jobs.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.jobs.business.model.JobStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * C-13 job journal (SRS-15-25): {@code batch_job_audit.detail} holds 500 characters and {@code
 * actor} 150; a longer value is cut to the column, one that fits (to the last character) or none at
 * all is kept as it is.
 */
class JobJournalBranchGapsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JobJournal journal =
            new JobJournal(
                    jdbc, Clock.fixed(Instant.parse("2026-10-22T06:00:00Z"), ZoneOffset.UTC));

    private Object[] recorded(String actor, String detail) {
        org.mockito.Mockito.clearInvocations(jdbc);
        journal.record(
                7L,
                JobJournal.Event.STARTED,
                actor,
                JobStatus.PICKED,
                JobStatus.RUNNING,
                40L,
                detail);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        return args.getValue();
    }

    @Test
    void SRS_15_25_theDetailAndTheActorAreCutToTheirColumns() {
        Object[] cut = recorded("a".repeat(151), "d".repeat(501));
        assertThat((String) cut[2]).hasSize(150);
        assertThat((String) cut[7]).hasSize(500);

        Object[] fits = recorded("a".repeat(150), "d".repeat(500));
        assertThat((String) fits[2]).hasSize(150);
        assertThat((String) fits[7]).hasSize(500);

        Object[] none = recorded("node-1", null);
        assertThat(none[2]).isEqualTo("node-1");
        assertThat(none[7]).isNull();
    }
}
