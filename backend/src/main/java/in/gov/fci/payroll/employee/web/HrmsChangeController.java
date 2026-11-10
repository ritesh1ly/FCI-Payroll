package in.gov.fci.payroll.employee.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.orchestration.HrmsChangeRegister;
import in.gov.fci.payroll.employee.orchestration.HrmsChangeService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
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
 * I-21 HRMS Change Approval (plan 16 phase 16a; SRS-1-28). No endpoint creates a task or edits a
 * value: tasks come from HRMS (`employee.api.HrmsChanges`). The SRS names the screen, not the API
 * (PENDING API-16-1). The Checker decides on W-01.
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/hrms-change-tasks")
public class HrmsChangeController {

    private static final String CODE = "^[A-Z_]{1,40}$";
    private static final String EIS = "^[A-Za-z0-9-]{1,20}$";

    /** SRS-1-28 / SRS-G-31: the order behind the change, or "held in HRMS". */
    public record OrderBody(
            @Size(max = 60) @PrintableAscii String orderNo,
            LocalDate orderDate,
            @Size(max = 200) @PrintableAscii String issuingAuthority,
            boolean heldInHrms) {}

    public record Submitted(long approvalId) {}

    private final CurrentAccess contexts;
    private final HrmsChangeService changes;
    private final HrmsChangeRegister registers;
    private final ExportStreaming exports;
    private final Paging paging;

    public HrmsChangeController(
            CurrentAccess contexts,
            HrmsChangeService changes,
            HrmsChangeRegister registers,
            ExportStreaming exports,
            Paging paging) {
        this.contexts = contexts;
        this.changes = changes;
        this.registers = registers;
        this.exports = exports;
        this.paging = paging;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<Paged<HrmsChangeService.TaskView>> list(
            @RequestParam(required = false) @Pattern(regexp = CODE) String status,
            @RequestParam(required = false) @Pattern(regexp = CODE) String field,
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(defaultValue = "false") boolean nameDob,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                changes.page(
                        contexts.current(),
                        new HrmsChangeService.Filter(status, field, eis, nameDob, q),
                        paging.resolve(page, size)));
    }

    /** SRS-1-30: the counts of the register by status and by field, for the same filters. */
    @GetMapping("/counts")
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<HrmsChangeRegister.Counts> counts(
            @RequestParam(required = false) @Pattern(regexp = CODE) String status,
            @RequestParam(required = false) @Pattern(regexp = CODE) String field,
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(defaultValue = "false") boolean nameDob) {
        return noStore(
                registers.counts(
                        contexts.current(),
                        new HrmsChangeService.Filter(status, field, eis, nameDob)));
    }

    /**
     * SRS-1-71 / SRS-1-30: the HRMS Change Register or the Name / DOB Change Register (Excel or
     * CSV), for the same filters.
     */
    @GetMapping("/export")
    @PreAuthorize("@authz.gate('I-21','EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(defaultValue = "HRMS") HrmsChangeRegister.Register register,
            @RequestParam(required = false) @Pattern(regexp = CODE) String status,
            @RequestParam(required = false) @Pattern(regexp = CODE) String field,
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        // The session is read here, on the request thread: the rows are written on an export
        // thread.
        AccessContext context = contexts.current();
        HrmsChangeService.Filter filter =
                new HrmsChangeService.Filter(
                        status, field, eis, register == HrmsChangeRegister.Register.NAME_DOB);
        exports.checkSize(registers.exportSize(context, filter));
        return exports.stream(
                register == HrmsChangeRegister.Register.NAME_DOB
                        ? "name-dob-change-register"
                        : "hrms-change-register",
                format,
                sink -> registers.export(context, filter, register, sink));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<HrmsChangeService.Detail> detail(@PathVariable @Min(1) long id) {
        return noStore(changes.detail(contexts.current(), id));
    }

    /**
     * SRS-1-28 / SRS-G-31 (US-GAP-5): the Maker attaches the order copy (201); after submission a
     * copy can only be added.
     */
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
        byte[] content;
        try {
            content = file.getBytes();
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        changes.attachOrder(
                                contexts.current(),
                                id,
                                new HrmsChangeService.OrderCopy(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingAuthority,
                                        file.getOriginalFilename(),
                                        content)));
    }

    /** The order copies of the task (the Checker compares the value with the order). */
    @GetMapping("/{id}/documents")
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<java.util.List<DocumentStore.AttachmentView>> documents(
            @PathVariable @Min(1) long id) {
        return noStore(changes.orderCopies(contexts.current(), id));
    }

    /** One order copy, served as an attachment so the browser never renders it inline. */
    @GetMapping("/{id}/documents/{fileId}")
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<byte[]> document(
            @PathVariable @Min(1) long id,
            @PathVariable @Min(1) long fileId,
            jakarta.servlet.http.HttpServletRequest request) {
        DocumentStore.Content c =
                changes.orderCopy(contexts.current(), request.getRemoteAddr(), id, fileId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                                .filename(c.attachment().fileName())
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(c.attachment().contentType()))
                .body(c.bytes());
    }

    /** SRS-1-28: the Maker records the order details and sends the task to the Checker (202). */
    @PostMapping("/{id}/submissions")
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<Submitted> submit(
            @PathVariable @Min(1) long id, @Valid @RequestBody OrderBody body) {
        long approval =
                changes.submit(
                        contexts.current(),
                        id,
                        new HrmsChangeService.OrderDetails(
                                body.orderNo(),
                                body.orderDate(),
                                body.issuingAuthority(),
                                body.heldInHrms()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(new Submitted(approval));
    }
}
