package in.gov.fci.payroll.employee.web;

import in.gov.fci.payroll.employee.orchestration.HrmsOrderService;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * I-21 one order for many staff (plan 16 phase 16b; SRS-1-67, SRS-G-31; DDL {@code
 * hrms_change_task_order}): the Maker enters the order once for several tasks of one paying unit;
 * the Checker approves the whole order on W-01. The SRS names the screen, not the API (PENDING
 * API-16-1). The service checks I-21 ORDER or ORDER_OTP at the tasks' unit.
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/hrms-change-orders")
public class HrmsOrderController {

    /** At most this many tasks in one order (one Checker screen). */
    static final int MAX_TASKS = 200;

    public record OrderBody(
            @NotNull @Size(max = 60) @PrintableAscii String orderNo,
            @NotNull LocalDate orderDate,
            @NotNull @Size(max = 200) @PrintableAscii String issuingAuthority,
            @Size(max = 500) @PrintableAscii String subject,
            @NotNull @Size(min = 2, max = MAX_TASKS) List<@NotNull @Positive Long> taskIds,
            @Positive Long copyAttachmentId,
            @Size(max = 60)
                    @jakarta.validation.constraints.Pattern(
                            regexp = "^(PROVISIONAL:[0-9a-f-]{36})?$")
                    String copyProvisionalKey) {}

    private final CurrentAccess contexts;
    private final HrmsOrderService orders;

    public HrmsOrderController(CurrentAccess contexts, HrmsOrderService orders) {
        this.contexts = contexts;
        this.orders = orders;
    }

    /** SRS-1-67 / SRS-G-31: the order's one copy, stored before the order names it (201). */
    @PostMapping(
            value = "/copies",
            consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<HrmsOrderService.StoredCopy> storeCopy(
            @org.springframework.web.bind.annotation.RequestParam
                    @jakarta.validation.constraints.NotBlank
                    @jakarta.validation.constraints.Pattern(regexp = "^[A-Z_]{2,40}$")
                    String documentType,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                    @Size(max = 60)
                    @PrintableAscii
                    String reference,
            @org.springframework.web.bind.annotation.RequestParam
                    @NotNull
                    @org.springframework.format.annotation.DateTimeFormat(
                            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @org.springframework.web.bind.annotation.RequestParam
                    @jakarta.validation.constraints.NotBlank
                    @Size(max = 200)
                    @PrintableAscii
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
                .cacheControl(CacheControl.noStore())
                .body(
                        orders.storeCopy(
                                contexts.current(),
                                new in.gov.fci.payroll.employee.orchestration.HrmsChangeService
                                        .OrderCopy(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingAuthority,
                                        file.getOriginalFilename(),
                                        content)));
    }

    /** SRS-1-67: the order and its tasks sent to the Checker in one approval item (202). */
    @PostMapping
    @PreAuthorize("@authz.gate('I-21','VIEW')")
    public ResponseEntity<HrmsOrderService.Created> create(@Valid @RequestBody OrderBody body) {
        HrmsOrderService.Created created =
                orders.create(
                        contexts.current(),
                        new HrmsOrderService.NewOrder(
                                body.orderNo(),
                                body.orderDate(),
                                body.issuingAuthority(),
                                body.subject(),
                                body.taskIds(),
                                body.copyAttachmentId(),
                                body.copyProvisionalKey()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(created);
    }
}
