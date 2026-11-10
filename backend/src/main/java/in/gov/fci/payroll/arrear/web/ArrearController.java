package in.gov.fci.payroll.arrear.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.arrear.orchestration.ArrearExceptionService;
import in.gov.fci.payroll.arrear.orchestration.ArrearOverviewService;
import in.gov.fci.payroll.arrear.orchestration.ArrearRegisterService;
import in.gov.fci.payroll.arrear.orchestration.RetroEventService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.workflow.api.Submitted;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * I-08 Arrears &amp; Retrospective Processing (plan 13 phase 13a; SRS-8-8, SRS-8-14, SRS-8-21). An
 * event is raised by the DA arrear job or a back-dated event, never typed here. The SRS names the
 * screen, not the API (PENDING API-13-1).
 *
 * <pre>
 * GET  /v1/arrear-events                    the events of the offices (paged)    I-08 VIEW
 * GET  /v1/arrear-events/{id}/details       one event's differentials (paged)    I-08 VIEW
 * POST /v1/arrear-events/{id}/submissions   submit for Approve &amp; Merge (202)  I-08 SUBMIT
 * POST /v1/arrear-events/{id}/computations  recompute a single-member event      I-08 SUBMIT
 * POST /v1/arrear-events/{id}/hq-exceptions ask HQ to process an event beyond
 *                                           the back-date limit (202, SRS-8-6)   I-08 SUBMIT
 * GET  /v1/arrear-events/register           the Arrears Register (file)          I-08 EXPORT
 * POST /v1/arrear-events/{id}/documents     attach an order copy (multipart)     I-08 SUBMIT
 * GET  /v1/arrear-events/{id}/documents     the stored orders                    I-08 VIEW
 * GET  /v1/arrear-events/{id}/documents/{f} one stored order (attachment)        I-08 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class ArrearController {

    private static final String STATUS = "^(RAISED|COMPUTED|APPROVED|POSTED|REVERSED|REJECTED)$";
    private static final String CODE = "^[A-Z_]{1,30}$";

    public record SubmissionBody(@NotNull @Size(max = 500) @PrintableAscii String remark) {}

    /** SRS-8-6: the reason and the order (an Active M-25 circular) of a Headquarters exception. */
    public record ExceptionBody(
            @NotNull @Size(max = 500) @PrintableAscii String reason,
            @NotNull @Positive Long circularId) {}

    private final CurrentAccess contexts;
    private final ArrearRegisterService register;
    private final Paging paging;
    private final ExportStreaming exports;
    private final RetroEventService retro;
    private final ArrearExceptionService exceptions;
    private final ArrearOverviewService overview;

    public ArrearController(
            CurrentAccess contexts,
            ArrearRegisterService register,
            Paging paging,
            ExportStreaming exports,
            RetroEventService retro,
            ArrearExceptionService exceptions,
            ArrearOverviewService overview) {
        this.overview = overview;
        this.contexts = contexts;
        this.register = register;
        this.paging = paging;
        this.exports = exports;
        this.retro = retro;
        this.exceptions = exceptions;
    }

    @GetMapping("/v1/arrear-events")
    @PreAuthorize("@authz.gate('I-08','VIEW')")
    public ResponseEntity<Paged<ArrearRegisterService.EventRow>> events(
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Pattern(regexp = CODE) String eventCode,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                register.events(
                        contexts.current(),
                        new ArrearRegisterService.Filter(status, eventCode),
                        paging.resolve(page, size)));
    }

    /** I-08 as drawn (OD-121): the four tiles over the scope. */
    @GetMapping("/v1/arrear-events/summary")
    @PreAuthorize("@authz.gate('I-08','VIEW')")
    public ResponseEntity<ArrearOverviewService.Summary> summary() {
        return noStore(overview.summary(contexts.current()));
    }

    /** I-08 "Retro controls": the Headquarters settings (null = not configured, R-RULE). */
    @GetMapping("/v1/arrear-events/controls")
    @PreAuthorize("@authz.gate('I-08','VIEW')")
    public ResponseEntity<ArrearOverviewService.Controls> controls() {
        return noStore(overview.controls(contexts.current()));
    }

    /** The event types of arrear_event_type, for the New Retro Event dropdown (OD-123). */
    @GetMapping("/v1/arrear-events/types")
    @PreAuthorize("@authz.gate('I-08','VIEW')")
    public ResponseEntity<List<ArrearOverviewService.EventType>> types() {
        return noStore(overview.types(contexts.current()));
    }

    /** I-08 "Period-wise differential": every month of one event with its sums. */
    @GetMapping("/v1/arrear-events/{id}/periods")
    @PreAuthorize("@authz.gate('I-08','VIEW')")
    public ResponseEntity<List<ArrearOverviewService.PeriodRow>> periods(@PathVariable long id) {
        return noStore(overview.periods(contexts.current(), id));
    }

    /** The Maker's new retro event (SRS-8-8): RAISED for the office and span. */
    public record NewEventBody(
            @NotNull @Pattern(regexp = CODE) String eventCode,
            @NotNull @Size(min = 1, max = 100) @PrintableAscii String reference,
            @NotNull @Pattern(regexp = "^(STAFF|DEPT_LABOUR|DPS_LABOUR|NWNP_LABOUR)$")
                    String payrollType,
            @NotNull @PastOrPresent LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @Positive Long circularId) {}

    @PostMapping("/v1/arrear-events")
    @PreAuthorize("@authz.gate('I-08','SUBMIT')")
    public ResponseEntity<java.util.Map<String, Long>> create(
            @Valid @RequestBody NewEventBody body) {
        long id =
                overview.create(
                        contexts.current(),
                        new ArrearOverviewService.NewEvent(
                                body.eventCode(),
                                body.reference(),
                                body.payrollType(),
                                body.effectiveFrom(),
                                body.effectiveTo(),
                                body.circularId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(java.util.Map.of("id", id));
    }

    /** I-08 "Order copy - attach &amp; store" (FRS §6.4.3, A-41, SRS-G-30): the Maker attaches. */
    @PostMapping(
            value = "/v1/arrear-events/{id}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-08','SUBMIT')")
    public ResponseEntity<DocumentStore.AttachmentView> attachOrder(
            @PathVariable @Positive long id,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @PastOrPresent @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam(required = false) @Size(max = 200) @PrintableAscii String issuingOffice,
            @RequestPart("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        overview.attachOrder(
                                contexts.current(),
                                id,
                                new ArrearOverviewService.NewOrder(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingOffice,
                                        file.getOriginalFilename(),
                                        content)));
    }

    @GetMapping("/v1/arrear-events/{id}/documents")
    @PreAuthorize("@authz.gate('I-08','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> orders(
            @PathVariable @Positive long id) {
        return noStore(overview.orders(contexts.current(), id));
    }

    /** One stored order, served as an attachment so the browser never renders it inline. */
    @GetMapping("/v1/arrear-events/{id}/documents/{fileId}")
    @PreAuthorize("@authz.gate('I-08','VIEW')")
    public ResponseEntity<byte[]> order(
            @PathVariable @Positive long id,
            @PathVariable @Positive long fileId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                overview.order(contexts.current(), request.getRemoteAddr(), id, fileId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(content.attachment().fileName())
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(content.attachment().contentType()))
                .body(content.bytes());
    }

    @GetMapping("/v1/arrear-events/{id}/details")
    @PreAuthorize("@authz.gate('I-08','VIEW')")
    public ResponseEntity<Paged<ArrearRegisterService.DetailRow>> details(
            @PathVariable long id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(register.details(contexts.current(), id, paging.resolve(page, size)));
    }

    @PostMapping("/v1/arrear-events/{id}/submissions")
    @PreAuthorize("@authz.gate('I-08','SUBMIT')")
    public ResponseEntity<Submitted> submit(
            @PathVariable long id, @Valid @RequestBody SubmissionBody body) {
        SubmittedApproval item = register.submit(contexts.current(), id, body.remark());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new Submitted(item.id(), item.status().name()));
    }

    /** SRS-8-6: the Maker asks Headquarters to process an event beyond the back-date limit. */
    @PostMapping("/v1/arrear-events/{id}/hq-exceptions")
    @PreAuthorize("@authz.gate('I-08','SUBMIT')")
    public ResponseEntity<Submitted> requestException(
            @PathVariable long id, @Valid @RequestBody ExceptionBody body) {
        SubmittedApproval item =
                exceptions.request(contexts.current(), id, body.reason(), body.circularId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new Submitted(item.id(), item.status().name()));
    }

    /** The outcome of a recompute: computed, or the reason the member cannot be. */
    public record Computation(boolean computed, String reason) {}

    /**
     * Plan 13 phase 13b: the Maker computes a single-member retrospective event again, once the
     * member's data is complete (SRS-8-13); a refusal is an answer, not an error.
     */
    @PostMapping("/v1/arrear-events/{id}/computations")
    @PreAuthorize("@authz.gate('I-08','SUBMIT')")
    public ResponseEntity<Computation> recompute(@PathVariable long id) {
        java.util.Optional<String> refused = retro.recompute(contexts.current(), id);
        return noStore(new Computation(refused.isEmpty(), refused.orElse(null)));
    }

    /** SRS-8-21: the Arrears Register (Excel / CSV). */
    @GetMapping("/v1/arrear-events/register")
    @PreAuthorize("@authz.gate('I-08','EXPORT')")
    public ResponseEntity<StreamingResponseBody> registerExport(
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Pattern(regexp = CODE) String eventCode,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        ArrearRegisterService.Filter filter = new ArrearRegisterService.Filter(status, eventCode);
        exports.checkSize(register.registerSize(context, filter));
        return exports.stream(
                "arrears-register", format, sink -> register.register(context, filter, sink));
    }
}
