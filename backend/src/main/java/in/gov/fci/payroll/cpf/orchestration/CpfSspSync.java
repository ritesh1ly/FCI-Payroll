package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.cpf.business.SspGateway;
import in.gov.fci.payroll.cpf.business.model.CpfSspSyncLog;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Queues a member change for the CPF Self-Service Portal (SRS-6-10, SRS-6-44, SRS-6-52) and logs
 * the outcome: sent (SUCCESS), refused by the interface (FAILED, for a later retry), or not sent
 * because no interface is configured (SKIPPED, with the reason). Never silent.
 *
 * <p>Wave C review (finding 7): the log row is written in the caller's transaction and the change
 * is sent only after that transaction commits, never while the caller holds its row locks (a
 * sanction's): until then the row is FAILED with the detail {@link #QUEUED} (DDL statuses SUCCESS /
 * FAILED / SKIPPED / CONFLICT; a crash before the send leaves a row to retry, never nothing), and
 * the send's outcome updates it in a transaction of its own. No interface configured: SKIPPED at
 * once, as before.
 *
 * <p>Wave D review 7: a waiting row carries the exact marker {@code QUEUED} and a real failure
 * {@code SEND_FAILED: <cause>}, so the two are told apart ({@link CpfSspSyncLog#isQueued()}); each
 * send carries the row's idempotency key {@code CPF-SSP-<sync_id>}. No retry job exists yet: a row
 * left queued or failed waits for the module-23 interface and its retry settings (R-INT).
 */
@Component
public class CpfSspSync {

    static final String NOT_CONFIGURED = "CPF SSP interface not configured";

    /**
     * The detail of a row whose change is sent once the caller's transaction commits (Wave D review
     * 7: one exact marker, never mistaken for a real failure).
     */
    static final String QUEUED = CpfSspSyncLog.QUEUED;

    /** A logged change to send once the caller's transaction has committed. */
    record Queued(Long syncId, SspGateway.Change change) {}

    private final SspGateway gateway;
    private final CpfRepositories.SyncLog log;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate ownTransaction;

    public CpfSspSync(
            SspGateway gateway,
            CpfRepositories.SyncLog log,
            Clock clock,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactions) {
        this.gateway = gateway;
        this.log = log;
        this.clock = clock;
        this.events = events;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Logs the change and queues its send for after the commit; returns the log row. */
    public CpfSspSyncLog queue(SspGateway.Change change) {
        String fields = String.join(",", change.fields());
        if (!gateway.configured()) {
            return log.save(
                    CpfSspSyncLog.toSsp(
                            change.employeeId(),
                            change.trigger(),
                            fields,
                            "SKIPPED",
                            NOT_CONFIGURED,
                            null,
                            clock.instant()));
        }
        CpfSspSyncLog row =
                log.save(
                        CpfSspSyncLog.toSsp(
                                change.employeeId(),
                                change.trigger(),
                                fields,
                                "FAILED",
                                null,
                                QUEUED,
                                clock.instant()));
        events.publishEvent(new Queued(row.getSyncId(), change));
        return row;
    }

    /**
     * Sends a queued change once the caller's transaction has committed (at once when there was
     * none) and records the outcome on its log row in a transaction of its own.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(Queued queued) {
        String failure = null;
        try {
            gateway.send(queued.change(), CpfSspSyncLog.idempotencyKeyOf(queued.syncId()));
        } catch (RuntimeException failed) {
            failure = failed.getClass().getSimpleName();
        }
        String outcome = failure;
        ownTransaction.executeWithoutResult(
                status ->
                        log.findById(queued.syncId())
                                .ifPresent(
                                        row -> {
                                            row.sent(outcome, clock.instant());
                                            log.save(row);
                                        }));
    }
}
