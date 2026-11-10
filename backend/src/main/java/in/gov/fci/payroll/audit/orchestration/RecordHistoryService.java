package in.gov.fci.payroll.audit.orchestration;

import in.gov.fci.payroll.audit.api.ActorDirectory;
import in.gov.fci.payroll.audit.api.ApprovalOutcomes;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.ChangeType;
import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.audit.business.AuditOutcome;
import in.gov.fci.payroll.audit.business.PointInTime;
import in.gov.fci.payroll.audit.business.model.AuditEvent;
import in.gov.fci.payroll.audit.persistence.AuditRepositories;
import in.gov.fci.payroll.platform.approval.AttachmentPresence;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * X-04 Audit Trail &amp; Change History (on A-05) and the History button of the master screens: the
 * change history of one master or rule record (SRS-20-29, SRS-G-2) and its reconstruction as of a
 * date (SRS-20-3, SRS-20-6), with the Change History Report and the Point-in-Time Reconstruction
 * Extract as exports. Read only: history is never changed (SRS-20-19).
 *
 * <p>A record is reached only through its module's {@link RecordHistorySource} (X-04 Record Key:
 * "must resolve to an existing master or rule record"). The reader needs VIEW on A-05, or on the
 * record's own screen (the History button), at the record's office.
 */
@Service
@Loggable
public class RecordHistoryService {

    /** The audit screen (X-04 is served by A-05, plan decision D-1). */
    static final String AUDIT_SCREEN = "A-05";

    /** A maker or checker: login id with name and Employee No. (X-04 Maker and Checker). */
    public record Person(String loginId, String name, String employeeNumber) {}

    /**
     * One native audit row with the X-04 fields. {@code transactionId} is the audit row id (plan
     * decision D-2); {@code origin} is always NATIVE (legacy rows are a separate list, SRS-20-22).
     */
    public record HistoryRow(
            Long transactionId,
            Instant changedAt,
            String objectKey,
            String field,
            String oldValue,
            String newValue,
            String outcome,
            String changeType,
            LocalDate effectiveDate,
            String reason,
            Long approvalId,
            String approvalStatus,
            Person maker,
            Person checker,
            String sourceIp,
            String moduleCode,
            String origin,
            Order order) {}

    /**
     * SRS l.1248 Order Reference: the latest active document of the changed record that carries a
     * reference number, with its id for the link. Null where the change carries no order.
     */
    public record Order(String number, Long documentId) {}

    /** One approved version of a versioned record. */
    public record VersionView(
            int versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Long approvalId,
            Map<String, String> values) {}

    public record RecordView(
            String entity,
            String key,
            String screen,
            boolean versioned,
            List<VersionView> versions,
            List<HistoryRow> items,
            int page,
            int size,
            long total) {}

    /**
     * The record as it stood on {@code date}: {@code status} FOUND (one version, or the fields
     * rebuilt from the audit rows) or NOT_IN_FORCE. Two versions on one date are refused as
     * AUD-409-VERSIONS, never resolved by a pick.
     */
    public record AsOfView(
            String entity,
            String key,
            LocalDate date,
            String status,
            boolean versioned,
            Integer versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Long approvalId,
            Map<String, String> values) {}

    private final Map<String, RecordHistorySource> sources;
    private final AuditRepositories.Audit audit;
    private final Authorization policy;
    private final ExportReader exportReader;
    private final ObjectProvider<ApprovalOutcomes> approvals;
    private final ObjectProvider<ActorDirectory> actors;
    private final AuditService auditWriter;
    private final ObjectProvider<AttachmentPresence> documents;

    /** The audit entity of a logged point-in-time reconstruction query (FRS V0.5 §25). */
    static final String POINT_IN_TIME = "point_in_time_query";

    @SuppressWarnings("java:S107") // one collaborator per concern
    public RecordHistoryService(
            List<RecordHistorySource> sources,
            AuditRepositories.Audit audit,
            Authorization policy,
            ExportReader exportReader,
            ObjectProvider<ApprovalOutcomes> approvals,
            ObjectProvider<ActorDirectory> actors,
            AuditService auditWriter,
            ObjectProvider<AttachmentPresence> documents) {
        this.auditWriter = auditWriter;
        this.documents = documents;
        this.sources =
                sources.stream()
                        .collect(
                                Collectors.toMap(
                                        RecordHistorySource::entity,
                                        Function.identity(),
                                        (a, b) -> {
                                            throw new IllegalStateException(
                                                    "two history sources for " + a.entity());
                                        },
                                        TreeMap::new));
        this.audit = audit;
        this.policy = policy;
        this.exportReader = exportReader;
        this.approvals = approvals;
        this.actors = actors;
    }

