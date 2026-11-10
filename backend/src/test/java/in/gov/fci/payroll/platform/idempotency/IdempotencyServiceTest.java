package in.gov.fci.payroll.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.idempotency.IdempotencyService.Decision;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * ADR-0011 Idempotency-Key: a first request proceeds, a repeat of a completed one is replayed, a
 * repeat while the first still runs (or loses the insert race to it) waits, and a key reused for
 * another request is refused.
 */
class IdempotencyServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T05:00:00Z");

    private final IdempotencyRepository repository = mock(IdempotencyRepository.class);
    private final ApiProperties properties = mock(ApiProperties.class);
    private final IdempotencyService service =
            new IdempotencyService(repository, Clock.fixed(NOW, ZoneOffset.UTC), properties);

    IdempotencyServiceTest() {
        when(properties.idempotency())
                .thenReturn(new ApiProperties.Idempotency(Duration.ofHours(24)));
    }

    private static String id(String user, String key) {
        return in.gov.fci.payroll.platform.security.SecureTokens.sha256Hex(user + ":" + key);
    }

    @Test
    void aSecondRequestThatLosesTheInsertRaceWaitsForTheFirst() {
        when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("pk_idempotency"));
        assertThat(service.begin("7", "K-1", "h1")).isInstanceOf(Decision.InProgress.class);
    }

    @Test
    void theFirstRequestProceedsUnderAKeyOfTheUserAndTheKey() {
        when(repository.findById(any())).thenReturn(Optional.empty());
        assertThat(service.begin("7", "K-1", "h1")).isEqualTo(new Decision.Proceed(id("7", "K-1")));
    }

    @Test
    void aRepeatIsReplayedWaitsOrIsRefusedByWhatTheFirstDid() {
        IdempotencyRecord running =
                IdempotencyRecord.start(id("7", "K-1"), "7", "h1", NOW, NOW.plusSeconds(60));
        when(repository.findById(id("7", "K-1"))).thenReturn(Optional.of(running));
        assertThat(service.begin("7", "K-1", "h1")).isInstanceOf(Decision.InProgress.class);
        assertThat(service.begin("7", "K-1", "other")).isInstanceOf(Decision.Mismatch.class);
        running.complete(201, "application/json", "{\"id\":5}");
        assertThat(service.begin("7", "K-1", "h1"))
                .isEqualTo(new Decision.Replay(201, "application/json", "{\"id\":5}"));
    }
}
