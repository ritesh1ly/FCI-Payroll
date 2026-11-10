package in.gov.fci.payroll.loans.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.loans.orchestration.LoanChangeService;
import in.gov.fci.payroll.loans.orchestration.LoanOrderService;
import in.gov.fci.payroll.loans.orchestration.LoanQueryService;
import in.gov.fci.payroll.loans.orchestration.LoanSanctionService;
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
 * I-04 Loans &amp; Advances (plan 09 phase 09b; SRS-12-1, SRS-12-7, SRS-12-8, SRS-12-11, SRS-12-17,
 * SRS-12-18). The SRS names the screen, not the API (PENDING API-09-1).
 *
 * <pre>
 * GET  /v1/loans                      the unit's loans               I-04 VIEW
 * GET  /v1/loans/{id}                 one loan with its schedules    I-04 VIEW
 * GET  /v1/loans/types                the types the Maker sets up    I-04 VIEW
 * GET  /v1/loans/requests             pending and returned requests  I-04 VIEW
 * GET  /v1/loans/summary?yymm=        the loan tiles                 I-04 VIEW
 * GET  /v1/loans/disbursements        advances paid and their invoice I-04 VIEW
 * GET  /v1/loans/{id}/orders          stored order copies            I-04 VIEW
 * GET  /v1/loans/{id}/orders/{fileId} one order copy                 I-04 VIEW
 * POST /v1/loans/{id}/orders          stores an order copy           I-04 LOAN_SANCTION
 * GET  /v1/loans/report?from=&to=     Loan Recovery & Outstanding    I-04 EXPORT
 * POST /v1/loans/sanctions/previews   the checks and the schedule    I-04 LOAN_SANCTION
 * POST /v1/loans/sanctions            the sanction with its order    I-04 LOAN_SANCTION (202)
 * POST /v1/loans/{id}/foreclosures    with the payment receipt       I-04 LOAN_FORECLOSE (202; OTP at approval)
 * POST /v1/loans/{id}/reschedules     with the signed request        I-04 LOAN_RESCHEDULE (202)
 * </pre>
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/loans")
public class LoanController {

    private static final String EIS = "^[A-Za-z0-9-]{1,20}$";
    private static final String CODE = "^[A-Z_]{1,30}$";
    private static final String YYMM = "^\\d{4}(0[1-9]|1[0-2])$";

    /** The terms of a sanction as the order states them. */
    public record SanctionBody(
            @NotNull @Pattern(regexp = EIS) String eis,
            @NotNull @Pattern(regexp = CODE) String loanType,
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal sanctioned,
            @Min(1) @Max(9999) int principalInstalments,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal instalment,
            @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal firstInstalment,
            @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal interestAmount,
            @Min(0) @Max(9999) Integer interestInstalments,
            @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal interestInstalment,
            @NotNull @Pattern(regexp = YYMM) String startYymm,
            Boolean reviewedOpenRequest) {

        LoanSanctionService.Proposal proposal() {
            return new LoanSanctionService.Proposal(
                    eis,
                    loanType,
                    sanctioned,
                    principalInstalments,
                    instalment,
                    firstInstalment,
                    interestAmount,
                    interestInstalments == null ? 0 : interestInstalments,
                    interestInstalment,
                    startYymm,
                    Boolean.TRUE.equals(reviewedOpenRequest));
        }
    }

    /** A reschedule's terms on the remaining balance (SRS-12-4). */
    public record RescheduleBody(
            @Min(1) @Max(9999) int principalInstalments,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal instalment,
            @Min(0) @Max(9999) Integer interestInstalments,
            @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal interestInstalment,
            @NotNull @Pattern(regexp = YYMM) String startYymm) {

        LoanChangeService.NewSchedule schedule() {
            return new LoanChangeService.NewSchedule(
                    principalInstalments,
                    instalment,
                    interestInstalments == null ? 0 : interestInstalments,
                    interestInstalment,
                    startYymm);
        }
    }

