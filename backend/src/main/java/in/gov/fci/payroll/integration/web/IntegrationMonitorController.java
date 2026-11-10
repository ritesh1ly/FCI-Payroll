package in.gov.fci.payroll.integration.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.integration.orchestration.HrmsApiCatalogService;
import in.gov.fci.payroll.integration.orchestration.HrmsConsoleService;
import in.gov.fci.payroll.integration.orchestration.MonitorService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * X-03 Integration Monitor API (plan 23 phase 23b; SRS-INT-6, SRS-INT-16, SRS-INT-17, SRS-INT-31 …
 * SRS-INT-33; the paths are designed here, PENDING API-23-7):
 *
 * <pre>
 * GET  /v1/integration/summary                          the figures                X-03 VIEW
 * GET  /v1/integration/interfaces                       every interface, paged      X-03 VIEW
 * GET  /v1/integration/interfaces/{code}                its contract (spec held)    X-03 EDIT
 * GET  /v1/integration/hrms-apis                       HRMS Postman APIs, paged    X-03 VIEW
 *        (class, direction, configured, status, days; read-only: URLs are settings)
 * GET  /v1/integration/windows                          exchange windows, paged     X-03 VIEW
 * GET  /v1/integration/exceptions                       the exception queue, paged  X-03 VIEW
 * POST /v1/integration/exceptions/{id}/reprocess        Reprocess Failed            X-03 EDIT
 * POST /v1/integration/exceptions/{id}/write-off        Write off                   X-03 EDIT
 * POST /v1/integration/interfaces/{code}/test-connection Test connection            X-03 EDIT
 * POST /v1/integration/hrms-apis/{code}/call            HRMS API console (Administrator) X-03 EDIT
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class IntegrationMonitorController {

    private static final String CODE = "^[A-Z0-9_]{1,40}$";
    private static final String API_CODE = "^[a-z0-9_]{1,60}$";

    /** The Administrator's mandatory reason. */
    public record ReasonBody(@NotBlank @Size(max = 1000) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final MonitorService monitor;
    private final HrmsApiCatalogService hrmsApis;
    private final Paging paging;
    private final ExportStreaming exports;
    private final HrmsConsoleService console;

    public IntegrationMonitorController(
            CurrentAccess contexts,
            MonitorService monitor,
            HrmsApiCatalogService hrmsApis,
            Paging paging,
            ExportStreaming exports,
            HrmsConsoleService console) {
        this.console = console;
        this.exports = exports;
        this.contexts = contexts;
        this.monitor = monitor;
        this.hrmsApis = hrmsApis;
        this.paging = paging;
    }

    @GetMapping("/v1/integration/summary")
    @PreAuthorize("@authz.gate('X-03','VIEW')")
    public ResponseEntity<MonitorService.Summary> summary() {
        return noStore(monitor.summary(contexts.current()));
    }

    @GetMapping("/v1/integration/interfaces")
    @PreAuthorize("@authz.gate('X-03','VIEW')")
    public ResponseEntity<Paged<MonitorService.InterfaceRow>> interfaces(
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z_]{0,30}$") String counterparty,
            @RequestParam(required = false) @Pattern(regexp = "^(HRMS|ENTERPRISE)$") String scope,
            @RequestParam(required = false) @Pattern(regexp = "^(OK|PARTIAL|FAILED)$")
                    String status,
            @RequestParam(required = false)
                    @jakarta.validation.constraints.Size(max = 60)
                    @in.gov.fci.payroll.platform.validation.PrintableAscii
                    String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                monitor.interfaces(
                        contexts.current(),
                        counterparty,
                        scope,
                        status,
                        q,
                        paging.resolve(page, size)));
    }

    @GetMapping("/v1/integration/interfaces/{code}")
    @PreAuthorize("@authz.gate('X-03','EDIT')")
    public ResponseEntity<MonitorService.Contract> contract(
            @PathVariable @Pattern(regexp = CODE) String code) {
        return noStore(monitor.contract(contexts.current(), code));
    }

    /**
     * The HRMS APIs of the Postman collection with their configuration and activity (owner
     * 2026-11-05; SRS-INT-6, SRS-INT-16, SRS-INT-32). Read-only; a URL shows as its path only.
     */
    @GetMapping("/v1/integration/hrms-apis")
    @PreAuthorize("@authz.gate('X-03','VIEW')")
    public ResponseEntity<Paged<HrmsApiCatalogService.Row>> hrmsApis(
            @RequestParam(name = "class", required = false)
                    @Pattern(regexp = "^(REQUIRED|RULE_PENDING)?$")
                    String selectionClass,
            @RequestParam(required = false) @Pattern(regexp = "^(INBOUND|OUTBOUND)?$")
                    String direction,
            @RequestParam(required = false) Boolean configured,
            @RequestParam(required = false) @Pattern(regexp = "^(OK|PARTIAL|FAILED|NONE)?$")
                    String status,
            @RequestParam(required = false)
                    @jakarta.validation.constraints.Min(1)
                    @jakarta.validation.constraints.Max(90)
                    Integer days,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                hrmsApis.list(
                        contexts.current(),
                        blank(selectionClass),
                        blank(direction),
                        configured,
                        blank(status),
                        days,
                        paging.resolve(page, size)));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @GetMapping("/v1/integration/windows")
    @PreAuthorize("@authz.gate('X-03','VIEW')")
    public ResponseEntity<Paged<MonitorService.Window>> windows(
            @RequestParam(name = "interface", required = false)
                    @Pattern(regexp = "^[A-Z0-9_]{0,40}$")
                    String code,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(monitor.windows(contexts.current(), code, paging.resolve(page, size)));
    }

    /** FRS V0.5 "Integration Audit Trail": the envelope log as a file (X-03 EXPORT). */
    @GetMapping("/v1/integration/windows/export")
    @PreAuthorize("@authz.gate('X-03','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportAuditTrail(
            @RequestParam(name = "interface", required = false)
                    @Pattern(regexp = "^[A-Z0-9_]{0,40}$")
                    String code,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(monitor.auditTrailSize(context, code));
        return exports.stream(
                "integration-audit-trail",
                format,
                sink -> monitor.exportAuditTrail(context, code, sink));
    }

    @GetMapping("/v1/integration/exceptions")
    @PreAuthorize("@authz.gate('X-03','VIEW')")
    public ResponseEntity<Paged<MonitorService.QueueItem>> exceptions(
            @RequestParam(defaultValue = "true") boolean open,
            @RequestParam(name = "interface", required = false)
                    @Pattern(regexp = "^[A-Z0-9_]{0,40}$")
                    String code,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(monitor.queue(contexts.current(), open, code, paging.resolve(page, size)));
    }

    @PostMapping("/v1/integration/exceptions/{id}/reprocess")
    @PreAuthorize("@authz.gate('X-03','EDIT')")
    public ResponseEntity<MonitorService.QueueItem> reprocess(
            @PathVariable long id, @Valid @RequestBody ReasonBody body) {
        return noStore(monitor.reprocess(contexts.current(), id, body.reason()));
    }

    /**
     * Reprocess Failed for one exchange window (X-03 l.5172; SRS-INT-33): the Administrator only,
     * with one mandatory reason for every open item of the window. The API is not in the sources
     * (designed here, behind MonitorService).
     */
    @PostMapping("/v1/integration/windows/{runId}/reprocess")
    @PreAuthorize("@authz.gate('X-03','EDIT')")
    public ResponseEntity<MonitorService.WindowReprocess> reprocessWindow(
            @PathVariable long runId, @Valid @RequestBody ReasonBody body) {
        return noStore(monitor.reprocessWindow(contexts.current(), runId, body.reason()));
    }

    @PostMapping("/v1/integration/exceptions/{id}/write-off")
    @PreAuthorize("@authz.gate('X-03','EDIT')")
    public ResponseEntity<MonitorService.QueueItem> writeOff(
            @PathVariable long id, @Valid @RequestBody ReasonBody body) {
        return noStore(monitor.writeOff(contexts.current(), id, body.reason()));
    }

    @PostMapping("/v1/integration/interfaces/{code}/test-connection")
    @PreAuthorize("@authz.gate('X-03','EDIT')")
    public ResponseEntity<MonitorService.Outcome> testConnection(
            @PathVariable @Pattern(regexp = CODE) String code) {
        return noStore(monitor.testConnection(contexts.current(), code));
    }

    /** HRMS API console (SRS-INT-33): the Administrator calls an outbound HRMS API. */
    @PostMapping("/v1/integration/hrms-apis/{code}/call")
    @PreAuthorize("@authz.gate('X-03','EDIT')")
    public ResponseEntity<HrmsConsoleService.CallResult> callHrmsApi(
            @PathVariable @Pattern(regexp = API_CODE) String code,
            @Valid @RequestBody HrmsConsoleService.CallRequest body) {
        return noStore(console.call(contexts.current(), code, body));
    }
}
