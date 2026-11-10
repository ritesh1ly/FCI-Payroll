package in.gov.fci.payroll.audit.orchestration;

import in.gov.fci.payroll.audit.api.ActorDirectory;
import in.gov.fci.payroll.audit.business.AuditObjectGroups;
import in.gov.fci.payroll.audit.business.model.AuditEvent;
import in.gov.fci.payroll.audit.business.model.RetentionClass;
import in.gov.fci.payroll.audit.persistence.AuditRepositories;
import in.gov.fci.payroll.platform.approval.AttachmentPresence;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.Likes;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A-05 Audit Trail: read-only search over {@code audit_event} within a bounded window, and CSV
 * export (itself audited), limited to the session's data scope outside Headquarters (V023). Rows
 * are append-only (database trigger); nothing here can change them.
 */
@Service
@Loggable
public class AuditQueryService {

    /**
     * The search. {@code objectGroup} and {@code userGroup} are codes of the M-16 lists
     * AUDIT_OBJECT_GROUP and AUDIT_USER_GROUP, {@code term} the drawn "Search in list".
     */
    public record Filter(
            Instant from,
            Instant to,
            String entity,
            String actor,
            String field,
            String objectGroup,
            String userGroup,
            String term) {
        public Filter(Instant from, Instant to, String entity, String actor, String field) {
            this(from, to, entity, actor, field, null, null, null);
        }
    }

    public record Row(
            Long id,
            Instant changedAt,
            String changedBy,
            String entityName,
            String entityKey,
            String fieldName,
            String oldValue,
            String newValue,
            String reason,
            Long approvalId,
            String changeType,
            String moduleCode,
            String sourceIp,
            LocalDate effectiveDate,
            String outcome,
            String approvalStatus,
            RecordHistoryService.Person maker,
            RecordHistoryService.Person checker,
            RecordHistoryService.Order order) {}

    public record PageView(List<Row> items, int page, int size, long total) {}

    /**
     * X-04 tiles, within the reader's scope. {@code legacyRows} is null outside Headquarters: the
     * Legacy Audit table has no office to count by, so the drawn "Full record" stands there.
     */
    public record Summary(
            long rowsThisCycle, Long legacyRows, Integer retentionMonths, boolean retentionSet) {}

    /** The System user group: the actors the application itself writes as. */
    private static final List<String> SYSTEM_ACTORS = List.of("SYSTEM", "system", "batch");

    private static final String USER_GROUP_SYSTEM = "SYSTEM";
    private static final String NATIVE_RETENTION = "NATIVE_AUDIT";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final AuditRepositories.Audit audit;
    private final Authorization policy;
    private final ApiProperties properties;