    /** The record types X-04 can enquire on (the Record Key's object list). */
    @Transactional(readOnly = true)
    public List<String> types(AccessContext context) {
        policy.require(context, AUDIT_SCREEN, "VIEW");
        return List.copyOf(sources.keySet());
    }

    /** SRS-20-29 / SRS-G-2: one record's native change history, oldest first, one page. */
    @Transactional(readOnly = true)
    public RecordView history(
            AccessContext context, String entity, String key, int page, int size) {
        HistoryRecord record = resolve(context, entity, key, "VIEW");
        Page<AuditEvent> found =
                rows(record, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));
        return new RecordView(
                record.entity(),
                record.key(),
                record.screen(),
                record.isVersioned(),
                record.versions().stream()
                        .map(
                                v ->
                                        new VersionView(
                                                v.versionNo(),
                                                v.effectiveFrom(),
                                                v.effectiveTo(),
                                                v.approvalId(),
                                                v.values()))
                        .toList(),
                enrich(found.getContent()),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements());
    }

    /**
     * SRS-20-3 / SRS-20-6: the record as it stood on {@code date}. FRS V0.5 §25 Audit Process:
     * "every point-in-time reconstruction query ... is itself logged with the requester, the scope
     * requested and the timestamp" (with SRS-20-24), in its own transaction, as the Legacy Audit
     * enquiry is; the reconstruction extract passes here first, so it is logged too.
     */
    @Transactional(readOnly = true)
    public AsOfView asOf(AccessContext context, String entity, String key, LocalDate date) {
        HistoryRecord record = resolve(context, entity, key, "VIEW");
        AsOfView view = asOf(record, date);
        String logKey = record.entity() + "/" + record.key();
        auditWriter.recordIndependently(
                context.loginId(),
                AuditService.Entry.change(
                                POINT_IN_TIME,
                                logKey.length() > 100 ? logKey.substring(0, 100) : logKey,
                                "ENQUIRY",
                                null,
                                LegacyAuditService.scope(context) + ";asOf=" + date,
                                "Point-in-time reconstruction query")
                        .module(AUDIT_SCREEN));
        return view;
    }

    /** Rows of the Change History Report (the size check before streaming). */
    @Transactional(readOnly = true)
    public long historySize(AccessContext context, String entity, String key) {
        HistoryRecord record = resolve(context, entity, key, "EXPORT");
        return exportReader.count(p -> rows(record, p));
    }

    /**
     * SRS-20-29 Change History Report: every native row of the record, oldest first, in one
     * read-only snapshot, checked against the snapshot's count. Origin on every row (SRS-20-22).
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void exportHistory(AccessContext context, String entity, String key, ExportSink sink)
            throws IOException {
        HistoryRecord record = resolve(context, entity, key, "EXPORT");
        sink.header(
                "origin",
                "transaction_id",
                "date_time",
                "object_key",
                "field",
                "old_value",
                "new_value",
                "outcome",
                "effective_date",
                "reason",
                "approval_id",
                "maker",
                "maker_employee_no",
                "checker",
                "checker_employee_no",
                "ip_address");
        long expected =
                exportReader.readAll(
                        p -> rows(record, p),
                        batch -> {
                            for (HistoryRow r : enrich(batch)) {
                                sink.row(
                                        r.origin(),
                                        r.transactionId(),
                                        r.changedAt(),
                                        r.objectKey(),
                                        r.field(),
                                        r.oldValue(),
                                        r.newValue(),
                                        r.outcome(),
                                        r.effectiveDate(),
                                        r.reason(),
                                        r.approvalId(),
                                        name(r.maker()),
                                        r.maker() == null ? null : r.maker().employeeNumber(),
                                        name(r.checker()),
                                        r.checker() == null ? null : r.checker().employeeNumber(),
                                        r.sourceIp());
                            }
                        });
        ExportSink.requireComplete(expected, sink);
    }

    /** SRS-20-29 Point-in-Time Reconstruction Extract: the record's fields on {@code date}. */
    @Transactional(readOnly = true)
    public void exportAsOf(
            AccessContext context, String entity, String key, LocalDate date, ExportSink sink)
            throws IOException {
        HistoryRecord record = resolve(context, entity, key, "EXPORT");
        AsOfView view = asOf(record, date);
        sink.header(
                "object",
                "key",
                "as_of",
                "status",
                "version",
                "effective_from",
                "effective_to",
                "approval_id",
                "field",
                "value");
        if (view.values().isEmpty()) {
            sink.row(
                    view.entity(),
                    view.key(),
                    view.date(),
                    view.status(),
                    view.versionNo(),
                    view.effectiveFrom(),
                    view.effectiveTo(),
                    view.approvalId(),
                    null,
                    null);
            return;
        }
        for (Map.Entry<String, String> field : view.values().entrySet()) {
            sink.row(
                    view.entity(),
                    view.key(),
                    view.date(),
                    view.status(),
                    view.versionNo(),
                    view.effectiveFrom(),
                    view.effectiveTo(),
                    view.approvalId(),
                    field.getKey(),
                    field.getValue());
        }
    }

    /**
     * The record behind {@code entity} / {@code key}, if the reader may see it: VIEW (or EXPORT) on
     * A-05, else on the record's own screen, at the record's office (a national record needs the
     * permission only). An unknown type or key is AUD-404-RECORD.
     */
    HistoryRecord resolve(AccessContext context, String entity, String key, String action) {
        RecordHistorySource source = entity == null ? null : sources.get(entity);
        String wanted = key == null ? "" : key.strip();
        if (source == null || wanted.isEmpty()) {
            throw new FciException(ApiErrorCode.AUDIT_RECORD_NOT_FOUND);
        }
        HistoryRecord record =
                source.find(wanted)
                        .orElseThrow(() -> new FciException(ApiErrorCode.AUDIT_RECORD_NOT_FOUND));
        String screen = policy.can(context, AUDIT_SCREEN, action) ? AUDIT_SCREEN : record.screen();
        // RBAC-SEC-141: a reader without the right gets the answer an unknown key gets, so the
        // response never says whether the record exists.
        if (!policy.can(context, screen, action)) {
            throw new FciException(ApiErrorCode.AUDIT_RECORD_NOT_FOUND);
        }
        if (record.locationId() != null) {
            policy.requireRecordAt(
                    context,
                    screen,
                    action,
                    record.locationId(),
                    ApiErrorCode.AUDIT_RECORD_NOT_FOUND);
        }
        return record;
    }

    private AsOfView asOf(HistoryRecord record, LocalDate date) {
        if (date == null) {
            throw new FciException(
                    in.gov.fci.payroll.platform.error.CommonErrorCode.VALIDATION_FAILED);
        }
        if (record.isVersioned()) {
            PointInTime.Result result = PointInTime.asOf(record.versions(), date);
            if (result instanceof PointInTime.Overlap o) {
                throw new FciException(
                        ApiErrorCode.AUDIT_VERSIONS_OVERLAP,
                        new Object[] {
                            o.versionNos().stream()
                                    .map(String::valueOf)
                                    .collect(Collectors.joining(", "))
                        },
                        List.of(),
                        null);
            }
            if (result instanceof PointInTime.Found f) {
                return new AsOfView(
                        record.entity(),
                        record.key(),
                        date,
                        "FOUND",
                        true,
                        f.versionNo(),
                        f.effectiveFrom(),
                        f.effectiveTo(),
                        f.approvalId(),
                        f.values());
            }
            return notInForce(record, date);
        }
        // SRS-20-3: before the row took effect there is nothing to rebuild (a seeded value has no
        // creation row in the trail, so the rebuild alone would show it as always there).
        if (record.existsFrom() != null && date.isBefore(record.existsFrom())) {
            return notInForce(record, date);
        }
        List<PointInTime.FieldChange> changes =
                audit.findByEntityNameAndEntityKeyIn(record.entity(), record.auditKeys()).stream()
                        .filter(e -> record.current().containsKey(e.getFieldName()))
                        .map(
                                e ->
                                        new PointInTime.FieldChange(
                                                e.getId(),
                                                e.getFieldName(),
                                                e.getOldValue(),
                                                e.getNewValue(),
                                                e.getEffectiveDate(),
                                                e.getChangedAt(),
                                                e.getChangeType() == ChangeType.INITIAL_VALUE))
                        .toList();
        return PointInTime.reconstruct(record.current(), changes, date)
                .map(
                        values ->
                                new AsOfView(
                                        record.entity(),
                                        record.key(),
                                        date,
                                        "FOUND",
                                        false,
                                        null,
                                        null,
                                        null,
                                        null,
                                        values))
                .orElseGet(() -> notInForce(record, date));
    }

    private static AsOfView notInForce(HistoryRecord record, LocalDate date) {
        return new AsOfView(
                record.entity(),
                record.key(),
                date,
                "NOT_IN_FORCE",
                record.isVersioned(),
                null,
                null,
                null,
                null,
                Map.of());
    }

    private Page<AuditEvent> rows(HistoryRecord record, Pageable page) {
        return audit.findByEntityNameAndEntityKeyInOrderByChangedAtAscIdAsc(
                record.entity(), record.auditKeys(), page);
    }

    /** X-04 fields from the approval behind each row and the users who acted. */
    List<HistoryRow> enrich(List<AuditEvent> events) {
        Set<Long> approvalIds =
                events.stream()
                        .map(AuditEvent::getApprovalId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        ApprovalOutcomes approvalSource = approvals.getIfAvailable();
        Map<Long, ApprovalOutcomes.Outcome> outcomes =
                approvalIds.isEmpty() || approvalSource == null
                        ? Map.of()
                        : approvalSource.outcomes(approvalIds);
        Set<String> logins = new HashSet<>();
        events.forEach(e -> logins.add(e.getChangedBy()));
        outcomes.values()
                .forEach(
                        o -> {
                            logins.add(o.maker());
                            logins.add(o.checker());
                        });
        logins.remove(null);
        ActorDirectory directory = actors.getIfAvailable();
        Map<String, ActorDirectory.Actor> people =
                logins.isEmpty() || directory == null ? Map.of() : directory.actors(logins);
        Map<String, Order> orders = orders(events);
        return events.stream()
                .map(
                        e -> {
                            ApprovalOutcomes.Outcome approval =
                                    e.getApprovalId() == null
                                            ? null
                                            : outcomes.get(e.getApprovalId());
                            String maker = approval == null ? e.getChangedBy() : approval.maker();
                            String checker = approval == null ? null : approval.checker();
                            return new HistoryRow(
                                    e.getId(),
                                    e.getChangedAt(),
                                    e.getEntityName() + " · " + e.getEntityKey(),
                                    e.getFieldName(),
                                    e.getOldValue(),
                                    e.getNewValue(),
                                    AuditOutcome.of(e.getFieldName(), e.getNewValue()),
                                    // audit_event.change_type is NOT NULL (DEFAULT 'CHANGE').
                                    e.getChangeType().name(),
                                    e.getEffectiveDate(),
                                    e.getReason(),
                                    e.getApprovalId(),
                                    approval == null ? null : approval.status(),
                                    person(maker, people),
                                    person(checker, people),
                                    e.getSourceIp(),
                                    e.getModuleCode(),
                                    "NATIVE",
                                    orders.get(e.getEntityName() + "|" + e.getEntityKey()));
                        })
                .toList();
    }

    /**
     * SRS l.1248, SRS-G-2: the order of each changed record, read from its stored documents in one
     * query per entity (P rules). The newest active document with a reference number wins; a record
     * with none shows no order, which the SRS allows ("where the change carries an order").
     */
    private Map<String, Order> orders(List<AuditEvent> events) {
        AttachmentPresence store = documents.getIfAvailable();
        if (store == null) {
            return Map.of();
        }
        Map<String, Set<String>> keys = new TreeMap<>();
        events.forEach(
                e ->
                        keys.computeIfAbsent(e.getEntityName(), n -> new HashSet<>())
                                .add(e.getEntityKey()));
        Map<String, Order> found = new HashMap<>();
        keys.forEach(
                (entity, ofEntity) ->
                        store.filesOf(entity, ofEntity)
                                .forEach(
                                        (key, files) ->
                                                files.stream()
                                                        .filter(f -> f.referenceNumber() != null)
                                                        .reduce((older, newer) -> newer)
                                                        .ifPresent(
                                                                f ->
                                                                        found.put(
                                                                                entity + "|" + key,
                                                                                new Order(
                                                                                        f
                                                                                                .referenceNumber(),
                                                                                        f.id())))));
        return found;
    }

    private static Person person(String login, Map<String, ActorDirectory.Actor> people) {
        if (login == null) {
            return null;
        }
        ActorDirectory.Actor actor = people.get(login);
        return actor == null
                ? new Person(login, null, null)
                : new Person(login, actor.name(), actor.employeeNumber());
    }

    private static String name(Person person) {
        if (person == null) {
            return null;
        }
        return person.name() == null
                ? person.loginId()
                : person.name() + " (" + person.loginId() + ")";
    }
}
