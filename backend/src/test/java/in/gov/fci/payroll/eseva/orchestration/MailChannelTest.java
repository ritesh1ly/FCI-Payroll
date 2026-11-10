package in.gov.fci.payroll.eseva.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The member e-mail channel's shared capacity (OD-148; SRS-INT-16; PENDING MAIL-4): windows of a
 * minute, an hour and an IST day; a token in every configured window or none (rolled back); a new
 * window resets its count; a full window defers; connection slots leased up to the maximum, a slot
 * or window created at the same moment by another instance reused; a pause recorded once and moved
 * after.
 */
class MailChannelTest {

    private static final Instant NOW = Instant.parse("2026-11-09T05:17:42Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final MailChannel channel;

    MailChannelTest() {
        when(tx.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        channel = new MailChannel(jdbc, tx);
    }

    private static MailChannel.Config config(Integer minute, Integer hour, Integer day) {
        return new MailChannel.Config(minute, hour, day, 2, 300, 100, "WITHOUT_ATTACHMENT", 0, 900);
    }

    @Test
    void SRS_INT_16_theWindowsStartAtTheMinuteTheHourAndTheIstDay() {
        assertThat(MailChannel.windowStart(MailChannel.Window.MINUTE, NOW))
                .isEqualTo(Instant.parse("2026-11-09T05:17:00Z"));
        assertThat(MailChannel.windowStart(MailChannel.Window.HOUR, NOW))
                .isEqualTo(Instant.parse("2026-11-09T05:00:00Z"));
        assertThat(MailChannel.windowStart(MailChannel.Window.DAY, NOW))
                .as("midnight IST")
                .isEqualTo(Instant.parse("2026-11-08T18:30:00Z"));
        MailChannel.Config c = config(1, 2, 3);
        assertThat(c.limit(MailChannel.Window.MINUTE)).isEqualTo(1);
        assertThat(c.limit(MailChannel.Window.HOUR)).isEqualTo(2);
        assertThat(c.limit(MailChannel.Window.DAY)).isEqualTo(3);
        assertThat(c.withoutAttachmentWhenTooLarge()).isTrue();
        assertThat(
                        new MailChannel.Config(1, 1, 1, 1, 1, 1, "FAIL", 0, 1)
                                .withoutAttachmentWhenTooLarge())
                .isFalse();
    }

    @Test
    void SRS_INT_16_aTokenIsTakenInEveryWindowOrNone() {
        // minute: a new window (reset); hour: the increment succeeds; day: not limited
        when(jdbc.update(contains("SET window_start = ?, used = 1"), any(Object[].class)))
                .thenReturn(1)
                .thenReturn(0);
        when(jdbc.update(contains("SET used = used + 1"), any(Object[].class))).thenReturn(1);
        assertThat(channel.takeToken(config(10, 100, null), NOW)).isTrue();
        verify(jdbc, times(2))
                .update(contains("INSERT INTO mail_rate_window"), any(Object[].class));
        // a full window: nothing taken, the transaction rolled back
        when(jdbc.update(contains("SET window_start = ?, used = 1"), any(Object[].class)))
                .thenReturn(0);
        when(jdbc.update(contains("SET used = used + 1"), any(Object[].class))).thenReturn(0);
        assertThat(channel.takeToken(config(10, null, null), NOW)).isFalse();
        org.mockito.ArgumentCaptor<org.springframework.transaction.TransactionStatus> done =
                org.mockito.ArgumentCaptor.forClass(
                        org.springframework.transaction.TransactionStatus.class);
        verify(tx, times(2)).commit(done.capture());
        assertThat(done.getAllValues().get(0).isRollbackOnly()).isFalse();
        assertThat(done.getAllValues().get(1).isRollbackOnly())
                .as("nothing taken: rolled back")
                .isTrue();
        assertThat(channel.takeToken(config(null, null, null), NOW)).as("no cap").isTrue();
    }

    @Test
    void SRS_INT_16_aWindowCreatedByAnotherInstanceIsReused() {
        when(jdbc.update(contains("INSERT INTO mail_rate_window"), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("pk"));
        when(jdbc.update(contains("SET window_start = ?, used = 1"), any(Object[].class)))
                .thenReturn(1);
        assertThat(channel.takeToken(config(5, null, null), NOW)).isTrue();
    }

    @Test
    void SRS_INT_16_capacityAndUsageAreReadPerConfiguredWindow() {
        when(jdbc.queryForList(
                        contains("SELECT used FROM mail_rate_window"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of(10L))
                .thenReturn(List.of(3L))
                .thenReturn(List.of())
                .thenReturn(List.of(4L))
                .thenReturn(List.of());
        assertThat(channel.hasCapacity(config(10, null, null), NOW)).as("10 of 10").isFalse();
        assertThat(channel.hasCapacity(config(10, 20, null), NOW)).isTrue();
        assertThat(channel.usage(config(10, null, 50), NOW))
                .containsEntry("MINUTE", 4L)
                .containsEntry("DAY", 0L)
                .hasSize(2);
        assertThat(channel.hasCapacity(config(null, null, null), NOW)).isTrue();
    }

    @Test
    void SRS_INT_16_slotsAreLeasedUpToTheMaximumAndReleased() {
        when(jdbc.update(contains("INSERT INTO mail_send_slot"), any(Object[].class)))
                .thenReturn(1)
                .thenThrow(new DuplicateKeyException("pk"));
        when(jdbc.update(contains("UPDATE mail_send_slot SET holder = ?"), any(Object[].class)))
                .thenReturn(1)
                .thenReturn(0);
        assertThat(channel.acquireSlots("me", 5, config(null, null, null), NOW)).containsExactly(1);
        channel.releaseSlots("me");
        verify(jdbc).update(contains("SET holder = NULL"), eq("MEMBER_EMAIL"), eq("me"));
        assertThat(channel.acquireSlots("me", 0, config(null, null, null), NOW)).isEmpty();
    }

    @Test
    void SRS_INT_6_aPauseIsRecordedOnceThenMoved() {
        when(jdbc.update(contains("UPDATE mail_channel_state"), any(Object[].class)))
                .thenReturn(0)
                .thenReturn(1);
        channel.pause(NOW.plusSeconds(900), "Credentials rejected (535)", NOW);
        channel.pause(NOW.plusSeconds(1800), "Credentials rejected (535)", NOW);
        verify(jdbc, times(1))
                .update(contains("INSERT INTO mail_channel_state"), any(Object[].class));
        when(jdbc.queryForList(
                        contains("FROM mail_channel_state"),
                        eq(Timestamp.class),
                        any(Object[].class)))
                .thenReturn(List.of(Timestamp.from(NOW.plusSeconds(60))));
        assertThat(channel.pausedUntil(NOW)).contains(NOW.plusSeconds(60));
    }
}
