package in.gov.fci.payroll.general.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.general.orchestration.FallbackService;
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
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
 * X-07 HRMS Fallback Upload (SRS-G-46, SRS-G-47, SRS-EH-11, BR-INT-011). The Checker's approval
 * with OTP happens in the Approvals Inbox (W-01 / X-02), like every maker-checker item.
 *
 * <pre>
 * GET  /v1/hrms-fallback/enablements                       list                     X-07 VIEW
 * GET  /v1/hrms-fallback/enablements/live                  open for an upload       X-07 VIEW
 * GET  /v1/hrms-fallback/summary                           tiles                    X-07 VIEW
 * POST /v1/hrms-fallback/enablements                       propose (HQ)             X-07 CREATE  -> X07_CREATE approval
 * POST /v1/hrms-fallback/enablements/{id}/withdraw         withdraw with reason     X-07 CREATE
 * GET  /v1/hrms-fallback/uploads                           uploads in scope         X-07 VIEW
 * GET  /v1/hrms-fallback/uploads/export                    the list as CSV          X-07 EXPORT
 * POST /v1/hrms-fallback/uploads (multipart)               upload the HRMS export   X-07 SUBMIT
 * GET  /v1/hrms-fallback/uploads/{id}/lines                validation result        X-07 VIEW
 * GET  /v1/hrms-fallback/uploads/{id}/rejections           rejected, by reason      X-07 VIEW
 * GET  /v1/hrms-fallback/uploads/{id}/orders               order copies             X-07 VIEW
 * POST /v1/hrms-fallback/uploads/{id}/order (multipart)    order copy, fallback (b) X-07 SUBMIT
 * POST /v1/hrms-fallback/uploads/{id}/submit               to the Checker (OTP)     X-07 SUBMIT  -> X07_SUBMIT approval
 * POST /v1/hrms-fallback/uploads/{id}/cancel               cancel with reason       X-07 SUBMIT
 * GET  /v1/hrms-fallback/uploads/{id}/files/{fileId}       source file / order      X-07 VIEW
 * </pre>
 *
 * No DELETE: uploads and files are cancelled, never deleted (SRS-G-37).
 */
@RestController
@Validated
@Profile("online")
public class FallbackController {

    public record EnableRequest(
            @NotBlank @Pattern(regexp = "^(ATTENDANCE_LEAVE|SUSPENSION_ABSCONDING)$") String type,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String payrollYymm,
            @NotEmpty @Size(max = 200)
                    List<@Pattern(regexp = "^[A-Za-z0-9-]{2,20}$") String> offices,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            Instant hrmsDownSince) {}

    public record ReasonRequest(@Size(max = 1000) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final FallbackService service;

    private final ExportStreaming exports;
    private final Paging paging;

    public FallbackController(
            ExportStreaming exports,
            CurrentAccess contexts,
            FallbackService service,
            Paging paging) {
        this.paging = paging;
        this.exports = exports;
        this.contexts = contexts;
        this.service = service;
    }

    @GetMapping("/v1/hrms-fallback/enablements")
    @PreAuthorize("@authz.gate('X-07','VIEW')")
    public ResponseEntity<Paged<FallbackService.EnablementView>> enablements(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(service.enablements(contexts.current(), paging.resolve(page, size)));
    }

    /** The enablements still open for an upload (the upload form's choice; not paged). */
    @GetMapping("/v1/hrms-fallback/enablements/live")
    @PreAuthorize("@authz.gate('X-07','VIEW')")
    public ResponseEntity<List<FallbackService.EnablementView>> liveEnablements() {
        return noStore(service.liveEnablements(contexts.current()));
    }

    /** X-07 tiles over the whole scope. */
    @GetMapping("/v1/hrms-fallback/summary")
    @PreAuthorize("@authz.gate('X-07','VIEW')")
    public ResponseEntity<FallbackService.Summary> summary() {
        return noStore(service.summary(contexts.current()));
    }

    @PostMapping("/v1/hrms-fallback/enablements")
    @PreAuthorize("@authz.gate('X-07','CREATE')")
    public ResponseEntity<Map<String, Long>> enable(@Valid @RequestBody EnableRequest r) {
        Long approval =
                service.proposeEnablement(
                        contexts.current(),
                        r.type(),
                        r.payrollYymm(),
                        r.offices(),
                        r.reason(),
                        r.hrmsDownSince());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("approvalId", approval));
    }

