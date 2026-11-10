package in.gov.fci.payroll.penalty.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.penalty.business.model.PenaltyRevoke;
import in.gov.fci.payroll.penalty.orchestration.OverAndAboveService;
import in.gov.fci.payroll.penalty.orchestration.PenaltyChangeService;
import in.gov.fci.payroll.penalty.orchestration.PenaltyOrderService;
import in.gov.fci.payroll.penalty.orchestration.PenaltyQueryService;
import in.gov.fci.payroll.penalty.orchestration.PenaltyRegisterService;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * I-06 Penalty, Recovery &amp; Over-and-Above (plan 12 phases 12b / 12g; SRS-14-1, SRS-14-7,
 * SRS-14-10, SRS-14-12, SRS-14-15, SRS-14-18). Tracker API-12-1.
 *
 * <pre>
 * GET  /v1/penalties?eis&amp;status&amp;page&amp;size   the orders of the unit        I-06 VIEW
 * GET  /v1/penalties/{id}                     one order with its schedule    I-06 VIEW
 * GET  /v1/penalties/requests?page&amp;size       pending and returned requests  I-06 VIEW
 * POST /v1/penalties/previews                 the checks and the schedule    I-06 CREATE
 * POST /v1/penalties  (multipart)             the order with its copy (202)  I-06 CREATE
 * POST /v1/penalties/{id}/revokes  (multipart)       a Penalty Revoke (202)   I-06 REVOKE
 * POST /v1/penalties/{id}/cancellations  (multipart) a cancellation (202)     I-06 CANCEL
 * GET  /v1/penalties/report?from&amp;to&amp;format          the Penalty Report       I-06 EXPORT
 * GET  /v1/penalties/over-and-above                  the unit's payments      I-06 VIEW
 * POST /v1/penalties/over-and-above                  a payment (202)          I-06 OVER_ABOVE
 * POST /v1/penalties/over-and-above/{id}/cancellations  its cancellation (202) I-06 CANCEL
 * GET  /v1/penalties/counts                          the tiles and figures    I-06 VIEW
 * GET  /v1/penalties/register?page&amp;size              orders, payments, requests I-06 VIEW
 * GET  /v1/penalties/min-net?approvalId              the Min-Net Guard        I-06 VIEW
 * GET  /v1/penalties/{id}/orders                     the order's copies       I-06 VIEW
 * GET  /v1/penalties/{id}/files/{fileId}             one copy                 I-06 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/penalties")
public class PenaltyController {

    private static final String EIS = "^[A-Za-z0-9-]{1,20}$";
    private static final String CODE = "^[A-Z_]{1,40}$";
    private static final String YYMM = "^\\d{4}(0[1-9]|1[0-2])$";

    /** An order as the Maker enters it from the order copy. */
    public record OrderBody(
            @NotNull @Pattern(regexp = EIS) String eis,
            @NotNull @Pattern(regexp = CODE) String nature,
            @NotBlank @Size(max = 60) @PrintableAscii String orderReference,
            @NotNull LocalDate effectiveDate,
            // Required for a nature that recovers; the service refuses them on any other.
            @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @Min(1) @Max(999) Integer instalments,
            @Pattern(regexp = YYMM) String startYymm,
            // SRS l.2995: defer to F&F instead of breaching the floor (optional, default false).
            Boolean deferToFnf,
            // SRS-14-13: the end of a labour order that recovers nothing (optional).
            LocalDate periodTo) {

        PenaltyOrderService.Request request() {
            return new PenaltyOrderService.Request(
                    eis,
                    nature,
                    orderReference,
                    effectiveDate,
                    amount,
                    instalments == null ? 0 : instalments,
                    startYymm,
                    deferToFnf,
                    periodTo);
        }
    }

    /** A Penalty Revoke as the Maker enters it from the revoke order (SRS-14-16). */
    public record RevokeBody(
            @NotNull PenaltyRevoke.Type type,
            @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @Pattern(regexp = YYMM) String fromYymm,
            @Pattern(regexp = YYMM) String toYymm,
            @NotBlank @Size(max = 100) @PrintableAscii String orderReference,
            @NotNull LocalDate orderDate,
            @NotBlank @Size(max = 1000) @PrintableAscii String reason) {

        PenaltyChangeService.Revoke revoke() {
            return new PenaltyChangeService.Revoke(
                    type, amount, fromYymm, toYymm, orderReference, orderDate, reason);
        }
    }