    private final CurrentAccess contexts;
    private final LoanSanctionService sanctions;
    private final LoanChangeService changes;
    private final LoanQueryService loans;
    private final LoanOrderService orders;
    private final Paging paging;
    private final ExportStreaming exports;

    public LoanController(
            CurrentAccess contexts,
            LoanSanctionService sanctions,
            LoanChangeService changes,
            LoanQueryService loans,
            LoanOrderService orders,
            Paging paging,
            ExportStreaming exports) {
        this.contexts = contexts;
        this.sanctions = sanctions;
        this.changes = changes;
        this.loans = loans;
        this.orders = orders;
        this.paging = paging;
        this.exports = exports;
    }

    /** SRS-12-26: the Loan Recovery & Outstanding Report for the months from .. to. */
    @GetMapping("/report")
    @PreAuthorize("@authz.gate('I-04','EXPORT')")
    public ResponseEntity<StreamingResponseBody> report(
            @RequestParam @Pattern(regexp = YYMM) String from,
            @RequestParam @Pattern(regexp = YYMM) String to,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(loans.reportSize(context, from, to));
        return exports.stream(
                "loan-recovery-outstanding-" + from + "-" + to,
                format,
                sink -> loans.report(context, from, to, sink));
    }

    @GetMapping
    @PreAuthorize("@authz.gate('I-04','VIEW')")
    public ResponseEntity<Paged<LoanQueryService.LoanRow>> list(
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) @Pattern(regexp = CODE) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(loans.loans(contexts.current(), eis, status, paging.resolve(page, size)));
    }

    /** SRS-12-1, SRS-12-3: the loan tiles of the month (a month without lines: its latest). */
    @GetMapping("/summary")
    @PreAuthorize("@authz.gate('I-04','VIEW')")
    public ResponseEntity<LoanQueryService.Summary> summary(
            @RequestParam @Pattern(regexp = YYMM) String yymm) {
        return noStore(loans.summary(contexts.current(), yymm));
    }

    /** BR-AUDIT-010: the advances paid out with their invoice. */
    @GetMapping("/disbursements")
    @PreAuthorize("@authz.gate('I-04','VIEW')")
    public ResponseEntity<Paged<LoanQueryService.Disbursement>> disbursements(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(loans.disbursements(contexts.current(), paging.resolve(page, size)));
    }

    /** SRS-G-30: the order copies stored with the loan. */
    @GetMapping("/{id}/orders")
    @PreAuthorize("@authz.gate('I-04','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> orders(
            @PathVariable @Min(1) long id) {
        return noStore(orders.list(contexts.current(), id));
    }

    /** Downloads an order; served as an attachment so the browser never renders it inline. */
    @GetMapping("/{id}/orders/{fileId}")
    @PreAuthorize("@authz.gate('I-04','VIEW')")
    public ResponseEntity<byte[]> orderFile(
            @PathVariable @Min(1) long id,
            @PathVariable @Min(1) long fileId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                orders.file(contexts.current(), request.getRemoteAddr(), id, fileId);
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

    /** SRS-G-30: stores an order copy with a loan set up on I-04. */
    @PostMapping(value = "/{id}/orders", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-04','LOAN_SANCTION')")
    public ResponseEntity<DocumentStore.AttachmentView> attachOrder(
            @PathVariable @Min(1) long id,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam(required = false) @Size(max = 200) @PrintableAscii
                    String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        orders.attach(
                                contexts.current(),
                                id,
                                document(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingAuthority,
                                        file)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.gate('I-04','VIEW')")
    public ResponseEntity<LoanQueryService.Detail> detail(@PathVariable @Min(1) long id) {
        return noStore(loans.detail(contexts.current(), id));
    }

    @GetMapping("/types")
    @PreAuthorize("@authz.gate('I-04','VIEW')")
    public ResponseEntity<List<LoanQueryService.LoanType>> types() {
        return noStore(loans.types());
    }

    @GetMapping("/requests")
    @PreAuthorize("@authz.gate('I-04','VIEW')")
    public ResponseEntity<Paged<LoanQueryService.Request>> requests(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(loans.requests(contexts.current(), paging.resolve(page, size)));
    }

    /** SRS-12-11 / SRS-12-14: every check, the schedule and the maximum new sanction. */
    @PostMapping("/sanctions/previews")
    @PreAuthorize("@authz.gate('I-04','LOAN_SANCTION')")
    public ResponseEntity<LoanSanctionService.Preview> preview(
            @Valid @RequestBody SanctionBody body) {
        return noStore(sanctions.preview(contexts.current(), body.proposal()));
    }

    /** SRS-12-17 / SRS-12-18: the sanction with its order goes to the Payroll Checker (202). */
    @PostMapping(value = "/sanctions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-04','LOAN_SANCTION')")
    public ResponseEntity<SubmittedApproval> submit(
            @Valid @RequestPart("sanction") SanctionBody body,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        SubmittedApproval submitted =
                sanctions.submit(
                        contexts.current(),
                        body.proposal(),
                        new LoanSanctionService.Order(
                                documentType,
                                reference,
                                orderDate,
                                issuingAuthority,
                                file.getOriginalFilename(),
                                bytes(file)));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(submitted);
    }

    /**
     * SRS-12-21 / SRS-12-18: the foreclosure with its order and the payment receipt; the Checker
     * approves with OTP (202). Without the order the submission is blocked (US-GAP-202).
     */
    @PostMapping(value = "/{id}/foreclosures", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-04','LOAN_FORECLOSE')")
    public ResponseEntity<SubmittedApproval> foreclose(
            @PathVariable @Min(1) long id,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z_]{2,40}$")
                    String orderDocumentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String orderReference,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderIssuedOn,
            @RequestParam(required = false) @Size(max = 200) @PrintableAscii String orderAuthority,
            @RequestPart(value = "orderFile", required = false) MultipartFile orderFile,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        changes.foreclose(
                                contexts.current(),
                                id,
                                order(
                                        orderDocumentType,
                                        orderReference,
                                        orderIssuedOn,
                                        orderAuthority,
                                        orderFile),
                                document(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingAuthority,
                                        file)));
    }

    /**
     * SRS-12-22 / SRS-12-18: the reschedule with its order and the employee's signed request (202).
     * Without the order the submission is blocked (US-GAP-202).
     */
    @PostMapping(value = "/{id}/reschedules", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-04','LOAN_RESCHEDULE')")
    public ResponseEntity<SubmittedApproval> reschedule(
            @PathVariable @Min(1) long id,
            @Valid @RequestPart("schedule") RescheduleBody body,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z_]{2,40}$")
                    String orderDocumentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String orderReference,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderIssuedOn,
            @RequestParam(required = false) @Size(max = 200) @PrintableAscii String orderAuthority,
            @RequestPart(value = "orderFile", required = false) MultipartFile orderFile,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        changes.reschedule(
                                contexts.current(),
                                id,
                                body.schedule(),
                                order(
                                        orderDocumentType,
                                        orderReference,
                                        orderIssuedOn,
                                        orderAuthority,
                                        orderFile),
                                document(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingAuthority,
                                        file)));
    }

    private static LoanChangeService.Document document(
            String type, String reference, LocalDate date, String authority, MultipartFile file) {
        return new LoanChangeService.Document(
                type, reference, date, authority, file.getOriginalFilename(), bytes(file));
    }

    /** US-GAP-202: the order of a foreclosure or reschedule, or null when none was sent. */
    private static LoanChangeService.Document order(
            String type, String reference, LocalDate date, String authority, MultipartFile file) {
        return file == null || file.isEmpty()
                ? null
                : new LoanChangeService.Document(
                        type, reference, date, authority, file.getOriginalFilename(), bytes(file));
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