    @PostMapping("/v1/hrms-fallback/enablements/{id}/withdraw")
    @PreAuthorize("@authz.gate('X-07','CREATE')")
    public ResponseEntity<FallbackService.EnablementView> withdraw(
            @PathVariable @Positive Long id, @Valid @RequestBody ReasonRequest r) {
        return noStore(service.withdraw(contexts.current(), id, r.reason()));
    }

    @GetMapping("/v1/hrms-fallback/uploads")
    @PreAuthorize("@authz.gate('X-07','VIEW')")
    public ResponseEntity<Paged<FallbackService.UploadView>> uploads(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) @Positive Long enablementId) {
        return noStore(
                service.uploads(contexts.current(), paging.resolve(page, size), enablementId));
    }

    @PostMapping(
            value = "/v1/hrms-fallback/uploads",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('X-07','SUBMIT')")
    public ResponseEntity<FallbackService.UploadView> upload(
            @RequestParam @NotNull @Positive Long enablementId,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        service.upload(
                                contexts.current(),
                                enablementId,
                                file.getOriginalFilename(),
                                bytes(file)));
    }

    @GetMapping("/v1/hrms-fallback/uploads/export")
    @PreAuthorize("@authz.gate('X-07','EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(service.exportSize(context));
        return exports.stream(
                "hrms-fallback-uploads", format, sink -> service.export(context, sink));
    }

    @GetMapping("/v1/hrms-fallback/uploads/{id}/lines")
    @PreAuthorize("@authz.gate('X-07','VIEW')")
    public ResponseEntity<Paged<FallbackService.LineView>> lines(
            @PathVariable @Positive Long id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(service.lines(contexts.current(), id, paging.resolve(page, size)));
    }

    @GetMapping("/v1/hrms-fallback/uploads/{id}/rejections")
    @PreAuthorize("@authz.gate('X-07','VIEW')")
    public ResponseEntity<List<FallbackService.Rejection>> rejections(
            @PathVariable @Positive Long id) {
        return noStore(service.rejections(contexts.current(), id));
    }

    @PostMapping(
            value = "/v1/hrms-fallback/uploads/{id}/order",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('X-07','SUBMIT')")
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

    @PostMapping("/v1/hrms-fallback/uploads/{id}/submit")
    @PreAuthorize("@authz.gate('X-07','SUBMIT')")
    public ResponseEntity<FallbackService.UploadView> submit(
            @PathVariable @Positive Long id, @Valid @RequestBody ReasonRequest r) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(service.submit(contexts.current(), id, r.reason()));
    }

    @PostMapping("/v1/hrms-fallback/uploads/{id}/cancel")
    @PreAuthorize("@authz.gate('X-07','SUBMIT')")
    public ResponseEntity<FallbackService.UploadView> cancel(
            @PathVariable @Positive Long id, @Valid @RequestBody ReasonRequest r) {
        return noStore(service.cancel(contexts.current(), id, r.reason()));
    }

    @GetMapping("/v1/hrms-fallback/uploads/{id}/orders")
    @PreAuthorize("@authz.gate('X-07','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> orders(
            @PathVariable @Positive Long id) {
        return noStore(service.orders(contexts.current(), id));
    }

    /** Downloads a kept file; served as an attachment so the browser never renders it inline. */
    @GetMapping("/v1/hrms-fallback/uploads/{id}/files/{fileId}")
    @PreAuthorize("@authz.gate('X-07','VIEW')")
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
