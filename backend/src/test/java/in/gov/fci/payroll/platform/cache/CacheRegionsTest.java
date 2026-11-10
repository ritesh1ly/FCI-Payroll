package in.gov.fci.payroll.platform.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The regions a transaction changed are known to that transaction only, until it completes (commit
 * or rollback): its reads of them bypass the caches, so its uncommitted data is never cached.
 */
class CacheRegionsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CacheRegions regions =
            new CacheRegions(
                    jdbc,
                    new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)),
                    Clock.systemUTC());

    CacheRegionsTest() {
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    }

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void outsideATransactionNothingIsPending() {
        regions.changed("R", "tester");
        assertThat(regions.changedInCurrentTransaction("R")).isFalse();
    }

    @Test
    void aChangedRegionIsPendingUntilTheTransactionCompletesEitherWay() {
        for (int status :
                new int[] {
                    TransactionSynchronization.STATUS_COMMITTED,
                    TransactionSynchronization.STATUS_ROLLED_BACK
                }) {
            TransactionSynchronizationManager.initSynchronization();
            assertThat(regions.changedInCurrentTransaction("R")).isFalse();
            regions.changed("R", "tester");
            regions.changed("S", "tester");
            assertThat(regions.changedInCurrentTransaction("R")).isTrue();
            assertThat(regions.changedInCurrentTransaction("S")).isTrue();
            assertThat(regions.changedInCurrentTransaction("T")).isFalse();
            for (TransactionSynchronization s :
                    TransactionSynchronizationManager.getSynchronizations()) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    s.afterCommit();
                }
                s.afterCompletion(status);
            }
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.initSynchronization();
            assertThat(regions.changedInCurrentTransaction("R")).isFalse();
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

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

    private void rowsAre(JdbcTemplate template, Map<String, Long> rows) throws Exception {
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
                .when(template)
                .query(anyString(), any(RowCallbackHandler.class));
    }

    @Test
    void whenTheVersionsCannotBeReadTheLastKnownStandAndTheNextCheckIsOneIntervalAway()
            throws Exception {
        JdbcTemplate template = mock(JdbcTemplate.class);
        MovingClock clock = new MovingClock();
        CacheRegions cache =
                new CacheRegions(
                        template,
                        new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)),
                        clock);
        rowsAre(template, Map.of("RATES", 3L));
        assertThat(cache.version("RATES")).isEqualTo(3L);

        clock.advance(Duration.ofSeconds(11));
        doThrow(new DataAccessResourceFailureException("database down"))
                .when(template)
                .query(anyString(), any(RowCallbackHandler.class));
        assertThat(cache.version("RATES")).as("last known versions are kept").isEqualTo(3L);
        assertThat(cache.version("NEVER_CHANGED")).isZero();

        clock.advance(Duration.ofSeconds(9));
        assertThat(cache.version("RATES")).isEqualTo(3L);
        verify(template, times(2)).query(anyString(), any(RowCallbackHandler.class));

        // after the interval the database is asked again and the new version is taken
        clock.advance(Duration.ofSeconds(2));
        rowsAre(template, Map.of("RATES", 4L));
        assertThat(cache.version("RATES")).isEqualTo(4L);
        verify(template, times(3)).query(anyString(), any(RowCallbackHandler.class));
    }

    @Test
    void aFirstReadThatFailsAnswersZeroForEveryRegionAndDoesNotThrow() {
        JdbcTemplate template = mock(JdbcTemplate.class);
        doThrow(new DataAccessResourceFailureException("database down"))
                .when(template)
                .query(anyString(), any(RowCallbackHandler.class));
        CacheRegions cache =
                new CacheRegions(
                        template,
                        new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)),
                        new MovingClock());
        assertThat(cache.version("RATES")).isZero();
        assertThat(cache.version("")).isZero();
        verify(template, times(1)).query(anyString(), any(RowCallbackHandler.class));
    }

    @Test
    void aChangeOfAKnownVersionIsPickedUpAndAnUnknownRegionStaysAtZero() throws Exception {
        JdbcTemplate template = mock(JdbcTemplate.class);
        MovingClock clock = new MovingClock();
        CacheRegions cache =
                new CacheRegions(
                        template,
                        new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)),
                        clock);
        rowsAre(template, Map.of("A", 1L, "B", 7L));
        assertThat(cache.version("A")).isEqualTo(1L);
        assertThat(cache.version("B")).isEqualTo(7L);
        assertThat(cache.version("C")).isZero();
        clock.advance(Duration.ofSeconds(10));
        rowsAre(template, Map.of("A", 2L, "B", 7L));
        assertThat(cache.version("A")).isEqualTo(2L);
        assertThat(cache.version("B")).isEqualTo(7L);
    }

    @Test
    void aNewRegionIsInsertedWhenNoRowWasUpdated() {
        JdbcTemplate template = mock(JdbcTemplate.class);
        when(template.update(anyString(), any(), any(), any())).thenReturn(0);
        CacheRegions cache =
                new CacheRegions(
                        template,
                        new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)),
                        new MovingClock());
        cache.changed("NEW_REGION", "tester");
        verify(template, times(2)).update(anyString(), any(), any(), any());
    }
}
