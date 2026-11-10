package in.gov.fci.payroll.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.config.ApiProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ADR-0011: completing and abandoning an idempotency record. */
class IdempotencyServiceMutationGapsTest {

    private static final Instant NOW = Instant.parse("2026-11-05T05:00:00Z");

    private final IdempotencyRepository repository = mock(IdempotencyRepository.class);
    private final ApiProperties properties = mock(ApiProperties.class);
    private final IdempotencyService service =
            new IdempotencyService(repository, Clock.fixed(NOW, ZoneOffset.UTC), properties);

    IdempotencyServiceMutationGapsTest() {
        when(properties.idempotency())
                .thenReturn(new ApiProperties.Idempotency(Duration.ofHours(24)));
    }

    @Test
    void completeStoresTheResponseOnTheRecordAndSavesIt() {
        IdempotencyRecord record =
                IdempotencyRecord.start("k1", "7", "h1", NOW, NOW.plusSeconds(60));
        when(repository.findById("k1")).thenReturn(Optional.of(record));
        service.complete("k1", 201, "application/json", "{\"id\":5}");
        assertThat(record.getStatus()).isEqualTo(IdempotencyRecord.Status.COMPLETED);
        assertThat(record.getResponseStatus()).isEqualTo(201);
        assertThat(record.getContentType()).isEqualTo("application/json");
        assertThat(record.getResponseBody()).isEqualTo("{\"id\":5}");
        verify(repository).save(record);
    }

    @Test
    void completeOfAnUnknownRecordSavesNothing() {
        when(repository.findById("gone")).thenReturn(Optional.empty());
        service.complete("gone", 200, "application/json", "{}");
        verify(repository, never()).save(any());
    }

    @Test
    void abandonForgetsTheRecordSoTheClientMayRetry() {
        service.abandon("k1");
        verify(repository).deleteById("k1");
    }
}
