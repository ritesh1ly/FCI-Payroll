package in.gov.fci.payroll.general.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.general.api.SupplementaryPayments;
import in.gov.fci.payroll.general.orchestration.SupplementaryService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * I-23 Supplementary Payment (Post-Lock) (SRS-G-33, SRS-G-34). The Payroll Checker approves with
 * OTP in the Approvals Inbox (W-01 / X-02).
 *
 * <pre>
 * GET  /v1/supplementary                         requests in scope           I-23 VIEW
 * GET  /v1/supplementary/tiles                   open / pending / blocked    I-23 VIEW
 * GET  /v1/supplementary/export                  the list as CSV             I-23 EXPORT
 * POST /v1/supplementary (multipart)             pending salary + order      I-23 CREATE
 * GET  /v1/supplementary/{id}                    one request                 I-23 VIEW
 * GET  /v1/supplementary/{id}/orders             stored orders               I-23 VIEW
 * POST /v1/supplementary/{id}/submit             to the Checker (OTP)        I-23 SUBMIT -> I23_SUBMIT approval
 * POST /v1/supplementary/{id}/cancel             cancel with reason          I-23 SUBMIT
 * GET  /v1/supplementary/{id}/files/{fileId}     order download              I-23 VIEW
 * </pre>
 *
 * No DELETE: a request is cancelled, never deleted (SRS-G-37).
 */
@RestController
@Validated
@Profile("online")
public class SupplementaryController {

    public record ReasonRequest(@NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final SupplementaryService service;

    private final ExportStreaming exports;
    private final Paging paging;

    public SupplementaryController(
            ExportStreaming exports,
            CurrentAccess contexts,
            SupplementaryService service,
            Paging paging) {
        this.paging = paging;
        this.exports = exports;
        this.contexts = contexts;
        this.service = service;
    }

    @GetMapping("/v1/supplementary")
    @PreAuthorize("@authz.gate('I-23','VIEW')")
    public ResponseEntity<Paged<SupplementaryService.PaymentView>> list(
            @RequestParam(required = false)
                    @jakarta.validation.constraints.Pattern(regexp = "^[A-Z_]{2,30}$")
                    String paymentType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(service.list(contexts.current(), paymentType, paging.resolve(page, size)));
    }

    @GetMapping("/v1/supplementary/export")
    @PreAuthorize("@authz.gate('I-23','EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(service.exportSize(context));
        return exports.stream(
                "supplementary-payments", format, sink -> service.export(context, sink));
    }

    @GetMapping("/v1/supplementary/tiles")
    @PreAuthorize("@authz.gate('I-23','VIEW')")
    public ResponseEntity<SupplementaryService.Tiles> tiles() {
        return noStore(service.tiles(contexts.current()));
    }

    /**
     * Records a pending salary of a locked month with its order (one request: the DDL needs both).
     */
    @PostMapping(value = "/v1/supplementary", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-23','CREATE')")
    public ResponseEntity<SupplementaryService.PaymentView> capture(
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{1,20}$") String eis,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,30}$") String paymentType,
            @RequestParam @NotBlank @Pattern(regexp = "^\\d{6}$") String forPeriodYymm,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate applicationDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate sanctionDate,
            @RequestParam(required = false) @Size(max = 100) @PrintableAscii
                    String sanctionOrderRef,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        service.capture(
                                contexts.current(),
                                eis,
                                paymentType,
                                forPeriodYymm,
                                applicationDate,
                                sanctionDate,
                                sanctionOrderRef,
                                new SupplementaryPayments.Order(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingAuthority,
                                        file.getOriginalFilename(),
                                        bytes(file))));
    }

    @GetMapping("/v1/supplementary/{id}")
    @PreAuthorize("@authz.gate('I-23','VIEW')")
    public ResponseEntity<SupplementaryService.PaymentView> get(@PathVariable @Positive Long id) {
        return noStore(service.get(contexts.current(), id));
    }

    @PostMapping("/v1/supplementary/{id}/submit")
    @PreAuthorize("@authz.gate('I-23','SUBMIT')")
    public ResponseEntity<SupplementaryService.PaymentView> submit(
            @PathVariable @Positive Long id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(service.submit(contexts.current(), id));
    }

    @PostMapping("/v1/supplementary/{id}/cancel")
    @PreAuthorize("@authz.gate('I-23','SUBMIT')")
    public ResponseEntity<SupplementaryService.PaymentView> cancel(
            @PathVariable @Positive Long id, @Valid @RequestBody ReasonRequest r) {
        return noStore(service.cancel(contexts.current(), id, r.reason()));
    }

    @GetMapping("/v1/supplementary/{id}/orders")
    @PreAuthorize("@authz.gate('I-23','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> orders(
            @PathVariable @Positive Long id) {
        return noStore(service.orders(contexts.current(), id));
    }

    /** Downloads the order; served as an attachment so the browser never renders it inline. */
    @GetMapping("/v1/supplementary/{id}/files/{fileId}")
    @PreAuthorize("@authz.gate('I-23','VIEW')")
    public ResponseEntity<byte[]> file(
            @PathVariable @Positive Long id,
            @PathVariable @Positive Long fileId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                service.file(contexts.current(), request.getRemoteAddr(), id, fileId);
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

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