    /** An over-and-above payment as the Maker enters it (SRS-14-2). */
    public record PaymentBody(
            @NotNull @Pattern(regexp = EIS) String eis,
            @NotNull @Min(1) @Max(99999) Integer payCode,
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotNull @Pattern(regexp = YYMM) String payrollYymm,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            LocalDate applicationDate,
            @NotBlank @Size(max = 60) @PrintableAscii String sanctionReference,
            LocalDate sanctionDate) {

        OverAndAboveService.Request request() {
            return new OverAndAboveService.Request(
                    eis,
                    payCode,
                    amount,
                    payrollYymm,
                    reason,
                    applicationDate,
                    sanctionReference,
                    sanctionDate);
        }
    }

    /** A cancellation (SRS-14-20). */
    public record CancelBody(@NotBlank @Size(max = 1000) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final PenaltyOrderService orders;
    private final PenaltyQueryService queries;
    private final PenaltyChangeService changes;
    private final OverAndAboveService payments;
    private final PenaltyRegisterService register;
    private final Paging paging;
    private final ExportStreaming exports;

    public PenaltyController(
            CurrentAccess contexts,
            PenaltyOrderService orders,
            PenaltyQueryService queries,
            PenaltyChangeService changes,
            OverAndAboveService payments,
            PenaltyRegisterService register,
            Paging paging,
            ExportStreaming exports) {
        this.contexts = contexts;
        this.orders = orders;
        this.queries = queries;
        this.changes = changes;
        this.payments = payments;
        this.register = register;
        this.paging = paging;
        this.exports = exports;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('I-06','VIEW')")
    public ResponseEntity<Paged<PenaltyQueryService.OrderRow>> list(
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) @Pattern(regexp = CODE) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(queries.orders(contexts.current(), eis, status, paging.resolve(page, size)));
    }

    /** The tiles and panel figures of the session's unit (API-12-3). */
    @GetMapping("/counts")
    @PreAuthorize("@authz.gate('I-06','VIEW')")
    public ResponseEntity<PenaltyRegisterService.Counts> counts() {
        return noStore(register.counts(contexts.current()));
    }

    /** The drawn Order Register: requests, orders and over-and-above payments (API-12-3). */
    @GetMapping("/register")
    @PreAuthorize("@authz.gate('I-06','VIEW')")
    public ResponseEntity<Paged<PenaltyRegisterService.RegisterRow>> register(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(register.register(contexts.current(), paging.resolve(page, size)));
    }

    /** SRS-14-10: the Min-Net Guard of a request, or of the latest one waiting; 204 when none. */
    @GetMapping("/min-net")
    @PreAuthorize("@authz.gate('I-06','VIEW')")
    public ResponseEntity<PenaltyRegisterService.MinNetGuard> minNet(
            @RequestParam(required = false) @Min(1) Long approvalId) {
        return register.guard(contexts.current(), approvalId)
                .map(g -> noStore(g))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** SRS-14-12, SRS-G-31: the order copies stored with an order (the drawn card). */
    @GetMapping("/{id}/orders")
    @PreAuthorize("@authz.gate('I-06','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> orderCopies(
            @PathVariable @Min(1) long id) {
        return noStore(register.orderCopies(contexts.current(), id));
    }

    /** One order copy, as an attachment so the browser never renders it inline. */
    @GetMapping("/{id}/files/{fileId}")
    @PreAuthorize("@authz.gate('I-06','VIEW')")
    public ResponseEntity<byte[]> orderCopy(
            @PathVariable @Min(1) long id,
            @PathVariable @Min(1) long fileId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                register.orderCopy(contexts.current(), request.getRemoteAddr(), id, fileId);
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

    /** SRS-14-19: the Penalty Report for the months from .. to (Excel or CSV). */
    @GetMapping("/report")
    @PreAuthorize("@authz.gate('I-06','EXPORT')")
    public ResponseEntity<StreamingResponseBody> report(
            @RequestParam @Pattern(regexp = YYMM) String from,
            @RequestParam @Pattern(regexp = YYMM) String to,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(queries.reportSize(context, from, to));
        return exports.stream(
                "penalty-report-" + from + "-" + to,
                format,
                sink -> queries.report(context, from, to, sink));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.gate('I-06','VIEW')")
    public ResponseEntity<PenaltyQueryService.Detail> detail(@PathVariable @Min(1) long id) {
        return noStore(queries.detail(contexts.current(), id));
    }

    @GetMapping("/requests")
    @PreAuthorize("@authz.gate('I-06','VIEW')")
    public ResponseEntity<Paged<PenaltyQueryService.Request>> requests(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(queries.requests(contexts.current(), paging.resolve(page, size)));
    }

    /** SRS-14-7, SRS-14-10, SRS-14-18: every check, the schedule, the ceiling and the floor. */
    @PostMapping("/previews")
    @PreAuthorize("@authz.gate('I-06','CREATE')")
    public ResponseEntity<PenaltyOrderService.Preview> preview(@Valid @RequestBody OrderBody body) {
        return noStore(orders.preview(contexts.current(), body.request()));
    }

    /** SRS-14-1, SRS-14-12: the order with its copy goes to the Payroll Checker (202). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-06','CREATE')")
    public ResponseEntity<SubmittedApproval> submit(
            @Valid @RequestPart("order") OrderBody body,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        SubmittedApproval submitted =
                orders.submit(
                        contexts.current(),
                        body.request(),
                        new PenaltyOrderService.Copy(
                                documentType,
                                reference,
                                orderDate,
                                issuingAuthority,
                                file.getOriginalFilename(),
                                bytes(file)));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(submitted);
    }

    /** SRS-14-16: a Penalty Revoke with its revoke order goes to the Payroll Checker (202). */
    @PostMapping(value = "/{id}/revokes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-06','REVOKE')")
    public ResponseEntity<SubmittedApproval> revoke(
            @PathVariable @Min(1) long id,
            @Valid @RequestPart("revoke") RevokeBody body,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        changes.revoke(
                                contexts.current(),
                                id,
                                body.revoke(),
                                copy(documentType, reference, orderDate, issuingAuthority, file)));
    }

    /** SRS-14-20: a cancellation with its order goes to the Payroll Checker (202). */
    @PostMapping(value = "/{id}/cancellations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-06','CANCEL')")
    public ResponseEntity<SubmittedApproval> cancel(
            @PathVariable @Min(1) long id,
            @Valid @RequestPart("cancellation") CancelBody body,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        changes.cancel(
                                contexts.current(),
                                id,
                                body.reason(),
                                copy(documentType, reference, orderDate, issuingAuthority, file)));
    }

    @GetMapping("/over-and-above")
    @PreAuthorize("@authz.gate('I-06','VIEW')")
    public ResponseEntity<Paged<OverAndAboveService.PaymentView>> payments(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(payments.payments(contexts.current()), paging.resolve(page, size)));
    }

    /** SRS-14-2: the payment goes to the Payroll Checker before posting (202). */
    @PostMapping("/over-and-above")
    @PreAuthorize("@authz.gate('I-06','OVER_ABOVE')")
    public ResponseEntity<SubmittedApproval> pay(@Valid @RequestBody PaymentBody body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(payments.submit(contexts.current(), body.request()));
    }

    /** SRS-14-20, SRS-G-37: the cancellation of a payment until its invoice is sent (202). */
    @PostMapping("/over-and-above/{id}/cancellations")
    @PreAuthorize("@authz.gate('I-06','CANCEL')")
    public ResponseEntity<SubmittedApproval> cancelPayment(
            @PathVariable @Min(1) long id, @Valid @RequestBody CancelBody body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(payments.cancel(contexts.current(), id, body.reason()));
    }

    private static PenaltyOrderService.Copy copy(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingAuthority,
            MultipartFile file) {
        return new PenaltyOrderService.Copy(
                documentType,
                reference,
                orderDate,
                issuingAuthority,
                file.getOriginalFilename(),
                bytes(file));
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
