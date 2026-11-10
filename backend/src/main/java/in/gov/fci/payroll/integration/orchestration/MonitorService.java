package in.gov.fci.payroll.integration.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.integration.api.InterfaceAdapter;
import in.gov.fci.payroll.integration.api.InterfaceSettings;
import in.gov.fci.payroll.integration.api.InterfaceSettings.Settings;
import in.gov.fci.payroll.integration.business.IntegrationErrorCode;
import in.gov.fci.payroll.integration.business.IntegrationRules;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * X-03 Integration Monitor (plan 23 phase 23b; SRS-INT-6, SRS-INT-16, SRS-INT-17, SRS-INT-31 …
 * SRS-INT-33; SRS X-03 field table; D23-5 … D23-7). Every interface with its direction, the status
 * and counts of its latest window, its last good exchange, its open exceptions, its breaker and its
 * settings (read-only); the windows; the exception queue, which with its OPEN items is the
 * Integration Failure Report; the HRMS changes awaiting I-21 with the oldest one's age. Reprocess,
 * Write off and Test connection are the System Administrator's alone (X-03 EDIT), each with a
 * mandatory reason and audited with the interface and outcome - also when refused. A rejected
 * record's data stays as received in the database and is not shown (R-PII: it may hold PAN or bank
 * details; D23-9).
 */
@Service
@Loggable
public class MonitorService {

    static final String SCREEN = "X-03";

    /** One interface as X-03 lists it. */
    public record InterfaceRow(
            String code,
            String name,
            String counterparty,
            String direction,
            String transport,
            String endpointObject,
            String stagingObject,
            String consumingScreen,
            boolean specSignedOff,
            String scopeStatus,
            boolean active,
            String status,
            Instant lastExchangeAt,
            Instant lastGoodExchangeAt,
            long received,
            long accepted,
            long rejected,
            long openExceptions,
            String breakerState,
            boolean adapter,
            Settings settings) {}

    /** The specification held for an interface (Contract; SRS-INT-1). */
    public record Contract(
            InterfaceRow row, String frequency, String idempotencyKey, String reconciliation) {}

    /**
     * One exchange window; {@code adapter} when its interface has an adapter to reprocess through
     * (SRS-INT-33).
     */
    public record Window(
            long runId,
            String interfaceCode,
            String direction,
            String batchReference,
            Instant windowStart,
            Instant windowEnd,
            long received,
            long accepted,
            long rejected,
            String status,
            int retryAttempt,
            Instant startedAt,
            Instant completedAt,
            String failureDetail,
            boolean adapter) {}

    /** One item of the exception queue. */
    public record QueueItem(
            long id,
            String interfaceCode,
            Long runId,
            Long stagingId,
            String txnType,
            String sourceKey,
            String hrmsRequestId,
            String reason,
            String status,
            Instant raisedAt,
            Instant resolvedAt,
            String writtenOffBy,
            String writtenOffReason,
            Long reprocessedStagingId,
            boolean adapter) {}

    /** The figures above the lists. */
    public record Summary(
            long interfaces,
            long notConfigured,
            long openExceptions,
            long failedWindows,
            long awaitingI21,
            Instant oldestAwaitingI21) {}

    /** The outcome of an Administrator's action. */
    public record Outcome(String interfaceCode, String outcome) {}

    /**
     * What Reprocess Failed did for one exchange window (SRS-INT-33): the window, its interface and
     * the exception items reprocessed, in the order run.
     */
    public record WindowReprocess(long runId, String interfaceCode, List<Long> reprocessed) {}

    private final JdbcTemplate jdbc;
    private final IntegrationStore store;
    private final InterfaceSettings settings;
    private final Authorization policy;
    private final AuditService audit;
    private final ExceptionWorklist worklist;
    private final HrmsChanges hrms;
    private final Map<String, InterfaceAdapter> adapters;
    private final Clock clock;

