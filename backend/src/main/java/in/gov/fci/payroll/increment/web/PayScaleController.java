package in.gov.fci.payroll.increment.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.increment.orchestration.PayScaleService;
import in.gov.fci.payroll.increment.orchestration.WageRevisionFixationService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
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
 * M-03 Pay Scales &amp; Grades and the I-25 bulk fixation (plan 17 phases 17b and 17g). The SRS
 * names the screens, not the API (PENDING API-17-1).
 *
 * <pre>
 * GET  /v1/pay-scales                          the scales with the version in force   M-03 VIEW
 * GET  /v1/pay-scales/{code}/stages            the stages of the version in force     M-03 VIEW
 * POST /v1/pay-scales                          define a scale with its first version  M-03 CREATE
 * POST /v1/pay-scales/{code}/versions          propose a version (Headquarters)       M-03 CREATE
 * POST /v1/wage-revisions/{id}/fixations       bulk fixation of an approved revision  I-25 CREATE
 * GET  /v1/wage-revisions/{id}/status-report   Revision Status Report (file)          I-25 EXPORT
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class PayScaleController {

    private static final String CODE = "^[A-Za-z0-9-]{1,20}$";

    public record VersionBody(
            @NotNull @DecimalMin("1") @DecimalMax("9999999999") BigDecimal min,
            @NotNull @DecimalMin("1") @DecimalMax("9999999999") BigDecimal max,
            @DecimalMin("0") @DecimalMax("9999999999") BigDecimal stagnationAmount,
            @NotNull LocalDate effectiveFrom,
            @NotNull Long circularId,
            @NotEmpty @Size(max = 200) List<@NotNull @DecimalMin("1") BigDecimal> stages,
            @NotNull @Size(min = 1, max = 500) @PrintableAscii String reason,
            @Size(max = 30) @PrintableAscii String wageRevision) {}

    /** SC-17-0030 (US-GAP-56): a new scale - code, name, type - with its first version. */
    public record ScaleBody(
            @NotNull @Pattern(regexp = "^[A-Za-z0-9-]{1,10}$") String code,
            @NotNull @Size(min = 1, max = 100) @PrintableAscii String name,
            @NotNull @Pattern(regexp = "^(IDA|CDA)$") String scaleType,
            @NotNull @Valid VersionBody version) {}

    private final CurrentAccess contexts;
    private final PayScaleService scales;
    private final WageRevisionFixationService fixation;
    private final Paging paging;
    private final ExportStreaming exports;

    public PayScaleController(
            CurrentAccess contexts,
            PayScaleService scales,
            WageRevisionFixationService fixation,
            Paging paging,
            ExportStreaming exports) {
        this.contexts = contexts;
        this.scales = scales;
        this.fixation = fixation;
        this.paging = paging;
        this.exports = exports;
    }

    @GetMapping("/v1/pay-scales")
    @PreAuthorize("@authz.gate('M-03','VIEW')")
    public ResponseEntity<Paged<PayScaleService.ScaleRow>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(scales.scales(contexts.current(), paging.resolve(page, size)));
    }

    /** The drawn Grade to Designation to Scale Mapping (API-17-3). */
    @GetMapping("/v1/pay-scales/mapping")
    @PreAuthorize("@authz.gate('M-03','VIEW')")
    public ResponseEntity<Paged<PayScaleService.MappingRow>> mapping(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(scales.mapping(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/pay-scales/{code}/stages")
    @PreAuthorize("@authz.gate('M-03','VIEW')")
    public ResponseEntity<List<BigDecimal>> stages(
            @PathVariable @Pattern(regexp = CODE) String code) {
        return noStore(scales.stages(contexts.current(), code));
    }

    /** M-03 (SRS l.3952): every version of the scale, the superseded ones end-dated. */
    @GetMapping("/v1/pay-scales/{code}/versions")
    @PreAuthorize("@authz.gate('M-03','VIEW')")
    public ResponseEntity<List<PayScaleService.Version>> versions(
            @PathVariable @Pattern(regexp = CODE) String code) {
        return noStore(scales.versions(contexts.current(), code));
    }

    @PostMapping("/v1/pay-scales/{code}/versions")
    @PreAuthorize("@authz.gate('M-03','CREATE')")
    public ResponseEntity<PayScaleService.Proposed> propose(
            @PathVariable @Pattern(regexp = CODE) String code, @Valid @RequestBody VersionBody b) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        scales.propose(
                                contexts.current(),
                                new PayScaleService.Proposal(
                                        code,
                                        b.min(),
                                        b.max(),
                                        b.stagnationAmount(),
                                        b.effectiveFrom(),
                                        b.circularId(),
                                        b.stages(),
                                        b.reason(),
                                        b.wageRevision())));
    }

    @PostMapping("/v1/pay-scales")
    @PreAuthorize("@authz.gate('M-03','CREATE')")
    public ResponseEntity<PayScaleService.Proposed> define(@Valid @RequestBody ScaleBody b) {
        VersionBody v = b.version();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        scales.define(
                                contexts.current(),
                                new PayScaleService.NewScale(
                                        b.code(),
                                        b.name(),
                                        b.scaleType(),
                                        new PayScaleService.Proposal(
                                                b.code(),
                                                v.min(),
                                                v.max(),
                                                v.stagnationAmount(),
                                                v.effectiveFrom(),
                                                v.circularId(),
                                                v.stages(),
                                                v.reason(),
                                                v.wageRevision()))));
    }

    /** SRS M-03 Wage Revision lookup: the staff wage revisions a scale version may belong to. */
    @GetMapping("/v1/pay-scales/wage-revisions")
    @PreAuthorize("@authz.gate('M-03','VIEW')")
    public ResponseEntity<List<PayScaleService.WageRevision>> wageRevisions() {
        return noStore(scales.wageRevisions(contexts.current()));
    }

    /** SRS-8-25 steps 5 and 6. */
    @PostMapping("/v1/wage-revisions/{id}/fixations")
    @PreAuthorize("@authz.gate('I-25','CREATE')")
    public ResponseEntity<WageRevisionFixationService.Fixed> fix(@PathVariable long id) {
        return noStore(fixation.fix(contexts.current(), id));
    }

    /** SRS-8-25 step 8. */
    @GetMapping("/v1/wage-revisions/{id}/status-report")
    @PreAuthorize("@authz.gate('I-25','EXPORT')")
    public ResponseEntity<StreamingResponseBody> statusReport(
            @PathVariable long id, @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(fixation.statusSize(context, id));
        return exports.stream(
                "revision-status-report", format, sink -> fixation.statusReport(context, id, sink));
    }
}
