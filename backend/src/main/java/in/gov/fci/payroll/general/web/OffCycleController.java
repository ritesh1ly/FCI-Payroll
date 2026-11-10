package in.gov.fci.payroll.general.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.general.orchestration.OffCycleService;
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
 * I-20 Off-Cycle / Exceptional Salary Processing (SRS-G-10). The Payroll Checker decides in the
 * Approvals Inbox (W-01 / X-02).
 *
 * <pre>
 * GET  /v1/off-cycle                          cases in scope              I-20 VIEW
 * GET  /v1/off-cycle/export                   the list as CSV             I-20 EXPORT
 * POST /v1/off-cycle                          capture (amount computed)   I-20 CREATE
 * GET  /v1/off-cycle/{id}                     one case                    I-20 VIEW
 * GET  /v1/off-cycle/{id}/orders              stored orders               I-20 VIEW
 * POST /v1/off-cycle/{id}/order (multipart)   approval note / order       I-20 CREATE
 * POST /v1/off-cycle/{id}/submit              to the Checker              I-20 SUBMIT -> I20_CREATE approval
 * POST /v1/off-cycle/{id}/cancel              cancel with reason          I-20 SUBMIT
 * GET  /v1/off-cycle/{id}/files/{fileId}      order download              I-20 VIEW
 * </pre>
 *
 * No DELETE: a case is cancelled, never deleted (SRS-G-37).
 */
@RestController
@Validated
@Profile("online")
public class OffCycleController {

    public record CaptureRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{1,20}$") String eis,
            @NotBlank @Pattern(regexp = "^(LATE_JOINER|DEATH_IN_SERVICE|OTHER)$") String caseType,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String salaryYymm,
            @NotBlank @Size(max = 100) @PrintableAscii String approvalReference) {}

    public record ReasonRequest(@NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final OffCycleService service;

    private final ExportStreaming exports;
    private final Paging paging;

    public OffCycleController(
            ExportStreaming exports,
            CurrentAccess contexts,
            OffCycleService service,
            Paging paging) {
        this.paging = paging;
        this.exports = exports;
        this.contexts = contexts;
        this.service = service;
    }

    @GetMapping("/v1/off-cycle")
    @PreAuthorize("@authz.gate('I-20','VIEW')")
    public ResponseEntity<Paged<OffCycleService.CaseView>> list(
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z_]{2,30}$") String caseType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(service.list(contexts.current(), caseType, paging.resolve(page, size)));
    }

    @GetMapping("/v1/off-cycle/export")
    @PreAuthorize("@authz.gate('I-20','EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(service.exportSize(context));
        return exports.stream("off-cycle-cases", format, sink -> service.export(context, sink));
    }

    @PostMapping("/v1/off-cycle")
    @PreAuthorize("@authz.gate('I-20','CREATE')")
    public ResponseEntity<OffCycleService.CaseView> capture(@Valid @RequestBody CaptureRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        service.capture(
                                contexts.current(),
                                r.eis(),
                                r.caseType(),
                                r.salaryYymm(),
                                r.approvalReference()));
    }

    @GetMapping("/v1/off-cycle/{id}")
    @PreAuthorize("@authz.gate('I-20','VIEW')")
    public ResponseEntity<OffCycleService.CaseView> get(@PathVariable @Positive Long id) {
        return noStore(service.get(contexts.current(), id));
    }

    @PostMapping(value = "/v1/off-cycle/{id}/order", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-20','CREATE')")
    public ResponseEntity<DocumentStore.AttachmentView> order(
            @PathVariable @Positive Long id,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        service.attachOrder(
                                contexts.current(),
                                id,
                                documentType,
                                reference,
                                orderDate,
                                issuingAuthority,
                                file.getOriginalFilename(),
                                bytes(file)));
    }

    @PostMapping("/v1/off-cycle/{id}/submit")
    @PreAuthorize("@authz.gate('I-20','SUBMIT')")
    public ResponseEntity<OffCycleService.CaseView> submit(@PathVariable @Positive Long id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(service.submit(contexts.current(), id));
    }

    @PostMapping("/v1/off-cycle/{id}/cancel")
    @PreAuthorize("@authz.gate('I-20','SUBMIT')")
    public ResponseEntity<OffCycleService.CaseView> cancel(
            @PathVariable @Positive Long id, @Valid @RequestBody ReasonRequest r) {
        return noStore(service.cancel(contexts.current(), id, r.reason()));
    }

    @GetMapping("/v1/off-cycle/{id}/orders")
    @PreAuthorize("@authz.gate('I-20','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> orders(
            @PathVariable @Positive Long id) {
        return noStore(service.orders(contexts.current(), id));
    }

    /** Downloads the order; served as an attachment so the browser never renders it inline. */
    @GetMapping("/v1/off-cycle/{id}/files/{fileId}")
    @PreAuthorize("@authz.gate('I-20','VIEW')")
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
