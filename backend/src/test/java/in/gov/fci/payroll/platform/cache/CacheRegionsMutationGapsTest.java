package in.gov.fci.payroll.platform.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import in.gov.fci.payroll.platform.logging.LogCapture;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** HLD §3.12 master-data cache regions: gaps found by mutation testing. */
class CacheRegionsMutationGapsTest {

    private static final CacheProperties PROPERTIES =
            new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15));

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MovingClock clock = new MovingClock();
    private final CacheRegions regions = new CacheRegions(jdbc, PROPERTIES, clock);

    /** A clock the test moves by hand. */
    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2026-11-05T04:30:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void rowsAre(Map<String, Long> rows) {
        doAnswer(
                        call -> {
                            RowCallbackHandler handler = call.getArgument(1);
                            for (Map.Entry<String, Long> row : rows.entrySet()) {
                                ResultSet rs = mock(ResultSet.class);
                                when(rs.getString(1)).thenReturn(row.getKey());
                                when(rs.getLong(2)).thenReturn(row.getValue());
                                handler.processRow(rs);
                            }
                            return null;
                        })
                .when(jdbc)
                .query(anyString(), any(RowCallbackHandler.class));
    }

    @Test
    void thePropertiesAreTheOnesTheRegionsWereBuiltWith() {
        assertThat(regions.properties()).isSameAs(PROPERTIES);
    }

    @Test
    void aChangeCommittedInATransactionMakesTheNextReadAskTheDatabaseAtOnce() {
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
        rowsAre(Map.of("RATES", 1L));
        assertThat(regions.version("RATES")).isEqualTo(1L);

        TransactionSynchronizationManager.initSynchronization();
        regions.changed("RATES", "maker");
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        // before the commit the interval still holds
        rowsAre(Map.of("RATES", 2L));
        assertThat(regions.version("RATES")).isEqualTo(1L);
        for (TransactionSynchronization s : synchronizations) {
            s.afterCommit();
            s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        TransactionSynchronizationManager.clearSynchronization();

        // no time has passed: only the commit hook can have reopened the check
        assertThat(regions.version("RATES")).isEqualTo(2L);
        verify(jdbc, times(2)).query(anyString(), any(RowCallbackHandler.class));
    }

    @Test
    void aChangeOutsideATransactionMakesTheNextReadAskTheDatabaseAtOnce() {
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
        rowsAre(Map.of("RATES", 1L));
        assertThat(regions.version("RATES")).isEqualTo(1L);
        rowsAre(Map.of("RATES", 2L));
        regions.changed("RATES", "maker");
        assertThat(regions.version("RATES")).isEqualTo(2L);
    }

    @Test
    void theRefreshLockIsReleasedSoAnotherThreadCanRefreshAfterTheInterval() throws Exception {
        rowsAre(Map.of("RATES", 1L));
        assertThat(regions.version("RATES")).isEqualTo(1L);
        clock.advance(Duration.ofSeconds(11));
        rowsAre(Map.of("RATES", 5L));
        AtomicLong seen = new AtomicLong(-1);
        Thread other = new Thread(() -> seen.set(regions.version("RATES")));
        other.start();
        other.join(10_000);
        assertThat(seen).hasValue(5L);
    }

    @Test
    void aVersionMovedByAnotherInstanceIsLoggedOnceAndAnUnchangedOneIsNot() {
        rowsAre(Map.of("A", 1L, "B", 7L));
        try (LogCapture logs = LogCapture.of(CacheRegions.class, Level.INFO)) {
            // first read: nothing to compare with
            regions.version("A");
            assertThat(logs.messages()).isEmpty();

            clock.advance(Duration.ofSeconds(11));
            rowsAre(Map.of("A", 2L, "B", 7L, "C", 1L));
            regions.version("A");
            assertThat(logs.messages())
                    .containsExactly("event=CACHE_REGION_CHANGED region=A from=1 to=2");
        }
    }
}
