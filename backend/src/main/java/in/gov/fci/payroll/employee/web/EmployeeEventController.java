package in.gov.fci.payroll.employee.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.employee.orchestration.EmployeeEventService;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.workflow.api.Otp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
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

/**
 * I-17 Employee Life-Cycle Events and M-11 Staff Transfer (target DDL v4.0). Fixed gates per
 * endpoint; the service checks the employee record is in the session's data scope. I-17 is
 * read-only (SRS-1-63, I-17 l.3871-3875: "all are read-only on I-17", "No event is keyed in
 * Payroll"): the events arrive from HRMS through an I-21 task or from the X-07 fallback upload
 * (SRS-1-72), so there is no endpoint that records one. A recorded transfer answers 202 and changes
 * nothing until a Payroll Checker of the unit approves it.
 */
@RestController
@Validated
@Profile("online")
public class EmployeeEventController {

    private static final String EIS = "^[A-Za-z0-9-]{1,20}$";

    /**
     * An M-11 transfer as recorded. No LPC Reference: the M-11 field table makes it Read-only,
     * source System (SRS l.1352, "Last Pay Certificate linking the transfer to the pay record at
     * the origin"), so a request naming one is refused as an unknown field (R-SRC); it is filled
     * when the Last Pay Certificate is issued (SRS-18-4, PENDING 04 M-11 LPC).
     */
    public record TransferRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{2,20}$") String toLocation,
            @NotNull LocalDate transferDate,
            LocalDate relievingDate,
            LocalDate joiningDate,
            @Pattern(regexp = "^(\\d{2}:\\d{2})?$") String joiningTime,
            @Size(max = 100) @PrintableAscii String orderReference,
            @Pattern(regexp = "^([A-Za-z0-9-]{3,20})?$") String newStaffNumber,
            boolean splitWage,
            @Min(0) @Max(31) Integer fromUnitDays,
            @Min(0) @Max(31) Integer toUnitDays,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            // Optional (absent = No): a primitive would refuse every caller that omits it (Jackson
            // 3).
            Boolean orderHeldInHrms,
            @Positive Long orderAttachmentId,
            @Size(max = 60) @Pattern(regexp = "^(PROVISIONAL:[0-9a-f-]{36})?$")
                    String orderProvisionalKey,
            @Valid Otp otp) {}

    private final CurrentAccess contexts;
    private final EmployeeEventService service;
    private final Paging paging;

    public EmployeeEventController(
            CurrentAccess contexts, EmployeeEventService service, Paging paging) {
        this.paging = paging;
        this.contexts = contexts;
        this.service = service;
    }

    // ---------------------------------------------------------------- I-17

    @GetMapping("/v1/lifecycle-events")
    @PreAuthorize("@authz.gate('I-17','VIEW')")
    public ResponseEntity<Paged<EmployeeEventService.EventView>> recentEvents(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(service.recentEvents(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/employees/{eis}/lifecycle-events")
    @PreAuthorize("@authz.gate('I-17','VIEW')")
    public ResponseEntity<Paged<EmployeeEventService.EventView>> events(
            @PathVariable @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(service.events(contexts.current(), eis), paging.resolve(page, size)));
    }

    // ---------------------------------------------------------------- M-11

    @GetMapping("/v1/transfers")
    @PreAuthorize("@authz.gate('M-11','VIEW')")
    public ResponseEntity<Paged<EmployeeEventService.TransferView>> recentTransfers(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(service.recentTransfers(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/transfers/destinations")
    @PreAuthorize("@authz.gate('M-11','CREATE')")
    public ResponseEntity<List<EmployeeEventService.Destination>> destinations() {
        return noStore(service.destinations(contexts.current()));
    }

    @GetMapping("/v1/employees/{eis}/transfers")
    @PreAuthorize("@authz.gate('M-11','VIEW')")
    public ResponseEntity<Paged<EmployeeEventService.TransferView>> transfers(
            @PathVariable @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        service.transfers(contexts.current(), eis), paging.resolve(page, size)));
    }

    @PostMapping("/v1/employees/{eis}/transfers")
    @PreAuthorize("@authz.gate('M-11','CREATE')")
    public ResponseEntity<EmployeeEventService.TransferView> recordTransfer(
            @PathVariable @Pattern(regexp = EIS) String eis,
            @Valid @RequestBody TransferRequest r) {
        return accepted(
                service.recordTransfer(
                        contexts.current(),
                        eis,
                        new EmployeeEventService.NewTransfer(
                                r.toLocation(),
                                r.transferDate(),
                                r.relievingDate(),
                                r.joiningDate(),
                                r.joiningTime(),
                                r.orderReference(),
                                null,
                                r.newStaffNumber(),
                                r.splitWage(),
                                r.fromUnitDays(),
                                r.toUnitDays(),
                                r.reason(),
                                Boolean.TRUE.equals(r.orderHeldInHrms()),
                                r.orderAttachmentId(),
                                r.orderProvisionalKey()),
                        Otp.proof(r.otp())));
    }

    /**
     * SRS-16-18 / SRS-G-31 (US-GAP-26): the transfer order copy, stored before the transfer is
     * recorded (201): the transfer then names it.
     */
    @PostMapping(
            value = "/v1/employees/{eis}/transfer-orders",
            consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('M-11','CREATE')")
    public ResponseEntity<EmployeeEventService.StoredOrder> storeTransferOrder(
            @PathVariable @Pattern(regexp = EIS) String eis,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam
                    @NotNull
                    @org.springframework.format.annotation.DateTimeFormat(
                            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority,
            @org.springframework.web.bind.annotation.RequestPart("file")
                    org.springframework.web.multipart.MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        service.storeTransferOrder(
                                contexts.current(),
                                eis,
                                new EmployeeEventService.OrderCopy(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingAuthority,
                                        file.getOriginalFilename(),
                                        content)));
    }

    private static <T> ResponseEntity<T> accepted(T body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
