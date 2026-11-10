package in.gov.fci.payroll.integration.orchestration;

import in.gov.fci.payroll.integration.api.InterfaceIntake;
import in.gov.fci.payroll.integration.business.IntegrationErrorCode;
import in.gov.fci.payroll.integration.business.IntegrationRules;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The exchange envelope and its records (plan 23 phase 23a; SRS-INT-11, SRS-INT-12, SRS-INT-31,
 * SRS-INT-32; D23-4 … D23-6). Every record is staged as received with its source key, unique per
 * transaction type ({@code uk_intf_staging_txn}): a key already staged is rejected as a duplicate
 * and counted rejected in its window, never staged twice. A resend of a rejected record comes under
 * a new key naming the rejected one: it is linked ({@code reprocess_of_staging_id}) and the open
 * exception closes REPROCESSED. Completion checks received = accepted + rejected; an unbalanced
 * window is FAILED and raises the Integration Failure at once.
 */
@Service
public class IntakeService implements InterfaceIntake {

    private final IntegrationStore store;
    private final FailureAlerts alerts;
    private final Clock clock;

    public IntakeService(IntegrationStore store, FailureAlerts alerts, Clock clock) {
        this.store = store;
        this.alerts = alerts;
        this.clock = clock;
    }

    private JdbcTemplate jdbc() {
        return store.jdbc();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    @Override
    @Transactional
    public long open(
            String interfaceCode, String batchReference, Instant windowStart, Instant windowEnd) {
        IntegrationStore.Registered intf = store.interfaceOf(interfaceCode);
        return store.insert(
                "interface_run",
                "run_id",
                IntegrationStore.row(
                        "interface_id",
                        intf.id(),
                        "direction",
                        intf.direction(),
                        "batch_reference",
                        IntegrationRules.cut(batchReference, 60),
                        "window_start",
                        windowStart == null ? null : Timestamp.from(windowStart),
                        "window_end",
                        windowEnd == null ? null : Timestamp.from(windowEnd),
                        "run_status",
                        "OK",
                        "started_at",
                        now()));
    }

    @Override
    @Transactional
    public Staged stage(long runId, Inbound record) {
        Map<String, Object> run = openRun(runId);
        IntegrationRules.recordDefect(record.txnType(), record.requestId(), record.payload())
                .ifPresent(
                        d -> {
                            throw new FciException(
                                    IntegrationErrorCode.RECORD, new Object[] {d}, List.of(), null);
                        });
        jdbc().update(
                        "UPDATE interface_run SET received_count = received_count + 1 WHERE"
                                + " run_id = ?",
                        runId);
        List<Long> earlier =
                jdbc().queryForList(
                                "SELECT staging_id FROM intf_staging_txn WHERE txn_type = ? AND"
                                        + " request_id = ?",
                                Long.class,
                                record.txnType(),
                                record.requestId());
        if (!earlier.isEmpty()) {
            long first = earlier.getFirst();
            jdbc().update(
                            "UPDATE interface_run SET rejected_count = rejected_count + 1 WHERE"
                                    + " run_id = ?",
                            runId);
            event(first, "DUPLICATE_REJECTED", "Received again in run " + runId);
            return new Staged(first, Outcome.DUPLICATE);
        }
        Long reprocessOf = null;
        if (record.reprocessOfRequestId() != null && !record.reprocessOfRequestId().isBlank()) {
            List<Long> original =
                    jdbc().queryForList(
                                    "SELECT staging_id FROM intf_staging_txn WHERE txn_type = ?"
                                            + " AND request_id = ? AND staging_status IN"
                                            + " ('REJECTED', 'ERROR')",
                                    Long.class,
                                    record.txnType(),
                                    record.reprocessOfRequestId());
            if (original.isEmpty()) {
                throw new FciException(
                        IntegrationErrorCode.RECORD,
                        new Object[] {"REPROCESS_OF"},
                        List.of(),
                        null);
            }
            reprocessOf = original.getFirst();
        }
        long id =
                store.insert(
                        "intf_staging_txn",
                        "staging_id",
                        IntegrationStore.row(
                                "interface_id",
                                ((Number) run.get("INTERFACE_ID")).longValue(),
                                "run_id",
                                runId,
                                "source_system",
                                run.get("COUNTERPARTY_SYSTEM"),
                                "direction",
                                "INBOUND",
                                "txn_type",
                                record.txnType(),
                                "request_id",
                                record.requestId(),
                                "hrms_request_id",
                                IntegrationRules.cut(record.hrmsRequestId(), 50),
                                "employee_id",
                                record.employeeId(),
                                "payload",
                                record.payload(),
                                "staging_status",
                                "RECEIVED",
                                "received_at",
                                now(),
                                "reprocess_of_staging_id",
                                reprocessOf));
        event(id, "RECEIVED", null);
        if (reprocessOf != null) {
            jdbc().update(
                            "UPDATE intf_staging_txn SET staging_status = 'REPROCESSED',"
                                    + " processed_at = ? WHERE staging_id = ?",
                            now(),
                            reprocessOf);
            event(reprocessOf, "REPROCESSED", "Resent as staging " + id);
            jdbc().update(
                            "UPDATE interface_exception SET remediation_status = 'REPROCESSED',"
                                    + " reprocessed_staging_id = ?, resolved_at = ? WHERE"
                                    + " staging_id = ? AND remediation_status IN ('OPEN',"
                                    + " 'IN_PROGRESS')",
                            id,
                            now(),
                            reprocessOf);
        }
        return new Staged(id, Outcome.STAGED);
    }

    @Override
    @Transactional
    public void applied(long stagingId, String appliedEntity, String appliedKey) {
        Map<String, Object> s = staged(stagingId);
        jdbc().update(
                        "UPDATE intf_staging_txn SET staging_status = 'APPLIED', applied_entity ="
                                + " ?, applied_key = ?, processed_at = ? WHERE staging_id = ?",
                        IntegrationRules.cut(appliedEntity, 100),
                        IntegrationRules.cut(appliedKey, 100),
                        now(),
                        stagingId);
        count(s, true);
        event(stagingId, "APPLIED", appliedEntity + " " + appliedKey);
    }

    @Override
    @Transactional
    public void rejected(long stagingId, String reason) {
        Map<String, Object> s = staged(stagingId);
        String why = reason == null || reason.isBlank() ? "No reason given by the adapter" : reason;
        jdbc().update(
                        "UPDATE intf_staging_txn SET staging_status = 'REJECTED', error_detail = ?,"
                                + " processed_at = ? WHERE staging_id = ?",
                        IntegrationRules.cut(why, 2000),
                        now(),
                        stagingId);
        count(s, false);
        event(stagingId, "ERROR", IntegrationRules.cut(why, 2000));
        alerts.raise(
                store.interfaceOf((String) s.get("INTERFACE_CODE")),
                ((Number) s.get("RUN_ID")).longValue(),
                stagingId,
                (String) s.get("PAYLOAD"),
                why);
    }

    @Override
    @Transactional
    public Completed complete(long runId, BigDecimal controlTotal) {
        Map<String, Object> run = openRun(runId);
        long received = ((Number) run.get("RECEIVED_COUNT")).longValue();
        long accepted = ((Number) run.get("ACCEPTED_COUNT")).longValue();
        long rejected = ((Number) run.get("REJECTED_COUNT")).longValue();
        return finish(run, received, accepted, rejected, controlTotal, null);
    }

    @Override
    @Transactional
    public Completed completeCounts(
            long runId, long received, long accepted, long rejected, String detail) {
        Map<String, Object> run = openRun(runId);
        return finish(run, received, accepted, rejected, null, detail);
    }

    private Completed finish(
            Map<String, Object> run,
            long received,
            long accepted,
            long rejected,
            BigDecimal controlTotal,
            String detail) {
        long runId = ((Number) run.get("RUN_ID")).longValue();
        String status = IntegrationRules.windowStatus(received, accepted, rejected);
        String failure =
                IntegrationRules.FAILED.equals(status)
                        ? "Received "
                                + received
                                + " is not accepted "
                                + accepted
                                + " + rejected "
                                + rejected
                                + ": records lost in the window"
                        : null;
        jdbc().update(
                        "UPDATE interface_run SET received_count = ?, accepted_count = ?,"
                                + " rejected_count = ?, control_total = ?, run_status = ?,"
                                + " completed_at = ?, failure_detail = ? WHERE run_id = ?",
                        received,
                        accepted,
                        rejected,
                        controlTotal,
                        status,
                        now(),
                        IntegrationRules.cut(failure == null ? detail : failure, 2000),
                        runId);
        store.insert(
                "interface_acknowledgement",
                "ack_id",
                IntegrationStore.row(
                        "run_id",
                        runId,
                        "ack_direction",
                        "SENT",
                        "ack_status",
                        "PENDING",
                        "ack_reference",
                        run.get("BATCH_REFERENCE"),
                        "detail",
                        "Accepted " + accepted + ", rejected " + rejected));
        if (failure != null) {
            alerts.raise(
                    store.interfaceOf((String) run.get("INTERFACE_CODE")),
                    runId,
                    null,
                    null,
                    failure);
        }
        return new Completed(runId, status, received, accepted, rejected);
    }

    private Map<String, Object> openRun(long runId) {
        List<Map<String, Object>> rows =
                jdbc().queryForList(
                                "SELECT r.run_id, r.interface_id, r.batch_reference,"
                                        + " r.received_count, r.accepted_count, r.rejected_count,"
                                        + " r.completed_at, i.interface_code,"
                                        + " i.counterparty_system FROM interface_run r JOIN"
                                        + " interface_registry i ON i.interface_id ="
                                        + " r.interface_id WHERE r.run_id = ?",
                                runId);
        if (rows.isEmpty() || rows.getFirst().get("COMPLETED_AT") != null) {
            throw new FciException(
                    IntegrationErrorCode.RECORD, new Object[] {"RUN"}, List.of(), null);
        }
        return rows.getFirst();
    }

    private Map<String, Object> staged(long stagingId) {
        List<Map<String, Object>> rows =
                jdbc().queryForList(
                                "SELECT s.staging_id, s.run_id, s.staging_status, s.payload,"
                                        + " i.interface_code FROM intf_staging_txn s JOIN"
                                        + " interface_registry i ON i.interface_id ="
                                        + " s.interface_id WHERE s.staging_id = ?",
                                stagingId);
        if (rows.isEmpty() || !"RECEIVED".equals(rows.getFirst().get("STAGING_STATUS"))) {
            throw new FciException(
                    IntegrationErrorCode.RECORD, new Object[] {"STAGING"}, List.of(), null);
        }
        return rows.getFirst();
    }

    private void count(Map<String, Object> staged, boolean accepted) {
        Object run = staged.get("RUN_ID");
        if (run == null) {
            return;
        }
        jdbc().update(
                        accepted
                                ? "UPDATE interface_run SET accepted_count = accepted_count + 1"
                                        + " WHERE run_id = ? AND completed_at IS NULL"
                                : "UPDATE interface_run SET rejected_count = rejected_count + 1"
                                        + " WHERE run_id = ? AND completed_at IS NULL",
                        ((Number) run).longValue());
    }

    private void event(long stagingId, String type, String detail) {
        store.insert(
                "intf_staging_event",
                "event_id",
                IntegrationStore.row(
                        "staging_id",
                        stagingId,
                        "event_type",
                        type,
                        "event_detail",
                        IntegrationRules.cut(detail, 2000),
                        "event_by",
                        FailureAlerts.SYSTEM,
                        "event_at",
                        now()));
    }
}
