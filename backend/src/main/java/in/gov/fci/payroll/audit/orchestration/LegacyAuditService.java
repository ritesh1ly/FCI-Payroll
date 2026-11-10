package in.gov.fci.payroll.audit.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.audit.business.model.LegacyAuditRecord;
import in.gov.fci.payroll.audit.persistence.AuditRepositories;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Legacy Audit view of X-04 (SRS-20-20 … 24, BR-AUDIT-008): the migrated legacy audit rows of
 * the selected record, beside the native trail and never merged into it (SRS-20-22), filtered by
 * the reader's Access Group scope (SRS-20-23), read only for every role including the System
 * Administrator (SRS-20-21). Every enquiry is logged with the requester, the scope and the time
 * (SRS-20-24); exports are logged by the export platform.
 */
@Service
@Loggable
public class LegacyAuditService {

    /** {@code audit_event.entity_name} of the enquiry log rows. */
    static final String ENTITY = "legacy_audit";

    /**
     * One Legacy Audit row as held in legacy. A ROW_IMAGE row is a snapshot of the whole legacy
     * row, never split into field-level before / after (SC-03-0039).
     */
    public record LegacyRow(
            Long id,
            String origin,
            String sourceObject,
            String sourceKey,
            String grain,
            String actionCode,
            String fieldName,
            String oldValue,
            String newValue,
            String rowImage,
            String changedFields,
            String changedBy,
            Instant changedAt,
            String reason) {}

    public record LegacyPage(
            String entity, String key, List<LegacyRow> items, int page, int size, long total) {}

    private final RecordHistoryService records;
    private final AuditRepositories.LegacyAudit legacy;
    private final Authorization policy;
    private final ExportReader exportReader;
    private final AuditService auditWriter;

    public LegacyAuditService(
            RecordHistoryService records,
            AuditRepositories.LegacyAudit legacy,
            Authorization policy,
            ExportReader exportReader,
            AuditService auditWriter) {
        this.records = records;
        this.legacy = legacy;
        this.policy = policy;
        this.exportReader = exportReader;
        this.auditWriter = auditWriter;
    }

    /** SRS-20-23: one page of the record's legacy rows, oldest first; the enquiry is logged. */
    @Transactional(readOnly = true)
    public LegacyPage list(AccessContext context, String entity, String key, int page, int size) {
        HistoryRecord record = record(context, entity, key, "VIEW");
        Page<LegacyAuditRecord> found =
                rows(
                        context,
                        record,
                        PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));
        // SRS-20-24: logged in its own transaction - an enquiry is recorded even though it reads.
        auditWriter.recordIndependently(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                logKey(record),
                                "ENQUIRY",
                                null,
                                scope(context),
                                "Legacy Audit enquiry")
                        .module(RecordHistoryService.AUDIT_SCREEN));
        return new LegacyPage(
                record.entity(),
                record.key(),
                found.getContent().stream().map(LegacyAuditService::row).toList(),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements());
    }

    /** Rows the legacy export would write (the size check before streaming). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context, String entity, String key) {
        HistoryRecord record = record(context, entity, key, "EXPORT");
        return exportReader.count(p -> rows(context, record, p));
    }

    /**
     * SRS-20-23 Export: every legacy row of the record in the reader's scope, oldest first, in one
     * read-only snapshot, checked against the snapshot's count; Origin LEGACY on every row
     * (SRS-20-22: distinguishable in every export).
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(AccessContext context, String entity, String key, ExportSink sink)
            throws IOException {
        HistoryRecord record = record(context, entity, key, "EXPORT");
        sink.header(
                "origin",
                "legacy_source_table",
                "legacy_key",
                "grain",
                "action",
                "field",
                "old_value",
                "new_value",
                "values_as_held",
                "changed_fields",
                "legacy_user",
                "legacy_date",
                "legacy_reason");
        long expected =
                exportReader.readAll(
                        p -> rows(context, record, p),
                        batch -> {
                            for (LegacyAuditRecord r : batch) {
                                sink.row(
                                        "LEGACY",
                                        r.getSourceObject(),
                                        r.getSourceKey(),
                                        r.getGrain(),
                                        r.getActionCode(),
                                        r.getFieldName(),
                                        r.getOldValue(),
                                        r.getNewValue(),
                                        r.getRowImage(),
                                        r.getChangedFields(),
                                        r.getChangedBy(),
                                        r.getChangedAt(),
                                        r.getReason());
                            }
                        });
        ExportSink.requireComplete(expected, sink);
    }

    /** The record, reached through A-05 only (the Legacy Audit view is part of X-04). */
    private HistoryRecord record(AccessContext context, String entity, String key, String action) {
        policy.require(context, RecordHistoryService.AUDIT_SCREEN, action);
        return records.resolve(context, entity, key, action);
    }

    /**
     * The record's legacy rows; a row without a resolved member is national and shown at
     * Headquarters only (plan decision D-3).
     */
    private Page<LegacyAuditRecord> rows(
            AccessContext context, HistoryRecord record, Pageable page) {
        return legacy.forRecord(
                record.entity(), record.key(), policy.isAtHeadquarters(context), page);
    }

    private static String logKey(HistoryRecord record) {
        String key = record.entity() + "/" + record.key();
        return key.length() > 100 ? key.substring(0, 100) : key;
    }

    /** SRS-20-24 "scope": the office and access groups the requester acted in. */
    static String scope(AccessContext context) {
        String groups =
                context.groups() == null
                        ? ""
                        : context.groups().stream()
                                .map(ActiveGroup::code)
                                .collect(Collectors.joining(","));
        return "office=" + context.locationId() + ";groups=" + groups;
    }

    private static LegacyRow row(LegacyAuditRecord r) {
        return new LegacyRow(
                r.getId(),
                "LEGACY",
                r.getSourceObject(),
                r.getSourceKey(),
                r.getGrain(),
                r.getActionCode(),
                r.getFieldName(),
                r.getOldValue(),
                r.getNewValue(),
                r.getRowImage(),
                r.getChangedFields(),
                r.getChangedBy(),
                r.getChangedAt(),
                r.getReason());
    }
}
