package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.cpf.business.SspGateway;
import in.gov.fci.payroll.cpf.business.model.CpfSspSyncLog;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * SRS-6-10 / SRS-6-44, DDL {@code cpf_ssp_sync_log}: every change for the CPF SSP is logged - sent,
 * failed, or SKIPPED with its reason while no interface is configured; never silently dropped. Wave
 * C review (finding 7): a configured interface is called only after the caller's transaction
 * commits; until then the row waits as FAILED (queued), and the send's outcome updates it.
 */
class CpfSspSyncTest {

    private final CpfRepositories.SyncLog log = mock(CpfRepositories.SyncLog.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-23T05:00:00Z"), ZoneOffset.UTC);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SspGateway.Change change =
            new SspGateway.Change(
                    7L,
                    "OB_CORRECTION",
                    List.of("opening_balance_employee", "opening_balance_vpf"));

    {
        when(log.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private CpfSspSync sync(SspGateway gateway) {
        return new CpfSspSync(gateway, log, clock, events, transactions);
    }

    @Test
    void SRS_6_10_SRS_6_42_BR_CPF_035_withoutAnInterfaceTheChangeIsSkippedWithItsReason() {
        var row = sync(new NotConfiguredSspGateway()).queue(change);
        assertThat(row.getSyncStatus()).isEqualTo("SKIPPED");
        assertThat(row.getSkipReason()).contains("not configured");
        assertThat(row.getSyncDirection()).isEqualTo("TO_SSP");
        assertThat(row.getSyncTrigger()).isEqualTo("OB_CORRECTION");
        assertThat(row.getFieldList()).isEqualTo("opening_balance_employee,opening_balance_vpf");
        assertThat(row.getSyncedAt()).isEqualTo(clock.instant());
        assertThat(row.getRetryCount()).isZero();
        verify(events, never()).publishEvent(any(Object.class));
        assertThatThrownBy(() -> new NotConfiguredSspGateway().send(change, "CPF-SSP-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    // BR-EMPM-013: an Employee Master change is synced to the CPF Self-Service Portal.
    @Test
    void SRS_6_10_aConfiguredInterfaceSendsAfterTheCommitOrRecordsItsFailure() {
        SspGateway gateway = mock(SspGateway.class);
        when(gateway.configured()).thenReturn(true);
        CpfSspSync sync = sync(gateway);
        CpfSspSyncLog queued = sync.queue(change);
        assertThat(queued.getSyncStatus()).as("waits, retryable").isEqualTo("FAILED");
        assertThat(queued.getFailureDetail()).isEqualTo(CpfSspSync.QUEUED);
        // Wave D review 7: a waiting row is told apart from a real failure.
        assertThat(queued.isQueued()).isTrue();
        verify(gateway, never()).send(any(), any());
        ArgumentCaptor<CpfSspSync.Queued> event = ArgumentCaptor.forClass(CpfSspSync.Queued.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().change()).isEqualTo(change);

        when(log.findById(event.getValue().syncId())).thenReturn(Optional.of(queued));
        sync.on(event.getValue());
        // Wave D review 7: sent with the row's idempotency key, the same on every send.
        String key = CpfSspSyncLog.idempotencyKeyOf(event.getValue().syncId());
        assertThat(key).isEqualTo("CPF-SSP-" + event.getValue().syncId());
        verify(gateway).send(change, key);
        assertThat(queued.getSyncStatus()).isEqualTo("SUCCESS");
        assertThat(queued.getFailureDetail()).isNull();
        assertThat(queued.isQueued()).isFalse();

        doThrow(new IllegalArgumentException("refused")).when(gateway).send(change, key);
        sync.on(event.getValue());
        assertThat(queued.getSyncStatus()).isEqualTo("FAILED");
        assertThat(queued.getFailureDetail()).isEqualTo("SEND_FAILED: IllegalArgumentException");
        assertThat(queued.isQueued()).as("a real failure is not queued").isFalse();
        assertThat(queued.getSkipReason()).isNull();
        assertThat(queued.idempotencyKey()).isEqualTo(CpfSspSyncLog.idempotencyKeyOf(null));

        when(log.findById(event.getValue().syncId())).thenReturn(Optional.empty());
        sync.on(event.getValue());
    }

    /**
     * Wave D review 7: a SKIPPED or SUCCESS row is never queued; FAILED with another detail
     * neither.
     */
    @Test
    void SRS_6_10_onlyAFailedRowWithTheExactMarkerIsQueued() {
        CpfSspSyncLog skipped =
                CpfSspSyncLog.toSsp(7L, "REQUEST", "x", "SKIPPED", "r", null, clock.instant());
        assertThat(skipped.isQueued()).isFalse();
        CpfSspSyncLog other =
                CpfSspSyncLog.toSsp(7L, "REQUEST", "x", "FAILED", null, "QUEUED2", clock.instant());
        assertThat(other.isQueued()).isFalse();
        CpfSspSyncLog waiting =
                CpfSspSyncLog.toSsp(7L, "REQUEST", "x", "FAILED", null, "QUEUED", clock.instant());
        assertThat(waiting.isQueued()).isTrue();
    }
}
