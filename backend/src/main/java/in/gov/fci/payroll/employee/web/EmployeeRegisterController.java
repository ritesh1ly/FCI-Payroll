package in.gov.fci.payroll.employee.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.orchestration.EmployeeRegisters;
import in.gov.fci.payroll.employee.orchestration.HrmsChangeService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The reads of M-01, I-17, M-11 and I-21 as Wireframes v6.1 draws them (OD-121; PENDING API-16-8;
 * SRS-1-28, SRS-1-63, SRS-1-67, SRS-1-70, SRS-1-71, SRS-1-76, SRS-16-7). Every endpoint carries its
 * screen gate; the service bounds the rows by the session's data scope. A read only: no HRMS-owned
 * value is written here (R-SRC); the one write stores an order copy with the record (SRS-G-30).
 */
@RestController
@Validated
@Profile("online")
public class EmployeeRegisterController {

    private static final String EIS = "^[A-Za-z0-9-]{1,20}$";
    private static final String OFFICE = "^[A-Za-z0-9-]{2,20}$";

    private final CurrentAccess contexts;
    private final EmployeeRegisters registers;
    private final ExportStreaming exports;
    private final Paging paging;

    public EmployeeRegisterController(
            CurrentAccess contexts,
            EmployeeRegisters registers,
            ExportStreaming exports,
            Paging paging) {
        this.contexts = contexts;
        this.registers = registers;
        this.exports = exports;
        this.paging = paging;
    }

    // ---------------------------------------------------------------- M-01

    @GetMapping("/v1/employees/offices")
    @PreAuthorize("@authz.gate('M-01','VIEW')")
    public ResponseEntity<List<EmployeeRegisters.Office>> offices() {
        return noStore(registers.offices(contexts.current()));
    }

    /** M-01 Export: the list as filtered; PAN and bank masked (SRS-1-56). */
    @GetMapping("/v1/employees/export")
    @PreAuthorize("@authz.gate('M-01','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportMaster(
            @RequestParam(required = false) @Size(max = 60) String q,
            @RequestParam(required = false) @Size(max = 10) String payPattern,
            @RequestParam(required = false) @Pattern(regexp = EmployeeController.STATUS)
                    String status,
            @RequestParam(required = false) @Pattern(regexp = OFFICE) String office,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        return exports.stream(
                "employee-payroll-master",
                format,
                sink -> registers.exportMaster(context, q, payPattern, status, office, sink));
    }

