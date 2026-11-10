package in.gov.fci.payroll.reporting.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.reporting.business.ReportModelRules;
import in.gov.fci.payroll.reporting.business.ReportRules.Level;
import in.gov.fci.payroll.reporting.orchestration.ComponentSummaryService;
import in.gov.fci.payroll.reporting.orchestration.ReportCatalogueService;
import in.gov.fci.payroll.reporting.orchestration.ReportVersionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
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
 * O-04 MIS &amp; Statutory Reports and M-12 Report Catalogue (plan 15; SRS-18-9, SRS-18-10,
 * SRS-18-18, SRS-18-24, SRS-18-28). The SRS names the screens, not the API (PENDING API-15-1).
 *
 * <pre>
 * GET  /v1/reports/catalogue                       the catalogue with statuses     O-04 VIEW
 * GET  /v1/reports/component-summary?yymm=&amp;level=   the summary on screen      O-04 VIEW
 *      (&amp;officeId, payrollType, category, memberStatus: SRS-18-12 (a), US-GAP-37)
 * GET  /v1/reports/component-summary/options?yymm= payroll types, categories       O-04 VIEW
 * GET  /v1/reports/component-summary/latest-locked the default month (SRS l.3501)  O-04 VIEW
 * GET  /v1/reports/component-summary/export        the summary as a file, logged   O-04 EXPORT
 *      (the same parameters and lang=en|hi: SRS-18-12 (c), (d), SRS-G-40)
 * GET  /v1/report-definitions/{key}/versions       a definition's versions         M-12 VIEW
 * POST /v1/report-definitions/{key}/versions       a new version for approval      M-12 CREATE (202)
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class ReportController {

    private static final String YYMM = "^\\d{4}(0[1-9]|1[0-2])$";
    private static final String KEY = "^[A-Z0-9_]{1,60}$";
    private static final String CODE = "^[A-Z0-9_]{1,30}$";

    /** An employee number (SRS-18-12 member range). */
    private static final String EIS = "^[A-Za-z0-9-]{1,20}$";

    /** A proposed version (M-12). */
    public record VersionBody(
            @Pattern(regexp = "^(PDF|EXCEL|CSV|PRESCRIBED_FILE|HTML)$") String layoutFormat,
            boolean periodLockRequired,
            @NotNull @Pattern(regexp = "^(STORE|REGENERATE)$") String storeMode,
            boolean blocked,
            @Size(max = 500) @PrintableAscii String blockedReason,
            @NotNull LocalDate effectiveFrom,
            @Size(max = 100) @PrintableAscii String roundingRuleKey,
            @Size(max = 50) List<@Valid ParameterBody> parameters,
            @Size(max = 200) List<@Valid FieldBody> fields,
            @Size(max = 20) List<@Valid ControlBody> controls,
            @Size(max = 20) List<@Valid DistributionBody> distribution) {}

    /** SRS-18-9: one parameter of the set, with its own validation rule. */
    public record ParameterBody(
            @Size(max = 40) @PrintableAscii String code,
            @Size(max = 200) @PrintableAscii String label,
            @Size(max = 20) @PrintableAscii String dataType,
            boolean mandatory,
            boolean roleScoped,
            @Size(max = 300) @PrintableAscii String defaultExpression,
            @Size(max = 500) @PrintableAscii String validationRule) {}

    /** SRS-18-9: one field bound to the component catalogue or the field-source model. */
    public record FieldBody(
            @Size(max = 200) @PrintableAscii String label,
            ReportModelRules.Binding binding,
            @Size(max = 1000) @PrintableAscii String reference,
            @Size(max = 60) @PrintableAscii String formatMask) {}

    /** M-12: one control total, reconciled at run time (SRS-18-8). */
    public record ControlBody(
            @Size(max = 200) @PrintableAscii String label,
            @Size(max = 40) @PrintableAscii String reconcilesAgainst,
            BigDecimal tolerance,
            boolean releaseGate) {}

    /** M-12: one consumer of the output. */
    public record DistributionBody(
            @Size(max = 200) @PrintableAscii String consumerName,
            @Size(max = 30) @PrintableAscii String consumerType,
            @Size(max = 20) @PrintableAscii String channel,
            @Size(max = 100) @PrintableAscii String schedule) {}

    private final CurrentAccess contexts;
    private final ReportCatalogueService catalogue;
    private final ComponentSummaryService summaries;
    private final ReportVersionService versions;
    private final Paging paging;
    private final ExportStreaming exports;

    public ReportController(
            CurrentAccess contexts,
            ReportCatalogueService catalogue,
            ComponentSummaryService summaries,
            ReportVersionService versions,
            Paging paging,
            ExportStreaming exports) {
        this.contexts = contexts;
        this.catalogue = catalogue;
        this.summaries = summaries;
        this.versions = versions;
        this.paging = paging;
        this.exports = exports;
    }

    @GetMapping("/v1/reports/catalogue")
    @PreAuthorize("@authz.gate('O-04','VIEW')")
    public ResponseEntity<Paged<ReportCatalogueService.Entry>> catalogue(
            @RequestParam(required = false) @Size(max = 40) @PrintableAscii String family,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String text,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                catalogue.page(contexts.current(), family, text, paging.resolve(page, size)));
    }

    /** M-12: the tiles, the catalogue by family and the filter choices. */
    @GetMapping("/v1/report-definitions/summary")
    @PreAuthorize("@authz.gate('M-12','VIEW')")
    public ResponseEntity<ReportCatalogueService.Summary> definitionSummary() {
        return noStore(catalogue.summary(contexts.current()));
    }

    /**
     * M-12 Report Definition Catalogue (SRS l.5440; UI audit S8): the same catalogue under the M-12
     * gate, so a role holding M-12 but not O-04 (the Rule Approver) reads the definitions it
     * approves.
     */
    @GetMapping("/v1/report-definitions")
    @PreAuthorize("@authz.gate('M-12','VIEW')")
    public ResponseEntity<Paged<ReportCatalogueService.Entry>> definitions(
            @RequestParam(required = false) @Size(max = 40) @PrintableAscii String family,
            @RequestParam(required = false) @Size(max = 100) @PrintableAscii String payrollType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String text,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                catalogue.page(
                        contexts.current(),
                        "M-12",
                        family,
                        payrollType,
                        text,
                        paging.resolve(page, size)));
    }

    @GetMapping("/v1/reports/component-summary")
    @PreAuthorize("@authz.gate('O-04','VIEW')")
    public ResponseEntity<ComponentSummaryService.Summary> summary(
            @RequestParam @Pattern(regexp = YYMM) String yymm,
            @RequestParam Level level,
            @RequestParam(required = false) @Positive Long officeId,
            @RequestParam(required = false) @Pattern(regexp = CODE) String payrollType,
            @RequestParam(required = false) @Pattern(regexp = CODE) String category,
            @RequestParam(required = false) ComponentSummaryService.MemberStatus memberStatus,
            @RequestParam(required = false) @Pattern(regexp = EIS) String fromEis,
            @RequestParam(required = false) @Pattern(regexp = EIS) String toEis,
            @RequestParam(required = false) ComponentSummaryService.Sort sort) {
        return noStore(
                summaries.summary(
                        contexts.current(),
                        yymm,
                        level,
                        new ComponentSummaryService.Filters(
                                officeId,
                                payrollType,
                                category,
                                memberStatus,
                                fromEis,
                                toEis,
                                sort)));
    }

    @GetMapping("/v1/reports/component-summary/options")
    @PreAuthorize("@authz.gate('O-04','VIEW')")
    public ResponseEntity<ComponentSummaryService.Options> options(
            @RequestParam @Pattern(regexp = YYMM) String yymm) {
        return noStore(summaries.options(contexts.current(), yymm));
    }

    /**
     * O-04 (SRS l.3501): the latest locked month of the session's offices, the screen's default.
     */
    @GetMapping("/v1/reports/component-summary/latest-locked")
    @PreAuthorize("@authz.gate('O-04','VIEW')")
    public ResponseEntity<ComponentSummaryService.LatestLocked> latestLocked() {
        return noStore(summaries.latestLocked(contexts.current()));
    }

    @GetMapping("/v1/reports/component-summary/export")
    @PreAuthorize("@authz.gate('O-04','EXPORT')")
    @SuppressWarnings("java:S107") // the parameters of the report standard
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam @Pattern(regexp = YYMM) String yymm,
            @RequestParam Level level,
            @RequestParam(required = false) @Positive Long officeId,
            @RequestParam(required = false) @Pattern(regexp = CODE) String payrollType,
            @RequestParam(required = false) @Pattern(regexp = CODE) String category,
            @RequestParam(required = false) ComponentSummaryService.MemberStatus memberStatus,
            @RequestParam(required = false) @Pattern(regexp = EIS) String fromEis,
            @RequestParam(required = false) @Pattern(regexp = EIS) String toEis,
            @RequestParam(required = false) ComponentSummaryService.Sort sort,
            @RequestParam(defaultValue = "en") @Pattern(regexp = "^(en|hi)$") String lang,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        ComponentSummaryService.Filters filters =
                new ComponentSummaryService.Filters(
                        officeId, payrollType, category, memberStatus, fromEis, toEis, sort);
        summaries.requireReleasable(context, yymm, level, filters);
        return exports.stream(
                "component-summary-" + yymm + "-" + level.name().toLowerCase(java.util.Locale.ROOT),
                format,
                sink -> summaries.export(context, yymm, level, filters, lang, format.name(), sink));
    }

    @GetMapping("/v1/report-definitions/{key}/versions")
    @PreAuthorize("@authz.gate('M-12','VIEW')")
    public ResponseEntity<List<ReportVersionService.Version>> versions(
            @PathVariable @Pattern(regexp = KEY) String key) {
        return noStore(versions.versions(contexts.current(), key));
    }

    @PostMapping("/v1/report-definitions/{key}/versions")
    @PreAuthorize("@authz.gate('M-12','CREATE')")
    public ResponseEntity<SubmittedApproval> propose(
            @PathVariable @Pattern(regexp = KEY) String key, @Valid @RequestBody VersionBody b) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(
                        versions.propose(
                                contexts.current(),
                                key,
                                new ReportVersionService.Proposal(
                                        b.layoutFormat(),
                                        b.periodLockRequired(),
                                        b.storeMode(),
                                        b.blocked(),
                                        b.blockedReason(),
                                        b.effectiveFrom(),
                                        b.roundingRuleKey(),
                                        b.parameters() == null
                                                ? List.of()
                                                : b.parameters().stream()
                                                        .map(
                                                                x ->
                                                                        new ReportModelRules
                                                                                .Parameter(
                                                                                x.code(),
                                                                                x.label(),
                                                                                x.dataType(),
                                                                                x.mandatory(),
                                                                                x.roleScoped(),
                                                                                x
                                                                                        .defaultExpression(),
                                                                                x.validationRule()))
                                                        .toList(),
                                        b.fields() == null
                                                ? List.of()
                                                : b.fields().stream()
                                                        .map(
                                                                x ->
                                                                        new ReportModelRules.Field(
                                                                                x.label(),
                                                                                x.binding(),
                                                                                x.reference(),
                                                                                x.formatMask()))
                                                        .toList(),
                                        b.controls() == null
                                                ? List.of()
                                                : b.controls().stream()
                                                        .map(
                                                                x ->
                                                                        new ReportModelRules
                                                                                .Control(
                                                                                x.label(),
                                                                                x
                                                                                        .reconcilesAgainst(),
                                                                                x.tolerance(),
                                                                                x.releaseGate()))
                                                        .toList(),
                                        b.distribution() == null
                                                ? List.of()
                                                : b.distribution().stream()
                                                        .map(
                                                                x ->
                                                                        new ReportModelRules
                                                                                .Distribution(
                                                                                x.consumerName(),
                                                                                x.consumerType(),
                                                                                x.channel(),
                                                                                x.schedule()))
                                                        .toList())));
    }
}
