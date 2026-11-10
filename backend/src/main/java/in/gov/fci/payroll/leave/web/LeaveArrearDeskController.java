package in.gov.fci.payroll.leave.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.leave.orchestration.LeaveArrearDeskService;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.workflow.api.Submitted;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
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

/**
 * I-24 as Wireframes v6.1 draws it (OD-121; SRS-8-24, SRS-G-37, SRS-G-30, FRS §6.4.3): the SRS
 * names the objects, not the API (PENDING API-13-2).
 *
 * <pre>
 * GET  /v1/leave-encashment-arrears/summary              the tile of the latest order    I-24 VIEW
 * POST /v1/leave-encashment-arrears/{id}/cancellations   raise a cancellation (202)      I-24 SUBMIT
 * POST /v1/leave-encashment-arrears/{id}/documents       attach an order copy            I-24 SUBMIT
 * GET  /v1/leave-encashment-arrears/{id}/documents       the stored orders               I-24 VIEW
 * GET  /v1/leave-encashment-arrears/{id}/documents/{f}   one stored order (attachment)   I-24 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class LeaveArrearDeskController {

    public record CancellationBody(
            @NotNull @Size(min = 1, max = 500) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final LeaveArrearDeskService desk;

    public LeaveArrearDeskController(CurrentAccess contexts, LeaveArrearDeskService desk) {
        this.contexts = contexts;
        this.desk = desk;
    }

    @GetMapping("/v1/leave-encashment-arrears/summary")
    @PreAuthorize("@authz.gate('I-24','VIEW')")
    public ResponseEntity<LeaveArrearDeskService.Summary> summary() {
        return noStore(desk.summary(contexts.current()));
    }

    /** SRS-G-37: the Maker raises it; the Payroll Checker's approval cancels the record. */
    @PostMapping("/v1/leave-encashment-arrears/{id}/cancellations")
    @PreAuthorize("@authz.gate('I-24','SUBMIT')")
    public ResponseEntity<Submitted> cancel(
            @PathVariable @Positive long id, @Valid @RequestBody CancellationBody body) {
        SubmittedApproval item = desk.cancel(contexts.current(), id, body.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new Submitted(item.id(), item.status().name()));
    }

    /** I-24 "Order copy - attach &amp; store" (FRS §6.4.3, A-41, SRS-G-30). */
    @PostMapping(
            value = "/v1/leave-encashment-arrears/{id}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-24','SUBMIT')")
    public ResponseEntity<DocumentStore.AttachmentView> attachOrder(
            @PathVariable @Positive long id,
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
                        desk.attachOrder(
                                contexts.current(),
                                id,
                                new LeaveArrearDeskService.NewOrder(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingOffice,
                                        file.getOriginalFilename(),
                                        content)));
    }

    @GetMapping("/v1/leave-encashment-arrears/{id}/documents")
    @PreAuthorize("@authz.gate('I-24','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> orders(
            @PathVariable @Positive long id) {
        return noStore(desk.orders(contexts.current(), id));
    }

    /** One stored order, served as an attachment so the browser never renders it inline. */
    @GetMapping("/v1/leave-encashment-arrears/{id}/documents/{fileId}")
    @PreAuthorize("@authz.gate('I-24','VIEW')")
    public ResponseEntity<byte[]> order(
            @PathVariable @Positive long id,
            @PathVariable @Positive long fileId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                desk.order(contexts.current(), request.getRemoteAddr(), id, fileId);
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