    @GetMapping("/v1/employees/{eis}/member-ids")
    @PreAuthorize("@authz.gate('M-01','VIEW')")
    public ResponseEntity<Paged<EmployeeRegisters.MemberIdRow>> memberIds(
            @PathVariable @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        registers.memberIds(contexts.current(), eis), paging.resolve(page, size)));
    }

    @GetMapping("/v1/employees/{eis}/staff-numbers")
    @PreAuthorize("@authz.gate('M-01','VIEW')")
    public ResponseEntity<Paged<EmployeeRegisters.StaffNumberRow>> staffNumbers(
            @PathVariable @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        registers.staffNumbers(contexts.current(), eis),
                        paging.resolve(page, size)));
    }

    /** Record Detail's further fields and the live HRMS read (SRS-1-70; PENDING API-16-7). */
    @GetMapping("/v1/employees/{eis}/hrms-record")
    @PreAuthorize("@authz.gate('M-01','VIEW')")
    public ResponseEntity<EmployeeRegisters.RecordExtras> hrmsRecord(
            @PathVariable @Pattern(regexp = EIS) String eis) {
        return noStore(registers.recordExtras(contexts.current(), eis));
    }

    @GetMapping("/v1/employees/{eis}/documents")
    @PreAuthorize("@authz.gate('M-01','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> documents(
            @PathVariable @Pattern(regexp = EIS) String eis) {
        return noStore(registers.orders(contexts.current(), eis));
    }

    /** SRS-G-30: Establishment / HR stores a correction or salary release order (201). */
    @PostMapping(
            value = "/v1/employees/{eis}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('M-01','FLAG')")
    public ResponseEntity<DocumentStore.AttachmentView> attach(
            @PathVariable @Pattern(regexp = EIS) String eis,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam(required = false) @Size(max = 200) @PrintableAscii
                    String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        registers.attachOrder(
                                contexts.current(),
                                eis,
                                new HrmsChangeService.OrderCopy(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingAuthority,
                                        file.getOriginalFilename(),
                                        content)));
    }

    /** One stored order, served as an attachment so the browser never renders it inline. */
    @GetMapping("/v1/employees/{eis}/documents/{fileId}")
    @PreAuthorize("@authz.gate('M-01','VIEW')")
    public ResponseEntity<byte[]> document(
            @PathVariable @Pattern(regexp = EIS) String eis,
            @PathVariable @Min(1) long fileId,
            HttpServletRequest request) {
        DocumentStore.Content c =
                registers.order(contexts.current(), request.getRemoteAddr(), eis, fileId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(c.attachment().fileName())
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(c.attachment().contentType()))
                .body(c.bytes());
    }

    // ---------------------------------------------------------------- I-17

    @GetMapping("/v1/lifecycle-events/received")
    @PreAuthorize("@authz.gate('I-17','VIEW')")
    public ResponseEntity<Paged<EmployeeRegisters.ReceivedEvent>> events(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(registers.events(contexts.current(), false, paging.resolve(page, size)));
    }

    /** I-17 Export; {@code report=true} is the Suspension / Revocation Report (SRS l.3869). */
    @GetMapping("/v1/lifecycle-events/export")
    @PreAuthorize("@authz.gate('I-17','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportEvents(
            @RequestParam(defaultValue = "false") boolean report,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        return exports.stream(
                report ? "suspension-revocation-report" : "life-cycle-events",
                format,
                sink -> registers.exportEvents(context, report, sink));
    }

    // ---------------------------------------------------------------- M-11

    @GetMapping("/v1/transfers/register")
    @PreAuthorize("@authz.gate('M-11','VIEW')")
    public ResponseEntity<Paged<EmployeeRegisters.TransferRow>> transfers(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(registers.transfers(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/transfers/summary")
    @PreAuthorize("@authz.gate('M-11','VIEW')")
    public ResponseEntity<EmployeeRegisters.TransferSummary> transferSummary() {
        return noStore(registers.transferSummary(contexts.current()));
    }

    @GetMapping("/v1/transfers/export")
    @PreAuthorize("@authz.gate('M-11','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportTransfers(
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        return exports.stream(
                "employee-transfer-register",
                format,
                sink -> registers.exportTransfers(context, sink));
    }

    // ---------------------------------------------------------------- I-21

    @GetMapping("/v1/hrms-change-tasks/summary")
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<EmployeeRegisters.TaskSummary> taskSummary() {
        return noStore(registers.taskSummary(contexts.current()));
    }

    @GetMapping("/v1/hrms-change-tasks/register")
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<Paged<EmployeeRegisters.RegisterRow>> register(
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(registers.register(contexts.current(), q, paging.resolve(page, size)));
    }

    @GetMapping("/v1/hrms-change-tasks/field-groups")
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<List<EmployeeRegisters.FieldGroup>> fieldGroups() {
        return noStore(registers.fieldGroups(contexts.current()));
    }

    @GetMapping("/v1/hrms-change-tasks/dob-tasks")
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<Paged<EmployeeRegisters.DobTask>> dobTasks(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(registers.dobTasks(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/hrms-change-orders")
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<Paged<EmployeeRegisters.BulkOrder>> orders(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(registers.orders(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/hrms-change-orders/{id}/tasks")
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<Paged<EmployeeRegisters.OrderTask>> orderTasks(
            @PathVariable @Min(1) long id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(registers.orderTasks(contexts.current(), id, paging.resolve(page, size)));
    }
}
