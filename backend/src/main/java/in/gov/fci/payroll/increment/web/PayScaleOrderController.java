package in.gov.fci.payroll.increment.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.increment.orchestration.PayScaleOrders;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The drawn order card of M-03 (Wireframes v6.1, FRS §6.4.3, A-41; SRS-G-30; API-17-1 in PENDING).
 *
 * <pre>
 * GET  /v1/pay-scales/{code}/orders             the copies stored with a scale   M-03 VIEW
 * POST /v1/pay-scales/{code}/orders             store a copy (Headquarters)      M-03 CREATE
 * GET  /v1/pay-scales/{code}/files/{fileId}     one stored copy                  M-03 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class PayScaleOrderController {

    private static final String CODE = "^[A-Za-z0-9-]{1,20}$";

    private final CurrentAccess contexts;
    private final PayScaleOrders orders;

    public PayScaleOrderController(CurrentAccess contexts, PayScaleOrders orders) {
        this.contexts = contexts;
        this.orders = orders;
    }

    /** SRS-G-30: the copies stored with a scale. */
    @GetMapping("/v1/pay-scales/{code}/orders")
    @PreAuthorize("@authz.gate('M-03','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> orders(
            @PathVariable @Pattern(regexp = CODE) String code) {
        return noStore(orders.orders(contexts.current(), code));
    }

    /** SRS-G-30: stores a copy; the service also requires Headquarters (D17-10). */
    @PostMapping(
            value = "/v1/pay-scales/{code}/orders",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('M-03','CREATE')")
    public ResponseEntity<DocumentStore.AttachmentView> attach(
            @PathVariable @Pattern(regexp = CODE) String code,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        DocumentStore.NewAttachment order =
                new DocumentStore.NewAttachment(
                        null,
                        null,
                        documentType,
                        reference,
                        orderDate,
                        issuingAuthority,
                        file.getOriginalFilename(),
                        content,
                        false);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(orders.attach(contexts.current(), code, order));
    }

    /** One stored copy, as an attachment so the browser never renders it inline. */
    @GetMapping("/v1/pay-scales/{code}/files/{fileId}")
    @PreAuthorize("@authz.gate('M-03','VIEW')")
    public ResponseEntity<byte[]> file(
            @PathVariable @Pattern(regexp = CODE) String code,
            @PathVariable @Positive long fileId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                orders.file(contexts.current(), request.getRemoteAddr(), code, fileId);
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
}
