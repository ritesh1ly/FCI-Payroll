package in.gov.fci.payroll.employee.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.orchestration.HrmsUpdateRequestDocuments;
import in.gov.fci.payroll.employee.orchestration.HrmsUpdateRequestService;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * HRMS Update Request (plan 16 phase 16c; SRS-1-70). The SRS names the screens, not the API
 * (PENDING API-16-1).
 *
 * <pre>
 * GET  /v1/hrms-update-requests/fields       the fields with pay effect             I-21 / E-01 VIEW
 * POST /v1/hrms-update-requests              the Maker raises it for an employee    I-21 REQUEST (service)
 * GET  /v1/hrms-update-requests              the unit's requests                    I-21 VIEW
 * POST /v1/hrms-update-requests/{id}/fetches fetch HRMS again after a failed fetch  I-21 REQUEST (service)
 * POST /v1/hrms-update-requests/mine         the employee raises it in eSeva        E-01 CREATE
 * GET  /v1/hrms-update-requests/mine         the employee's requests                E-01 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/hrms-update-requests")
public class HrmsUpdateRequestController {

    private static final String CODE = "^[A-Z_]{1,40}$";
    private static final String EIS = "^[A-Za-z0-9-]{1,20}$";

    /** SRS-1-70: the field, the HRMS reference if known and the effective date. */
    public record UpdateBody(
            @Pattern(regexp = EIS) String eis,
            @NotNull @Pattern(regexp = CODE) String fieldCode,
            @Size(max = 60) @PrintableAscii String hrmsReference,
            @NotNull LocalDate effectiveDate) {}

    private final CurrentAccess contexts;
    private final HrmsUpdateRequestService requests;
    private final HrmsUpdateRequestDocuments documents;
    private final Paging paging;

    public HrmsUpdateRequestController(
            CurrentAccess contexts,
            HrmsUpdateRequestService requests,
            HrmsUpdateRequestDocuments documents,
            Paging paging) {
        this.contexts = contexts;
        this.requests = requests;
        this.documents = documents;
        this.paging = paging;
    }

    // ---------------------------------------------------------------- the order copy (SRS-1-70)

    /** The I-21 desk attaches the order copy of a request of its cycle unit. */
    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<DocumentStore.AttachmentView> attach(
            @PathVariable @Min(1) long id,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        return created(
                documents.attach(
                        contexts.current(),
                        id,
                        false,
                        order(documentType, reference, orderDate, issuingAuthority, file)));
    }

    /** The member attaches the order copy of an own request (eSeva). */
    @PostMapping(value = "/mine/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('E-01','CREATE')")
    public ResponseEntity<DocumentStore.AttachmentView> attachMine(
            @PathVariable @Min(1) long id,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        return created(
                documents.attach(
                        contexts.current(),
                        id,
                        true,
                        order(documentType, reference, orderDate, issuingAuthority, file)));
    }

    @GetMapping("/{id}/documents")
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> documents(
            @PathVariable @Min(1) long id) {
        return noStore(documents.list(contexts.current(), id, false));
    }

    @GetMapping("/mine/{id}/documents")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> myDocuments(
            @PathVariable @Min(1) long id) {
        return noStore(documents.list(contexts.current(), id, true));
    }

    /** Downloads the order; served as an attachment so the browser never renders it inline. */
    @GetMapping("/{id}/documents/{fileId}")
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<byte[]> document(
            @PathVariable @Min(1) long id,
            @PathVariable @Min(1) long fileId,
            HttpServletRequest request) {
        return download(
                documents.read(contexts.current(), request.getRemoteAddr(), id, false, fileId));
    }

    @GetMapping("/mine/{id}/documents/{fileId}")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<byte[]> myDocument(
            @PathVariable @Min(1) long id,
            @PathVariable @Min(1) long fileId,
            HttpServletRequest request) {
        return download(
                documents.read(contexts.current(), request.getRemoteAddr(), id, true, fileId));
    }

    private static HrmsUpdateRequestDocuments.Order order(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingAuthority,
            MultipartFile file) {
        try {
            return new HrmsUpdateRequestDocuments.Order(
                    documentType,
                    reference,
                    orderDate,
                    issuingAuthority,
                    file.getOriginalFilename(),
                    file.getBytes());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static ResponseEntity<byte[]> download(DocumentStore.Content content) {
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

    @GetMapping("/fields")
    @PreAuthorize("@authz.gateEither('E-01','SELF','I-21','VIEW')")
    public ResponseEntity<List<HrmsUpdateRequestService.FieldOption>> fields() {
        return noStore(requests.fields());
    }

    @PostMapping
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<HrmsUpdateRequestService.RequestView> raise(
            @Valid @RequestBody UpdateBody body) {
        return created(requests.raiseForEmployee(contexts.current(), request(body)));
    }

    @GetMapping
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<Paged<HrmsUpdateRequestService.RequestView>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(requests.page(contexts.current(), paging.resolve(page, size)));
    }

    @PostMapping("/{id}/fetches")
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<HrmsUpdateRequestService.RequestView> refetch(
            @PathVariable @Min(1) long id) {
        return noStore(requests.refetch(contexts.current(), id));
    }

    @PostMapping("/mine")
    @PreAuthorize("@authz.gate('E-01','CREATE')")
    public ResponseEntity<HrmsUpdateRequestService.RequestView> raiseMine(
            @Valid @RequestBody UpdateBody body) {
        return created(
                requests.raiseMine(
                        contexts.current(),
                        new HrmsUpdateRequestService.NewRequest(
                                null,
                                body.fieldCode(),
                                body.hrmsReference(),
                                body.effectiveDate())));
    }

    @GetMapping("/mine")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<Paged<HrmsUpdateRequestService.RequestView>> mine(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(requests.mine(contexts.current(), paging.resolve(page, size)));
    }

    private static HrmsUpdateRequestService.NewRequest request(UpdateBody body) {
        return new HrmsUpdateRequestService.NewRequest(
                body.eis(), body.fieldCode(), body.hrmsReference(), body.effectiveDate());
    }

    private static <T> ResponseEntity<T> created(T body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