    public MonitorService(
            IntegrationStore store,
            InterfaceSettings settings,
            Authorization policy,
            AuditService audit,
            ExceptionWorklist worklist,
            HrmsChanges hrms,
            ObjectProvider<InterfaceAdapter> adapters,
            Clock clock) {
        this.jdbc = store.jdbc();
        this.store = store;
        this.settings = settings;
        this.policy = policy;
        this.audit = audit;
        this.worklist = worklist;
        this.hrms = hrms;
        this.adapters =
                adapters.orderedStream()
                        .collect(
                                Collectors.toMap(
                                        InterfaceAdapter::interfaceCode, Function.identity()));
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Summary summary(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        List<String> codes =
                jdbc.queryForList(
                        "SELECT interface_code FROM interface_registry WHERE active_flag = 'Y'",
                        String.class);
        long notConfigured = codes.stream().filter(c -> settings.inForce(c).isEmpty()).count();
        Long open =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM interface_exception WHERE remediation_status IN"
                                + " ('OPEN', 'IN_PROGRESS')",
                        Long.class);
        Long failed =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM interface_run r WHERE r.run_status = 'FAILED' AND"
                                + " EXISTS (SELECT 1 FROM interface_exception e WHERE e.run_id ="
                                + " r.run_id AND e.remediation_status IN ('OPEN', 'IN_PROGRESS'))",
                        Long.class);
        HrmsChanges.Awaiting awaiting = hrms.awaiting();
        return new Summary(
                codes.size(),
                notConfigured,
                RowCounts.of(open),
                RowCounts.of(failed),
                awaiting.count(),
                awaiting.oldestReceivedAt());
    }

    @Transactional(readOnly = true)
    public Paged<InterfaceRow> interfaces(
            AccessContext context, String counterparty, PageSpec spec) {
        return interfaces(context, counterparty, null, null, null, spec);
    }

    /**
     * The interfaces of X-03 as Wireframes v6.1 draws them (OD-121): {@code scope} HRMS gives the
     * "HRMS -> Payroll interfaces" table, ENTERPRISE the "Enterprise interfaces" (every other
     * counterparty); {@code status} (OK, PARTIAL, FAILED: interface_run.run_status) keeps the
     * interfaces whose latest exchange ended so - the drawn status filter. Each filter applies only
     * when set.
     */
    @Transactional(readOnly = true)
    public Paged<InterfaceRow> interfaces(
            AccessContext context,
            String counterparty,
            String scope,
            String status,
            String search,
            PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        String cp = counterparty == null || counterparty.isBlank() ? null : counterparty;
        String sc = scope == null || scope.isBlank() ? null : scope;
        String st = status == null || status.isBlank() ? null : status;
        // The drawn "Search in list" (code or name, any case); the controller allows printable text
        // only.
        String q =
                search == null || search.isBlank()
                        ? null
                        : "%" + search.strip().toUpperCase() + "%";
        String where =
                " WHERE (? IS NULL OR r.counterparty_system = ?) AND (? IS NULL OR (? = 'HRMS' AND"
                    + " r.counterparty_system = 'HRMS') OR (? = 'ENTERPRISE' AND"
                    + " r.counterparty_system <> 'HRMS')) AND (? IS NULL OR (SELECT g.run_status"
                    + " FROM interface_run g WHERE g.interface_id = r.interface_id ORDER BY"
                    + " g.started_at DESC, g.run_id DESC FETCH FIRST 1 ROWS ONLY) = ?) AND (? IS"
                    + " NULL OR UPPER(r.interface_code || ' ' || r.interface_name) LIKE ?)";
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM interface_registry r" + where,
                        Long.class,
                        cp,
                        cp,
                        sc,
                        sc,
                        sc,
                        st,
                        st,
                        q,
                        q);
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        REGISTRY
                                + where
                                + " ORDER BY r.counterparty_system, r.interface_code OFFSET ? ROWS"
                                + " FETCH NEXT ? ROWS ONLY",
                        cp,
                        cp,
                        sc,
                        sc,
                        sc,
                        st,
                        st,
                        q,
                        q,
                        (long) spec.page() * spec.size(),
                        spec.size());
        List<InterfaceRow> items = new ArrayList<>();
        rows.forEach(r -> items.add(row(r)));
        return new Paged<>(items, spec.page(), spec.size(), RowCounts.of(total));
    }

    /**
     * SRS-INT-33 (US-GAP-62): the interface contract, for the Administrator only (X-03 EDIT, as
     * Test connection); every use is audited with the user, time, interface and outcome.
     */
    @Transactional
    public Contract contract(AccessContext context, String code) {
        policy.require(context, SCREEN, "EDIT");
        List<Map<String, Object>> rows =
                jdbc.queryForList(REGISTRY + " WHERE r.interface_code = ?", code);
        if (rows.isEmpty()) {
            throw unknown(code);
        }
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "interface_registry",
                                code,
                                "contract",
                                null,
                                "VIEWED",
                                "Contract viewed")
                        .module(SCREEN));
        Map<String, Object> r = rows.getFirst();
        return new Contract(
                row(r),
                (String) r.get("FREQUENCY"),
                (String) r.get("IDEMPOTENCY_KEY_DEF"),
                (String) r.get("RECONCILIATION_METHOD"));
    }

    @Transactional(readOnly = true)
    public Paged<Window> windows(AccessContext context, String code, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        String c = code == null || code.isBlank() ? null : code;
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM interface_run r JOIN interface_registry i ON"
                                + " i.interface_id = r.interface_id WHERE (? IS NULL OR"
                                + " i.interface_code = ?)",
                        Long.class,
                        c,
                        c);
        List<Window> items =
                jdbc.query(
                        "SELECT r.run_id, i.interface_code, r.direction, r.batch_reference,"
                                + " r.window_start, r.window_end, r.received_count,"
                                + " r.accepted_count, r.rejected_count, r.run_status,"
                                + " r.retry_attempt, r.started_at, r.completed_at,"
                                + " r.failure_detail FROM interface_run r JOIN interface_registry"
                                + " i ON i.interface_id = r.interface_id WHERE (? IS NULL OR"
                                + " i.interface_code = ?) ORDER BY r.started_at DESC, r.run_id"
                                + " DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                        (rs, i) ->
                                new Window(
                                        rs.getLong("run_id"),
                                        rs.getString("interface_code"),
                                        rs.getString("direction"),
                                        rs.getString("batch_reference"),
                                        instant(rs.getTimestamp("window_start")),
                                        instant(rs.getTimestamp("window_end")),
                                        rs.getLong("received_count"),
                                        rs.getLong("accepted_count"),
                                        rs.getLong("rejected_count"),
                                        rs.getString("run_status"),
                                        rs.getInt("retry_attempt"),
                                        instant(rs.getTimestamp("started_at")),
                                        instant(rs.getTimestamp("completed_at")),
                                        rs.getString("failure_detail"),
                                        adapters.containsKey(rs.getString("interface_code"))),
                        c,
                        c,
                        (long) spec.page() * spec.size(),
                        spec.size());
        return new Paged<>(items, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** Rows of the Integration Audit Trail for the interface filter (the size check first). */
    @Transactional(readOnly = true)
    public long auditTrailSize(AccessContext context, String code) {
        policy.require(context, SCREEN, "EXPORT");
        String c = code == null || code.isBlank() ? null : code;
        return RowCounts.of(
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM interface_run r JOIN interface_registry i ON"
                                + " i.interface_id = r.interface_id WHERE (? IS NULL OR"
                                + " i.interface_code = ?)",
                        Long.class,
                        c,
                        c));
    }

    /**
     * FRS V0.5 Integrations, Reports "Integration Audit Trail" (Export, Excel) and Audit Process:
     * every inbound and outbound message "at envelope level - interface name, direction, batch or
     * transaction identifier, record count, timestamp and outcome", newest first as on X-03, in one
     * read-only snapshot checked against its count. The per-record maker / checker, employee number
     * and prior / revised values of the report's description wait for FCI (PENDING US-GAP-97).
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void exportAuditTrail(AccessContext context, String code, ExportSink sink)
            throws IOException {
        long expected = auditTrailSize(context, code);
        String c = code == null || code.isBlank() ? null : code;
        sink.header(
                "run_id",
                "interface",
                "direction",
                "batch_reference",
                "window_start",
                "window_end",
                "received",
                "accepted",
                "rejected",
                "control_total",
                "outcome",
                "retry_attempt",
                "started_at",
                "completed_at",
                "failure_detail");
        try {
            jdbc.query(
                    "SELECT r.run_id, i.interface_code, r.direction, r.batch_reference,"
                            + " r.window_start, r.window_end, r.received_count, r.accepted_count,"
                            + " r.rejected_count, r.control_total, r.run_status, r.retry_attempt,"
                            + " r.started_at, r.completed_at, r.failure_detail FROM interface_run r"
                            + " JOIN interface_registry i ON i.interface_id = r.interface_id WHERE"
                            + " (? IS NULL OR i.interface_code = ?) ORDER BY r.started_at DESC,"
                            + " r.run_id DESC",
                    rs -> {
                        try {
                            sink.row(
                                    rs.getLong("run_id"),
                                    rs.getString("interface_code"),
                                    rs.getString("direction"),
                                    rs.getString("batch_reference"),
                                    instant(rs.getTimestamp("window_start")),
                                    instant(rs.getTimestamp("window_end")),
                                    rs.getLong("received_count"),
                                    rs.getLong("accepted_count"),
                                    rs.getLong("rejected_count"),
                                    rs.getBigDecimal("control_total"),
                                    rs.getString("run_status"),
                                    rs.getInt("retry_attempt"),
                                    instant(rs.getTimestamp("started_at")),
                                    instant(rs.getTimestamp("completed_at")),
                                    rs.getString("failure_detail"));
                        } catch (IOException failed) {
                            throw new UncheckedIOException(failed);
                        }
                    },
                    c,
                    c);
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
        ExportSink.requireComplete(expected, sink);
    }

    /**
     * The exception queue; {@code open} alone is the Integration Failure Report (SRS-INT-17,
     * SRS-INT-31).
     */
    @Transactional(readOnly = true)
    public Paged<QueueItem> queue(AccessContext context, boolean open, String code, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        String c = code == null || code.isBlank() ? null : code;
        String status = open ? "Y" : null;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + QUEUE_FROM, Long.class, c, c, status);
        List<QueueItem> items =
                jdbc.query(
                        "SELECT e.exception_id, i.interface_code, e.run_id, e.staging_id,"
                                + " s.txn_type, s.request_id, s.hrms_request_id,"
                                + " e.rejection_reason, e.remediation_status, e.raised_at,"
                                + " e.resolved_at, e.written_off_by, e.written_off_reason,"
                                + " e.reprocessed_staging_id"
                                + QUEUE_FROM
                                + " ORDER BY e.raised_at DESC, e.exception_id DESC OFFSET ? ROWS"
                                + " FETCH NEXT ? ROWS ONLY",
                        (rs, i) ->
                                new QueueItem(
                                        rs.getLong("exception_id"),
                                        rs.getString("interface_code"),
                                        rs.getObject("run_id", Long.class),
                                        rs.getObject("staging_id", Long.class),
                                        rs.getString("txn_type"),
                                        rs.getString("request_id"),
                                        rs.getString("hrms_request_id"),
                                        rs.getString("rejection_reason"),
                                        rs.getString("remediation_status"),
                                        instant(rs.getTimestamp("raised_at")),
                                        instant(rs.getTimestamp("resolved_at")),
                                        rs.getString("written_off_by"),
                                        rs.getString("written_off_reason"),
                                        rs.getObject("reprocessed_staging_id", Long.class),
                                        adapters.containsKey(rs.getString("interface_code"))),
                        c,
                        c,
                        status,
                        (long) spec.page() * spec.size(),
                        spec.size());
        return new Paged<>(items, spec.page(), spec.size(), RowCounts.of(total));
    }

    /**
     * Reprocess Failed (SRS-INT-31, SRS-INT-33; D23-5): the Administrator re-runs the record
     * through its interface's adapter; without one the action is refused and the attempt audited.
     */
    @Transactional
    public QueueItem reprocess(AccessContext context, long exceptionId, String reason) {
        policy.require(context, SCREEN, "EDIT");
        String why = requireReason(reason);
        Map<String, Object> e = openItem(exceptionId);
        String code = (String) e.get("INTERFACE_CODE");
        InterfaceAdapter adapter = adapters.get(code);
        Object staging = e.get("STAGING_ID");
        if (adapter == null || staging == null) {
            String outcome = adapter == null ? "NO_ADAPTER" : "NO_RECORD";
            audit.recordIndependently(
                    context.loginId(), entry(exceptionId, "REPROCESS", outcome, why));
            throw new FciException(
                    IntegrationErrorCode.NO_ADAPTER, new Object[] {code}, List.of(), null);
        }
        reprocessOne(context, exceptionId, e, adapter, why);
        clearIfSettled(context, code);
        return item(exceptionId);
    }

    /**
     * Reprocess Failed for one exchange window (X-03 l.5172 "Feeds the reprocessing queue for
     * rejected or failed records. Reprocess Failed is Administrator only"; SRS-INT-33): every OPEN
     * or IN_PROGRESS exception item of the window runs through the interface's adapter as the
     * per-item reprocess does, under the Administrator's one reason. The window is all or nothing:
     * an item that fails stops the run and nothing of it is kept, the failure audited with the item
     * (the Administrator retries the window or the item). Refused and audited: no adapter for the
     * interface, an item without its staged record; refused: a window with no open item.
     */
    @Transactional
    public WindowReprocess reprocessWindow(AccessContext context, long runId, String reason) {
        policy.require(context, SCREEN, "EDIT");
        String why = requireReason(reason);
        String code =
                jdbc
                        .queryForList(
                                "SELECT i.interface_code FROM interface_run r JOIN"
                                        + " interface_registry i ON i.interface_id ="
                                        + " r.interface_id WHERE r.run_id = ?",
                                String.class,
                                runId)
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new FciException(
                                                IntegrationErrorCode.WINDOW,
                                                new Object[] {runId},
                                                List.of(),
                                                null));
        List<Map<String, Object>> open =
                jdbc.queryForList(
                        "SELECT e.exception_id, e.staging_id, e.remediation_status,"
                                + " i.interface_code, s.txn_type, s.payload FROM"
                                + " interface_exception e JOIN interface_registry i ON"
                                + " i.interface_id = e.interface_id LEFT JOIN intf_staging_txn s"
                                + " ON s.staging_id = e.staging_id WHERE e.run_id = ? AND"
                                + " e.remediation_status IN ('OPEN', 'IN_PROGRESS') ORDER BY"
                                + " e.exception_id",
                        runId);
        if (open.isEmpty()) {
            throw new FciException(
                    IntegrationErrorCode.WINDOW, new Object[] {runId}, List.of(), null);
        }
        InterfaceAdapter adapter = adapters.get(code);
        boolean unstaged = open.stream().anyMatch(e -> e.get("STAGING_ID") == null);
        if (adapter == null || unstaged) {
            audit.recordIndependently(
                    context.loginId(),
                    windowEntry(runId, adapter == null ? "NO_ADAPTER" : "NO_RECORD", why));
            throw new FciException(
                    IntegrationErrorCode.NO_ADAPTER, new Object[] {code}, List.of(), null);
        }
        List<Long> done = new ArrayList<>();
        for (Map<String, Object> e : open) {
            long exceptionId = ((Number) e.get("EXCEPTION_ID")).longValue();
            try {
                reprocessOne(context, exceptionId, e, adapter, why);
            } catch (FciException failure) {
                audit.recordIndependently(
                        context.loginId(), windowEntry(runId, "FAILED AT " + exceptionId, why));
                throw failure;
            }
            done.add(exceptionId);
        }
        audit.record(context.loginId(), windowEntry(runId, "REPROCESSED " + done.size(), why));
        clearIfSettled(context, code);
        return new WindowReprocess(runId, code, List.copyOf(done));
    }

    /** One open item through its adapter; its staged record and the item marked REPROCESSED. */
    private void reprocessOne(
            AccessContext context,
            long exceptionId,
            Map<String, Object> e,
            InterfaceAdapter adapter,
            String why) {
        String code = (String) e.get("INTERFACE_CODE");
        long stagingId = ((Number) e.get("STAGING_ID")).longValue();
        Timestamp now = Timestamp.from(clock.instant());
        try {
            adapter.reprocess(stagingId, (String) e.get("TXN_TYPE"), (String) e.get("PAYLOAD"));
        } catch (RuntimeException failure) {
            String outcome = "FAILED: " + InterfaceCallService.summary(failure);
            audit.recordIndependently(
                    context.loginId(), entry(exceptionId, "REPROCESS", outcome, why));
            throw new FciException(
                    IntegrationErrorCode.CALL_FAILED,
                    new Object[] {code, 1, InterfaceCallService.summary(failure)},
                    List.of(),
                    failure);
        }
        jdbc.update(
                "UPDATE intf_staging_txn SET staging_status = 'REPROCESSED', processed_at = ?"
                        + " WHERE staging_id = ?",
                now,
                stagingId);
        store.insert(
                "intf_staging_event",
                "event_id",
                IntegrationStore.row(
                        "staging_id",
                        stagingId,
                        "event_type",
                        "REPROCESSED",
                        "event_detail",
                        IntegrationRules.cut("Reprocessed by the Administrator: " + why, 2000),
                        "event_by",
                        context.loginId(),
                        "event_at",
                        now));
        jdbc.update(
                "UPDATE interface_exception SET remediation_status = 'REPROCESSED',"
                        + " reprocessed_staging_id = ?, resolved_at = ? WHERE exception_id = ?",
                stagingId,
                now,
                exceptionId);
        audit.record(context.loginId(), entry(exceptionId, "REPROCESS", "REPROCESSED", why));
    }

    /** Write off (SRS-INT-31, DDL interface_exception): closed with the Administrator's reason. */
    @Transactional
    public QueueItem writeOff(AccessContext context, long exceptionId, String reason) {
        policy.require(context, SCREEN, "EDIT");
        String why = requireReason(reason);
        Map<String, Object> e = openItem(exceptionId);
        jdbc.update(
                "UPDATE interface_exception SET remediation_status = 'WRITTEN_OFF',"
                        + " written_off_by = ?, written_off_reason = ?, resolved_at = ? WHERE"
                        + " exception_id = ?",
                IntegrationRules.cut(context.loginId(), 50),
                why,
                Timestamp.from(clock.instant()),
                exceptionId);
        audit.record(context.loginId(), entry(exceptionId, "WRITE_OFF", "WRITTEN_OFF", why));
        clearIfSettled(context, (String) e.get("INTERFACE_CODE"));
        return item(exceptionId);
    }

    /**
     * Test connection (SRS-INT-33; D23-7): through the interface's adapter; NO_ADAPTER while none
     * is built. Every use is audited with the user, time, interface and outcome.
     */
    @Transactional
    public Outcome testConnection(AccessContext context, String code) {
        policy.require(context, SCREEN, "EDIT");
        store.interfaceOf(code);
        InterfaceAdapter adapter = adapters.get(code);
        String outcome;
        if (adapter == null) {
            outcome = "NO_ADAPTER";
        } else {
            try {
                outcome = IntegrationRules.cut(adapter.testConnection(), 300);
            } catch (RuntimeException failure) {
                outcome = "FAILED: " + InterfaceCallService.summary(failure);
            }
        }
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "interface_registry",
                                code,
                                "test_connection",
                                null,
                                outcome,
                                "Test connection")
                        .module(SCREEN));
        return new Outcome(code, outcome);
    }

    private static final String QUEUE_FROM =
            " FROM interface_exception e JOIN interface_registry i ON i.interface_id ="
                    + " e.interface_id LEFT JOIN intf_staging_txn s ON s.staging_id ="
                    + " e.staging_id WHERE (? IS NULL OR i.interface_code = ?) AND (? IS NULL"
                    + " OR e.remediation_status IN ('OPEN', 'IN_PROGRESS'))";

    private static final String REGISTRY =
            "SELECT r.interface_id, r.interface_code, r.interface_name, r.counterparty_system,"
                + " r.direction, r.transport, r.endpoint_object, r.staging_object,"
                + " r.consuming_screen, r.spec_signed_off, r.scope_status, r.active_flag,"
                + " r.frequency, r.idempotency_key_def, r.reconciliation_method, (SELECT"
                + " b.breaker_state FROM interface_breaker_state b WHERE b.interface_id ="
                + " r.interface_id) breaker_state, (SELECT COUNT(*) FROM interface_exception e"
                + " WHERE e.interface_id = r.interface_id AND e.remediation_status IN ('OPEN',"
                + " 'IN_PROGRESS')) open_exceptions, (SELECT MAX(g.completed_at) FROM interface_run"
                + " g WHERE g.interface_id = r.interface_id AND g.run_status = 'OK') last_good FROM"
                + " interface_registry r";

    private InterfaceRow row(Map<String, Object> r) {
        long id = ((Number) r.get("INTERFACE_ID")).longValue();
        String code = (String) r.get("INTERFACE_CODE");
        List<Map<String, Object>> latest =
                jdbc.queryForList(
                        "SELECT run_status, received_count, accepted_count, rejected_count,"
                                + " COALESCE(completed_at, started_at) at FROM interface_run WHERE"
                                + " interface_id = ? ORDER BY started_at DESC, run_id DESC FETCH"
                                + " FIRST 1 ROWS ONLY",
                        id);
        Map<String, Object> last = latest.isEmpty() ? Map.of() : latest.getFirst();
        return new InterfaceRow(
                code,
                (String) r.get("INTERFACE_NAME"),
                (String) r.get("COUNTERPARTY_SYSTEM"),
                (String) r.get("DIRECTION"),
                (String) r.get("TRANSPORT"),
                (String) r.get("ENDPOINT_OBJECT"),
                (String) r.get("STAGING_OBJECT"),
                (String) r.get("CONSUMING_SCREEN"),
                "Y".equals(r.get("SPEC_SIGNED_OFF")),
                (String) r.get("SCOPE_STATUS"),
                "Y".equals(r.get("ACTIVE_FLAG")),
                latest.isEmpty() ? null : (String) last.get("RUN_STATUS"),
                latest.isEmpty() ? null : instant((Timestamp) last.get("AT")),
                instant((Timestamp) r.get("LAST_GOOD")),
                count(last.get("RECEIVED_COUNT")),
                count(last.get("ACCEPTED_COUNT")),
                count(last.get("REJECTED_COUNT")),
                count(r.get("OPEN_EXCEPTIONS")),
                r.get("BREAKER_STATE") == null ? "CLOSED" : (String) r.get("BREAKER_STATE"),
                adapters.containsKey(code),
                settings.inForce(code).orElse(null));
    }

    private Map<String, Object> openItem(long exceptionId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT e.exception_id, e.staging_id, e.remediation_status,"
                                + " i.interface_code, s.txn_type, s.payload FROM"
                                + " interface_exception e JOIN interface_registry i ON"
                                + " i.interface_id = e.interface_id LEFT JOIN intf_staging_txn s"
                                + " ON s.staging_id = e.staging_id WHERE e.exception_id = ?",
                        exceptionId);
        if (rows.isEmpty()) {
            throw new FciException(
                    IntegrationErrorCode.STATE,
                    new Object[] {exceptionId, "UNKNOWN"},
                    List.of(),
                    null);
        }
        Map<String, Object> e = rows.getFirst();
        String status = (String) e.get("REMEDIATION_STATUS");
        if (!"OPEN".equals(status) && !"IN_PROGRESS".equals(status)) {
            throw new FciException(
                    IntegrationErrorCode.STATE,
                    new Object[] {exceptionId, status},
                    List.of(),
                    null);
        }
        return e;
    }

    private QueueItem item(long exceptionId) {
        return jdbc.query(
                        "SELECT e.exception_id, i.interface_code, e.run_id, e.staging_id,"
                                + " s.txn_type, s.request_id, s.hrms_request_id,"
                                + " e.rejection_reason, e.remediation_status, e.raised_at,"
                                + " e.resolved_at, e.written_off_by, e.written_off_reason,"
                                + " e.reprocessed_staging_id FROM interface_exception e JOIN"
                                + " interface_registry i ON i.interface_id = e.interface_id LEFT"
                                + " JOIN intf_staging_txn s ON s.staging_id = e.staging_id WHERE"
                                + " e.exception_id = ?",
                        (rs, i) ->
                                new QueueItem(
                                        rs.getLong("exception_id"),
                                        rs.getString("interface_code"),
                                        rs.getObject("run_id", Long.class),
                                        rs.getObject("staging_id", Long.class),
                                        rs.getString("txn_type"),
                                        rs.getString("request_id"),
                                        rs.getString("hrms_request_id"),
                                        rs.getString("rejection_reason"),
                                        rs.getString("remediation_status"),
                                        instant(rs.getTimestamp("raised_at")),
                                        instant(rs.getTimestamp("resolved_at")),
                                        rs.getString("written_off_by"),
                                        rs.getString("written_off_reason"),
                                        rs.getObject("reprocessed_staging_id", Long.class),
                                        adapters.containsKey(rs.getString("interface_code"))),
                        exceptionId)
                .getFirst();
    }

    /** With no open item left for the interface, its X-05 Integration Failure entry is settled. */
    private void clearIfSettled(AccessContext context, String code) {
        Long open =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM interface_exception e JOIN interface_registry i ON"
                                + " i.interface_id = e.interface_id WHERE i.interface_code = ? AND"
                                + " e.remediation_status IN ('OPEN', 'IN_PROGRESS')",
                        Long.class,
                        code);
        if (RowCounts.of(open) == 0) {
            worklist.clear(
                    context.loginId(),
                    FailureAlerts.CODE,
                    null,
                    null,
                    code,
                    "Every X-03 exception of " + code + " resolved");
        }
    }

    private static String requireReason(String reason) {
        if (IntegrationRules.reasonDefect(reason).isPresent()) {
            throw new FciException(IntegrationErrorCode.REASON, new Object[0], List.of(), null);
        }
        return reason.trim();
    }

    private static AuditService.Entry entry(
            long exceptionId, String action, String outcome, String reason) {
        return AuditService.Entry.change(
                        "interface_exception",
                        String.valueOf(exceptionId),
                        action.toLowerCase(java.util.Locale.ROOT),
                        null,
                        outcome,
                        reason)
                .module(SCREEN);
    }

    /** The audit entry of a window's Reprocess Failed (SRS-INT-33, SRS-G-2). */
    private static AuditService.Entry windowEntry(long runId, String outcome, String reason) {
        return AuditService.Entry.change(
                        "interface_run",
                        String.valueOf(runId),
                        "reprocess_failed",
                        null,
                        outcome,
                        reason)
                .module(SCREEN);
    }

    private static FciException unknown(String code) {
        return new FciException(
                IntegrationErrorCode.UNKNOWN_INTERFACE, new Object[] {code}, List.of(), null);
    }

    private static long count(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
