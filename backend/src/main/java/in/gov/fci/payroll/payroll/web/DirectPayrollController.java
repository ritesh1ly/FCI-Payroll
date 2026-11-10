package in.gov.fci.payroll.payroll.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.payroll.orchestration.DirectPayrollService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * I-19 Maintain Direct Payroll (plan 05 phase 05g; SRS-15-19, SRS-15-22). The SRS names the screen
 * and its fields, not the API (PENDING API-05-2).
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/mdp-transactions")
public class DirectPayrollController {

    private static final String PERIOD = "^\\d{4}-(0[1-9]|1[0-2])$";
    private static final String STATUS = "^(ENTERED|AMENDED|APPROVED|CANCELLED|POSTED)$";
    private static final String EIS = "^[A-Za-z0-9]{1,20}$";

    public record CaptureBody(
            @NotNull @Pattern(regexp = PERIOD) String period,
            @NotBlank @Pattern(regexp = EIS) String eis,
            @NotNull @Min(1) Integer payCode,
            @NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    public record AmendBody(
            @NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    public record ReasonBody(@NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    /** The approval item a change opened. */
    public record Submitted(long approvalId) {}

    private final CurrentAccess contexts;
    private final DirectPayrollService mdp;
    private final ExportStreaming exports;
    private final Paging paging;

    public DirectPayrollController(
            CurrentAccess contexts,
            DirectPayrollService mdp,
            ExportStreaming exports,
            Paging paging) {
        this.contexts = contexts;
        this.mdp = mdp;
        this.exports = exports;
        this.paging = paging;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('I-19','VIEW')")
    public ResponseEntity<Paged<DirectPayrollService.Entry>> register(
            @RequestParam(required = false) @Pattern(regexp = PERIOD) String period,
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Min(1) Integer payCode,
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                mdp.register(
                        contexts.current(),
                        filter(period, status, payCode, eis),
                        paging.resolve(page, size)));
    }

    /** SRS-15-22: the MDP Audit Report (every entry, cancelled ones included). */
    @GetMapping("/export")
    @PreAuthorize("@authz.gate('I-19','EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) @Pattern(regexp = PERIOD) String period,
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Min(1) Integer payCode,
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        DirectPayrollService.Filter filter = filter(period, status, payCode, eis);
        exports.checkSize(mdp.exportSize(context, filter));
        return exports.stream(
                "mdp-audit-report", format, sink -> mdp.export(context, filter, sink));
    }

    /** I-19 as drawn (OD-121): the window, the entries of its period, the heads and the value. */
    @GetMapping("/summary")
    @PreAuthorize("@authz.gate('I-19','VIEW')")
    public ResponseEntity<DirectPayrollService.Summary> summary() {
        return noStore(mdp.summary(contexts.current()));
    }

    /**
     * I-19 "Order copy - attach &amp; store" (FRS §6.4.3, A-41, SRS-G-30): the Maker attaches the
     * authority order.
     */
    @PostMapping(value = "/{mdpId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-19','CREATE')")
    public ResponseEntity<DocumentStore.AttachmentView> attachOrder(
            @PathVariable @Positive long mdpId,
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
                        mdp.attachOrder(
                                contexts.current(),
                                mdpId,
                                new DirectPayrollService.NewOrder(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingOffice,
                                        file.getOriginalFilename(),
                                        content)));
    }

    @GetMapping("/{mdpId}/documents")
    @PreAuthorize("@authz.gate('I-19','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> orders(
            @PathVariable @Positive long mdpId) {
        return noStore(mdp.orders(contexts.current(), mdpId));
    }

    /** One stored order, served as an attachment so the browser never renders it inline. */
    @GetMapping("/{mdpId}/documents/{fileId}")
    @PreAuthorize("@authz.gate('I-19','VIEW')")
    public ResponseEntity<byte[]> order(
            @PathVariable @Positive long mdpId,
            @PathVariable @Positive long fileId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                mdp.order(contexts.current(), request.getRemoteAddr(), mdpId, fileId);
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

    @GetMapping("/heads")
    @PreAuthorize("@authz.gate('I-19','VIEW')")
    public ResponseEntity<List<DirectPayrollService.Head>> heads() {
        return noStore(mdp.heads(contexts.current()));
    }

    @GetMapping("/open-periods")
    @PreAuthorize("@authz.gate('I-19','VIEW')")
    public ResponseEntity<List<String>> openPeriods() {
        return noStore(mdp.openPeriods(contexts.current()));
    }

    /** SRS-15-19: the Payroll Maker captures an entry (202: the Payroll Checker decides). */
    @PostMapping
    @PreAuthorize("@authz.gate('I-19','CREATE')")
    public ResponseEntity<Submitted> capture(@Valid @RequestBody CaptureBody body) {
        return accepted(
                mdp.capture(
                        contexts.current(),
                        YearMonth.parse(body.period()),
                        body.eis(),
                        body.payCode(),
                        body.amount(),
                        body.reason()));
    }

    @PostMapping("/{mdpId}/amendments")
    @PreAuthorize("@authz.gate('I-19','EDIT')")
    public ResponseEntity<Submitted> amend(
            @PathVariable @Min(1) long mdpId, @Valid @RequestBody AmendBody body) {
        return accepted(mdp.amend(contexts.current(), mdpId, body.amount(), body.reason()));
    }

    @PostMapping("/{mdpId}/cancellations")
    @PreAuthorize("@authz.gate('I-19','CANCEL')")
    public ResponseEntity<Submitted> cancel(
            @PathVariable @Min(1) long mdpId, @Valid @RequestBody ReasonBody body) {
        return accepted(mdp.cancel(contexts.current(), mdpId, body.reason()));
    }

    private static DirectPayrollService.Filter filter(
            String period, String status, Integer payCode, String eis) {
        return new DirectPayrollService.Filter(
                period == null ? null : YearMonth.parse(period), status, payCode, eis);
    }

    private static ResponseEntity<Submitted> accepted(long approvalId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(new Submitted(approvalId));
    }
}
