package in.gov.fci.payroll.increment.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.increment.orchestration.IncrementReportService;
import in.gov.fci.payroll.increment.orchestration.IncrementRunService;
import in.gov.fci.payroll.increment.orchestration.IncrementWorklistService;
import in.gov.fci.payroll.increment.orchestration.PayFixationService;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.workflow.api.Submitted;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
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
 * I-05 Increment &amp; Pay Fixation (plan 17 phases 17c - 17f; SRS-13-1 … 13-27). The SRS names the
 * screen, not the API (PENDING API-17-1).
 *
 * <pre>
 * GET  /v1/increments/due?month=           the Increment Due list (paged)          I-05 VIEW
 * POST /v1/increments/submissions          submit selected members (one item each) I-05 SUBMIT
 * GET  /v1/increments/tracking?month=      Increment Tracking Report (file)       I-05 EXPORT
 * POST /v1/pay-fixation-cases/previews     the proposal of a case                 I-05 SUBMIT
 * POST /v1/pay-fixation-cases              submit a case (202)                     I-05 SUBMIT
 * GET  /v1/basic-history?eis=              a member's Basic history                I-05 VIEW
 * GET  /v1/basic-history/report?eis=       Pay Fixation / Basic History Report    I-05 EXPORT
 * GET  /v1/increments/counts?month=        the drawn tiles and figures            I-05 VIEW
 * GET  /v1/increments/worklist?month=      the Increment / Fixation Worklist      I-05 VIEW
 * GET  /v1/pay-fixation-cases/{approvalId}/orders           a case's orders       I-05 VIEW
 * GET  /v1/pay-fixation-cases/{approvalId}/files/{fileId}   one order             I-05 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class IncrementController {

    private static final String EIS = "^[0-9A-Za-z]{1,20}$";
    private static final String TYPES =
            "^(PROMOTION_FIXATION|INDIVIDUAL_REVISION|ADDITIONAL_INCREMENT|STAGNATION_INCREMENT|"
                    + "BASIC_CORRECTION|DATE_CORRECTION|REVERSE_ENTRY|OBSERVATION_REFIXATION)$";

    public record RunBody(
            @NotEmpty @Size(max = 200) List<@NotNull Long> employeeIds,
            @NotNull @Size(min = 1, max = 500) @PrintableAscii String remark) {}

    public record CaseBody(
            @NotNull @Pattern(regexp = EIS) String eis,
            @NotNull @Pattern(regexp = TYPES) String caseType,
            @NotNull LocalDate effectiveDate,
            @DecimalMin("1") @DecimalMax("9999999999") BigDecimal basic,
            LocalDate correctedDueDate,
            @NotNull @Size(min = 1, max = 60) @PrintableAscii String orderNumber,
            @NotNull LocalDate orderDate,
            @NotNull @Size(min = 1, max = 100) @PrintableAscii String issuingAuthority,
            @NotNull @Size(min = 1, max = 500) @PrintableAscii String reason,
            @jakarta.validation.constraints.Positive Long orderAttachmentId,
            @Size(max = 60) @Pattern(regexp = "^(PROVISIONAL:[0-9a-f-]{36})?$")
                    String orderProvisionalKey,
            // SRS-13-27 (US-GAP-54): an observation refixation's source and reference.
            @Pattern(regexp = "^(AUDIT|ACCOUNTS)$") String observationSource,
            @Size(max = 100) @PrintableAscii String observationReference) {

        PayFixationService.Case toCase() {
            return new PayFixationService.Case(
                    eis,
                    caseType,
                    effectiveDate,
                    basic,
                    correctedDueDate,
                    orderNumber,
                    orderDate,
                    issuingAuthority,
                    reason,
                    orderAttachmentId,
                    orderProvisionalKey,
                    observationSource,
                    observationReference);
        }
    }

    private final CurrentAccess contexts;
    private final IncrementRunService run;
    private final PayFixationService cases;
    private final IncrementReportService reports;
    private final IncrementWorklistService worklist;
    private final Paging paging;
    private final ExportStreaming exports;

    public IncrementController(
            CurrentAccess contexts,
            IncrementRunService run,
            PayFixationService cases,
            IncrementReportService reports,
            IncrementWorklistService worklist,
            Paging paging,
            ExportStreaming exports) {
        this.contexts = contexts;
        this.run = run;
        this.cases = cases;
        this.reports = reports;
        this.worklist = worklist;
        this.paging = paging;
        this.exports = exports;
    }

    @GetMapping("/v1/increments/due")
    @PreAuthorize("@authz.gate('I-05','VIEW')")
    public ResponseEntity<Paged<IncrementRunService.DueRow>> due(
            @RequestParam YearMonth month,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(run.due(contexts.current(), month, paging.resolve(page, size)));
    }

    /** The drawn tiles and the figures of Rules &amp; guards (API-17-2). */
    @GetMapping("/v1/increments/counts")
    @PreAuthorize("@authz.gate('I-05','VIEW')")
    public ResponseEntity<IncrementWorklistService.Counts> counts(@RequestParam YearMonth month) {
        return noStore(worklist.counts(contexts.current(), month));
    }

    /** The drawn Increment / Fixation Worklist (API-17-2). */
    @GetMapping("/v1/increments/worklist")
    @PreAuthorize("@authz.gate('I-05','VIEW')")
    public ResponseEntity<Paged<IncrementWorklistService.Row>> worklist(
            @RequestParam YearMonth month,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(worklist.worklist(contexts.current(), month, paging.resolve(page, size)));
    }

    /** SRS-13-18, SRS-G-31: the orders stored with a case (the drawn order card). */
    @GetMapping("/v1/pay-fixation-cases/{approvalId}/orders")
    @PreAuthorize("@authz.gate('I-05','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> caseOrders(
            @PathVariable @Positive long approvalId) {
        return noStore(worklist.orders(contexts.current(), approvalId));
    }

    /** One stored order, as an attachment so the browser never renders it inline. */
    @GetMapping("/v1/pay-fixation-cases/{approvalId}/files/{fileId}")
    @PreAuthorize("@authz.gate('I-05','VIEW')")
    public ResponseEntity<byte[]> caseOrder(
            @PathVariable @Positive long approvalId,
            @PathVariable @Positive long fileId,
            jakarta.servlet.http.HttpServletRequest request) {
        DocumentStore.Content content =
                worklist.orderFile(contexts.current(), request.getRemoteAddr(), approvalId, fileId);
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                                .filename(content.attachment().fileName())
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(
                        org.springframework.http.MediaType.parseMediaType(
                                content.attachment().contentType()))
                .body(content.bytes());
    }

    @PostMapping("/v1/increments/submissions")
    @PreAuthorize("@authz.gate('I-05','SUBMIT')")
    public ResponseEntity<IncrementRunService.Submitted> submitRun(
            @Valid @RequestBody RunBody body) {
        return noStore(run.submit(contexts.current(), body.employeeIds(), body.remark()));
    }

    @GetMapping("/v1/increments/tracking")
    @PreAuthorize("@authz.gate('I-05','EXPORT')")
    public ResponseEntity<StreamingResponseBody> tracking(
            @RequestParam YearMonth month,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(reports.trackingSize(context, month));
        return exports.stream(
                "increment-tracking", format, sink -> reports.tracking(context, month, sink));
    }

    @PostMapping("/v1/pay-fixation-cases/previews")
    @PreAuthorize("@authz.gate('I-05','SUBMIT')")
    public ResponseEntity<PayFixationService.Proposal> preview(@Valid @RequestBody CaseBody body) {
        return noStore(cases.preview(contexts.current(), body.toCase()));
    }

    /** SRS-13-18 / SRS-G-31 (US-GAP-10): the order copy, stored before the case names it (201). */
    @PostMapping(
            value = "/v1/pay-fixation-cases/orders",
            consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-05','SUBMIT')")
    public ResponseEntity<PayFixationService.StoredOrder> storeOrder(
            @RequestParam @Pattern(regexp = EIS) String eis,
            @RequestParam
                    @jakarta.validation.constraints.NotBlank
                    @Pattern(regexp = "^[A-Z_]{2,40}$")
                    String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam
                    @NotNull
                    @org.springframework.format.annotation.DateTimeFormat(
                            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @jakarta.validation.constraints.NotBlank @Size(max = 200) @PrintableAscii
                    String issuingAuthority,
            @org.springframework.web.bind.annotation.RequestPart("file")
                    org.springframework.web.multipart.MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        cases.storeOrder(
                                contexts.current(),
                                eis,
                                new PayFixationService.OrderCopy(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingAuthority,
                                        file.getOriginalFilename(),
                                        content)));
    }

    @PostMapping("/v1/pay-fixation-cases")
    @PreAuthorize("@authz.gate('I-05','SUBMIT')")
    public ResponseEntity<Submitted> submitCase(@Valid @RequestBody CaseBody body) {
        SubmittedApproval item = cases.submit(contexts.current(), body.toCase());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new Submitted(item.id(), item.status().name()));
    }

    @GetMapping("/v1/basic-history")
    @PreAuthorize("@authz.gate('I-05','VIEW')")
    public ResponseEntity<List<IncrementReportService.HistoryRow>> history(
            @RequestParam @Pattern(regexp = EIS) String eis) {
        return noStore(reports.history(contexts.current(), eis));
    }

    @GetMapping("/v1/basic-history/report")
    @PreAuthorize("@authz.gate('I-05','EXPORT')")
    public ResponseEntity<StreamingResponseBody> historyReport(
            @RequestParam @Pattern(regexp = EIS) String eis,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(reports.historySize(context, eis));
        return exports.stream(
                "basic-history", format, sink -> reports.historyReport(context, eis, sink));
    }
}