    private final ExportReader exportReader;
    private final RecordHistoryService history;
    private final AuditRepositories.LegacyAudit legacy;
    private final AuditRepositories.RetentionClasses retention;
    private final ObjectProvider<ActorDirectory> actors;
    private final ObjectProvider<AttachmentPresence> documents;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public AuditQueryService(
            ExportReader exportReader,
            AuditRepositories.Audit audit,
            Authorization policy,
            ApiProperties properties,
            RecordHistoryService history,
            AuditRepositories.LegacyAudit legacy,
            AuditRepositories.RetentionClasses retention,
            ObjectProvider<ActorDirectory> actors,
            ObjectProvider<AttachmentPresence> documents,
            Clock clock) {
        this.exportReader = exportReader;
        this.history = history;
        this.legacy = legacy;
        this.retention = retention;
        this.actors = actors;
        this.documents = documents;
        this.clock = clock;
        this.audit = audit;
        this.policy = policy;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public PageView search(AccessContext context, Filter filter, int page, int size) {
        policy.require(context, "A-05", "VIEW");
        checkWindow(filter);
        Page<AuditEvent> found =
                query(
                        context,
                        filter,
                        PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));
        List<RecordHistoryService.HistoryRow> details = history.enrich(found.getContent());
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < found.getContent().size(); i++) {
            rows.add(row(found.getContent().get(i), details.get(i)));
        }
        return new PageView(rows, found.getNumber(), found.getSize(), found.getTotalElements());
    }

    /** Rows the audit export would write (the size check before streaming). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context, Filter filter) {
        policy.require(context, "A-05", "EXPORT");
        checkWindow(filter);
        return exportReader.count(page -> query(context, filter, page));
    }

    /**
     * A-05 export (SRS-G-13, SRS-G-2): every audit row of the filter in the session's scope, newest
     * first as on screen (id breaks ties), uncapped within the export limit the controller checks,
     * in one read-only snapshot; the rows written are checked against the snapshot's count. The
     * export itself is logged by the export platform (SRS-20-24, {@code ExportLog}).
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(AccessContext context, Filter filter, ExportSink sink) throws IOException {
        policy.require(context, "A-05", "EXPORT");
        checkWindow(filter);
        sink.header(
                "id",
                "changed_at",
                "changed_by",
                "entity",
                "entity_key",
                "field",
                "old_value",
                "new_value",
                "reason",
                "approval_id",
                "change_type",
                "module",
                "effective_date",
                "ip_address",
                "outcome",
                "maker",
                "checker",
                "order_reference");
        long expected =
                exportReader.readAll(
                        page -> query(context, filter, page),
                        rows -> {
                            List<RecordHistoryService.HistoryRow> details = history.enrich(rows);
                            for (int i = 0; i < rows.size(); i++) {
                                AuditEvent e = rows.get(i);
                                RecordHistoryService.HistoryRow d = details.get(i);
                                sink.row(
                                        e.getId(),
                                        e.getChangedAt(),
                                        e.getChangedBy(),
                                        e.getEntityName(),
                                        e.getEntityKey(),
                                        e.getFieldName(),
                                        e.getOldValue(),
                                        e.getNewValue(),
                                        e.getReason(),
                                        e.getApprovalId(),
                                        e.getChangeType() == null ? null : e.getChangeType().name(),
                                        e.getModuleCode(),
                                        e.getEffectiveDate(),
                                        e.getSourceIp(),
                                        d.outcome(),
                                        login(d.maker()),
                                        login(d.checker()),
                                        d.order() == null ? null : d.order().number());
                            }
                        });
        ExportSink.requireComplete(expected, sink);
    }

    private org.springframework.data.domain.Page<AuditEvent> query(
            AccessContext context, Filter filter, org.springframework.data.domain.Pageable page) {
        List<String> entities = entitiesOf(filter.objectGroup());
        List<String> users = usersOf(filter.userGroup());
        return audit.search(
                filter.from(),
                filter.to(),
                contains(filter.entity()),
                contains(filter.actor()),
                contains(filter.field()),
                contains(filter.term()),
                entities == null ? 0 : 1,
                entities == null ? NOBODY : entities,
                users == null ? 0 : 1,
                users == null ? NOBODY : users,
                scoped(context),
                scope(context),
                page);
    }

    /** Bound where a list filter is off, or holds nobody: {@code in ()} is no valid SQL. */
    private static final List<String> NOBODY = List.of("-");

    /** The entities of a drawn object group; a code the build does not know is refused. */
    private static List<String> entitiesOf(String group) {
        String code = blank(group);
        if (code == null) {
            return null;
        }
        List<String> entities = AuditObjectGroups.entities(code);
        if (entities == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        return entities;
    }

    /** The logins of a drawn user group, or {@link #NOBODY} when nobody holds it. */
    private List<String> usersOf(String group) {
        String code = blank(group);
        if (code == null) {
            return null;
        }
        if (USER_GROUP_SYSTEM.equals(code)) {
            return SYSTEM_ACTORS;
        }
        if (!List.of("MAKER", "CHECKER", "ADMINISTRATOR").contains(code)) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        ActorDirectory directory = actors.getIfAvailable();
        List<String> logins = directory == null ? List.of() : List.copyOf(directory.loginsOf(code));
        return logins.isEmpty() ? NOBODY : logins;
    }

    /**
     * X-04 tiles. The cycle is the calendar month of today in India, counted with the same scope as
     * the search; retention is read here on A-05 VIEW (the setting itself stays on A-07), and is
     * "set" only when FCI has confirmed a period (SRS-20-5, OQ-10).
     */
    @Transactional(readOnly = true)
    public Summary summary(AccessContext context) {
        policy.require(context, "A-05", "VIEW");
        Instant now = clock.instant();
        Instant monthStart =
                now.atZone(INDIA).toLocalDate().withDayOfMonth(1).atStartOfDay(INDIA).toInstant();
        long rows = audit.countInWindow(monthStart, now, scoped(context), scope(context));
        Long legacyRows = policy.isAtHeadquarters(context) ? legacy.count() : null;
        Optional<RetentionClass> period = retention.findByCode(NATIVE_RETENTION);
        boolean set = period.filter(RetentionClass::isConfirmed).isPresent();
        return new Summary(
                rows, legacyRows, period.map(RetentionClass::getMonths).orElse(null), set);
    }

    /**
     * SRS l.1248: the order behind an audit row, as a link. The row must be one the reader's search
     * would return (the same scope), and the document one of that record; each read is logged by
     * the document store (OC-7). Anything else reads as not found.
     */
    @Transactional
    public AttachmentPresence.FileContent orderDocument(
            AccessContext context, String sourceIp, long auditId, long documentId) {
        policy.require(context, "A-05", "VIEW");
        AuditEvent row =
                audit.findById(auditId)
                        .filter(e -> visible(context, e))
                        .orElseThrow(() -> new FciException(ApiErrorCode.AUDIT_RECORD_NOT_FOUND));
        AttachmentPresence store = documents.getIfAvailable();
        if (store == null) {
            throw new FciException(ApiErrorCode.AUDIT_RECORD_NOT_FOUND);
        }
        return store.content(
                context.loginId(), sourceIp, row.getEntityName(), row.getEntityKey(), documentId);
    }

    private boolean visible(AccessContext context, AuditEvent row) {
        return policy.isAtHeadquarters(context)
                || (row.getLocationId() != null && scope(context).contains(row.getLocationId()));
    }

    private static String login(RecordHistoryService.Person person) {
        return person == null ? null : person.loginId();
    }

    /**
     * Headquarters sees the national trail; any other office only the rows recorded within its data
     * scope (rows without an office - sign-in attempts, batch jobs, rows before V023 - are
     * national).
     */
    private int scoped(AccessContext context) {
        return policy.isAtHeadquarters(context) ? 0 : 1;
    }

    private static Collection<Long> scope(AccessContext context) {
        return OfficeScope.ids(context);
    }

    private void checkWindow(Filter filter) {
        // An empty or reversed range has its own message; "too large" is only for too large.
        if (filter.from() == null || filter.to() == null || !filter.to().isAfter(filter.from())) {
            throw new FciException(ApiErrorCode.AUDIT_RANGE);
        }
        if (Duration.between(filter.from(), filter.to()).toDays()
                > properties.audit().maxQueryWindowDays()) {
            throw new FciException(
                    ApiErrorCode.AUDIT_WINDOW,
                    new Object[] {properties.audit().maxQueryWindowDays()},
                    List.of(),
                    null);
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * SRS l.1226 (US-03-06): a search term as a case-insensitive "contains" pattern - lower-cased
     * (locale ROOT), its LIKE wildcards escaped ({@link Likes}), so {@code app_user} matches only
     * itself; null when the term is blank.
     */
    static String contains(String value) {
        String term = blank(value);
        return term == null ? null : "%" + Likes.escape(term.toLowerCase(Locale.ROOT)) + "%";
    }

    /** RFC 4180 quoting plus a leading quote against spreadsheet formula injection. */
    static String cell(String value) {
        return in.gov.fci.payroll.platform.export.Csv.cell(value);
    }

    private static Row row(AuditEvent e, RecordHistoryService.HistoryRow detail) {
        return new Row(
                e.getId(),
                e.getChangedAt(),
                e.getChangedBy(),
                e.getEntityName(),
                e.getEntityKey(),
                e.getFieldName(),
                e.getOldValue(),
                e.getNewValue(),
                e.getReason(),
                e.getApprovalId(),
                e.getChangeType() == null ? null : e.getChangeType().name(),
                e.getModuleCode(),
                e.getSourceIp(),
                e.getEffectiveDate(),
                detail.outcome(),
                detail.approvalStatus(),
                detail.maker(),
                detail.checker(),
                detail.order());
    }
}
