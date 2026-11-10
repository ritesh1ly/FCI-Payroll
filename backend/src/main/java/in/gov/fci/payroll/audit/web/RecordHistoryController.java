package in.gov.fci.payroll.audit.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.audit.orchestration.HistoryGuard;
import in.gov.fci.payroll.audit.orchestration.LegacyAuditService;
import in.gov.fci.payroll.audit.orchestration.RecordHistoryService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * X-04 Audit Trail &amp; Change History (screen A-05) and the History button of every master screen
 * (SRS-20-3, SRS-20-6, SRS-20-20 … 24, SRS-20-29). Read only: there is no create, change or delete
 * endpoint for audit or Legacy Audit records - such a request is refused and logged as a security
 * event (SRS-20-19, SRS-20-21). The screen-level permission depends on the record (A-05, or the
 * record's own screen for its History button), so it is checked in the services.
 */
@RestController
@Profile("online")
@PreAuthorize("isAuthenticated()")
public class RecordHistoryController {

    /** {@code audit_event.entity_name} of a record type. */
    private static final String ENTITY = "^[a-z][a-z0-9_]{1,99}$";

    private final CurrentAccess contexts;
    private final RecordHistoryService history;
    private final LegacyAuditService legacy;
    private final HistoryGuard guard;
    private final ExportStreaming exports;
    private final Paging paging;

    public RecordHistoryController(
            CurrentAccess contexts,
            RecordHistoryService history,
            LegacyAuditService legacy,
            HistoryGuard guard,
            ExportStreaming exports,
            Paging paging) {
        this.paging = paging;
        this.contexts = contexts;
        this.history = history;
        this.legacy = legacy;
        this.guard = guard;
        this.exports = exports;
    }

    /** The record types X-04 can enquire on. */
    @GetMapping("/v1/audit/record-types")
    public ResponseEntity<List<String>> recordTypes() {
        return noStore(history.types(contexts.current()));
    }

    /** SRS-20-29: one record's native change history. */
    @GetMapping("/v1/audit/history")
    public ResponseEntity<RecordHistoryService.RecordView> recordHistory(
            @RequestParam @Pattern(regexp = ENTITY) String entity,
            @RequestParam @Size(min = 1, max = 100) String key,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageSpec spec = paging.resolve(page, size);
        return noStore(history.history(contexts.current(), entity, key, spec.page(), spec.size()));
    }

    /** SRS-20-3 / SRS-20-6: the record as it stood on a date. */
    @GetMapping("/v1/audit/history/as-of")
    public ResponseEntity<RecordHistoryService.AsOfView> asOf(
            @RequestParam @Pattern(regexp = ENTITY) String entity,
            @RequestParam @Size(min = 1, max = 100) String key,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return noStore(history.asOf(contexts.current(), entity, key, date));
    }

    /** SRS-20-29 Change History Report. */
    @GetMapping("/v1/audit/history/export")
    public ResponseEntity<StreamingResponseBody> exportHistory(
            @RequestParam @Pattern(regexp = ENTITY) String entity,
            @RequestParam @Size(min = 1, max = 100) String key,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(history.historySize(context, entity, key));
        return exports.stream(
                "change-history",
                format,
                sink -> history.exportHistory(context, entity, key, sink));
    }

    /** SRS-20-29 Point-in-Time Reconstruction Extract. */
    @GetMapping("/v1/audit/history/as-of/export")
    public ResponseEntity<StreamingResponseBody> exportAsOf(
            @RequestParam @Pattern(regexp = ENTITY) String entity,
            @RequestParam @Size(min = 1, max = 100) String key,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        // Resolves and authorises before streaming: an unknown record is a 404, not an empty file.
        history.asOf(context, entity, key, date);
        return exports.stream(
                "point-in-time",
                format,
                sink -> history.exportAsOf(context, entity, key, date, sink));
    }

    /** SRS-20-23: the record's Legacy Audit rows (the enquiry is logged, SRS-20-24). */
    @GetMapping("/v1/audit/legacy")
    public ResponseEntity<LegacyAuditService.LegacyPage> legacyAudit(
            @RequestParam @Pattern(regexp = ENTITY) String entity,
            @RequestParam @Size(min = 1, max = 100) String key,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageSpec spec = paging.resolve(page, size);
        return noStore(legacy.list(contexts.current(), entity, key, spec.page(), spec.size()));
    }

    /** SRS-20-23 Export of the Legacy Audit view. */
    @GetMapping("/v1/audit/legacy/export")
    public ResponseEntity<StreamingResponseBody> exportLegacy(
            @RequestParam @Pattern(regexp = ENTITY) String entity,
            @RequestParam @Size(min = 1, max = 100) String key,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(legacy.exportSize(context, entity, key));
        return exports.stream(
                "legacy-audit", format, sink -> legacy.export(context, entity, key, sink));
    }

    /**
     * SRS-20-19 / SRS-20-21: any create, change or delete of an audit or Legacy Audit record -
     * refused for every role, System Administrator included, and logged as a security event.
     */
    @RequestMapping(
            path = {"/v1/audit", "/v1/audit/**", "/v1/admin/audit", "/v1/admin/audit/**"},
            method = {
                RequestMethod.POST,
                RequestMethod.PUT,
                RequestMethod.PATCH,
                RequestMethod.DELETE
            })
    public ResponseEntity<Void> refuseHistoryChange(HttpServletRequest request) {
        guard.refuseWrite(
                contexts.current().loginId(), request.getMethod(), request.getRequestURI());
        // Unreachable: refuseWrite always throws AUD-405-IMMUTABLE.
        return ResponseEntity.status(405).build();
    }
}
