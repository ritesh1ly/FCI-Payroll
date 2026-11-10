package in.gov.fci.payroll.allowance.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.allowance.orchestration.CafeteriaQueryService;
import in.gov.fci.payroll.allowance.orchestration.ClaimBoardService;
import in.gov.fci.payroll.allowance.orchestration.ClaimCatalogue;
import in.gov.fci.payroll.allowance.orchestration.ClaimMaintenance;
import in.gov.fci.payroll.allowance.orchestration.ClaimQueryService;
import in.gov.fci.payroll.allowance.orchestration.LabourClaimService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * I-03 Allowances &amp; Reimbursements and I-16 Cafeteria Allowance (plan 10; SRS §6.12). Tracker
 * API-10-1.
 *
 * <pre>
 * GET  /v1/claims?memberType&amp;status&amp;claimType&amp;eis&amp;page&amp;size   Claim Register      I-03 VIEW
 * GET  /v1/claims/types?memberType                          claim types           I-03 VIEW
 * GET  /v1/claims/pending-hrms                              claims open on I-21   I-03 VIEW
 * GET  /v1/claims/medical?eis                               Medical Claim view    I-03 VIEW
 * GET  /v1/claims/{id}, /{id}/documents/{fileId}            one claim, a document I-03 VIEW
 * POST /v1/claims (multipart: claim + files)                labour claim entry    I-03 CREATE
 * POST /v1/claims/{id}/validations                          validate and submit   I-03 VALIDATE
 * POST /v1/claims/{id}/taxable-confirmations                taxable flag          I-03 EDIT
 * POST /v1/claims/{id}/iogas                                IOGA (approval)       I-03 EDIT
 * POST /v1/claims/{id}/documents                            supporting document   I-03 EDIT
 * GET  /v1/claims/counts                                    tiles of the month    I-03 VIEW
 * GET  /v1/claims/leases?page&amp;size                          accommodation records I-03 VIEW
 * GET  /v1/claims/ceiling-breaches                          M-15 tile             M-15 VIEW
 * GET  /v1/claims/register, /v1/claims/medical-register     registers (CSV/XLSX)  I-03 EXPORT
 * GET  /v1/cafeteria-selections?eis&amp;finYear&amp;page&amp;size      I-16 selections       I-16 VIEW
 * GET  /v1/cafeteria-selections/export?finYear&amp;eis&amp;format      I-16 list (CSV/XLSX)  I-16 EXPORT
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class ClaimController {

    private static final String EIS = "^[A-Za-z0-9]{1,20}$";
    private static final String CODE = "^[A-Z][A-Z0-9_]{0,29}$";
    private static final String STATUS =
            "^(SUBMITTED|VALIDATED|CAPPED|APPROVED|PAID|REJECTED|OVERLAP_REJECTED|CANCELLED)$";
    private static final String MEMBER = "^(EMPLOYEE|LABOUR)$";
    private static final String YYMM = "^\\d{6}$";

    /** One supporting document's particulars; the file is the part at the same index. */
    public record DocumentBody(
            @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @Size(max = 60) @PrintableAscii String reference,
            @NotNull LocalDate orderDate,
            @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority) {}

    /** A labour claim as the Labour Dealing Hand enters it. */
    public record ClaimBody(
            @NotBlank @Pattern(regexp = EIS) String memberNumber,
            @NotBlank @Pattern(regexp = CODE) String claimType,
            @NotNull LocalDate periodFrom,
            @NotNull LocalDate periodTo,
            @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal claimedAmount,
            @Size(max = 10) List<@Valid @NotNull DocumentBody> documents) {}

    public record ReasonBody(@Size(max = 500) @PrintableAscii String reason) {}

    public record TaxableBody(@NotNull Boolean taxable) {}

    public record IogaBody(
            @NotBlank @Size(max = 50) @PrintableAscii String number,
            @NotNull LocalDate date,
            @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 100) @PrintableAscii String originatingOffice,
            @Size(max = 500) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final LabourClaimService labour;
    private final ClaimQueryService queries;
    private final ClaimMaintenance maintenance;
    private final ClaimCatalogue catalogue;
    private final CafeteriaQueryService cafeteria;
    private final ClaimBoardService board;
    private final SystemParameters settings;
    private final ExportStreaming exports;
    private final Paging paging;

    @SuppressWarnings("java:S107") // one collaborator per concern, as the other controllers
    public ClaimController(
            CurrentAccess contexts,
            LabourClaimService labour,
            ClaimQueryService queries,
            ClaimMaintenance maintenance,
            ClaimCatalogue catalogue,
            CafeteriaQueryService cafeteria,
            ClaimBoardService board,
            SystemParameters settings,
            ExportStreaming exports,
            Paging paging) {
        this.contexts = contexts;
        this.labour = labour;
        this.queries = queries;
        this.maintenance = maintenance;
        this.catalogue = catalogue;
        this.cafeteria = cafeteria;
        this.board = board;
        this.settings = settings;
        this.exports = exports;
        this.paging = paging;
    }

    @GetMapping("/v1/claims")
    @PreAuthorize("@authz.gate('I-03','VIEW')")
    public ResponseEntity<Paged<ClaimQueryService.ClaimRow>> register(
            @RequestParam(required = false) @Pattern(regexp = MEMBER) String memberType,
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Pattern(regexp = CODE) String claimType,
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) @Pattern(regexp = YYMM) String yymm,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                queries.register(
                        contexts.current(),
                        new ClaimQueryService.Filter(memberType, status, claimType, eis, yymm),
                        paging.resolve(page, size)));
    }

    /** Drawn tiles of I-03: claims of the month, those with the Checker, capped, leases. */
    @GetMapping("/v1/claims/counts")
    @PreAuthorize("@authz.gate('I-03','VIEW')")
    public ResponseEntity<ClaimBoardService.Counts> counts() {
        return noStore(board.counts(contexts.current()));
    }

    /** SRS-11-17: the accommodation records in force, read-only. */
    @GetMapping("/v1/claims/leases")
    @PreAuthorize("@authz.gate('I-03','VIEW')")
    public ResponseEntity<Paged<ClaimBoardService.LeaseRow>> leases(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(board.leases(contexts.current(), paging.resolve(page, size)));
    }

    /** Drawn tile of M-15: claims capped at the entitlement this month. */
    @GetMapping("/v1/claims/ceiling-breaches")
    @PreAuthorize("@authz.gate('M-15','VIEW')")
    public ResponseEntity<Long> ceilingBreaches() {
        return noStore(board.ceilingBreaches(contexts.current()));
    }

    /** D10-1: the claim types of a member type in force today (LABOUR for the entry form). */
    @GetMapping("/v1/claims/types")
    @PreAuthorize("@authz.gate('I-03','VIEW')")
    public ResponseEntity<List<ClaimCatalogue.ClaimType>> types(
            @RequestParam(defaultValue = "LABOUR") @Pattern(regexp = MEMBER) String memberType) {
        return noStore(catalogue.types(memberType, settings.today()));
    }

    @GetMapping("/v1/claims/pending-hrms")
    @PreAuthorize("@authz.gate('I-03','VIEW')")
    public ResponseEntity<Paged<ClaimQueryService.PendingHrmsClaim>> pendingHrms(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(queries.pendingFromHrms(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/claims/medical")
    @PreAuthorize("@authz.gate('I-03','VIEW')")
    public ResponseEntity<List<ClaimQueryService.ClaimRow>> medical(
            @RequestParam @Pattern(regexp = EIS) String eis) {
        return noStore(queries.medical(contexts.current(), eis));
    }

    @GetMapping("/v1/claims/{id}")
    @PreAuthorize("@authz.gate('I-03','VIEW')")
    public ResponseEntity<ClaimQueryService.ClaimDetail> detail(@PathVariable @Min(1) long id) {
        return noStore(queries.detail(contexts.current(), id));
    }

    /** A supporting document; served as an attachment so the browser never renders it inline. */
    @GetMapping("/v1/claims/{id}/documents/{fileId}")
    @PreAuthorize("@authz.gate('I-03','VIEW')")
    public ResponseEntity<byte[]> document(
            @PathVariable @Min(1) long id,
            @PathVariable @Min(1) long fileId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                queries.document(contexts.current(), request.getRemoteAddr(), id, fileId);
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

    /** SRS-11-13 / 11-14: a labour claim with its supporting documents (201). */
    @PostMapping(value = "/v1/claims", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-03','CREATE')")
    public ResponseEntity<ClaimQueryService.ClaimRow> enter(
            @Valid @RequestPart("claim") ClaimBody body,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        List<DocumentBody> docs = body.documents() == null ? List.of() : body.documents();
        List<MultipartFile> parts = files == null ? List.of() : files;
        if (docs.size() != parts.size()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        List<LabourClaimService.Document> documents = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            DocumentBody d = docs.get(i);
            MultipartFile f = parts.get(i);
            documents.add(
                    new LabourClaimService.Document(
                            d.documentType(),
                            d.reference(),
                            d.orderDate(),
                            d.issuingAuthority(),
                            f.getOriginalFilename(),
                            bytes(f)));
        }
        AccessContext context = contexts.current();
        long id =
                labour.enter(
                                context,
                                new LabourClaimService.NewClaim(
                                        body.memberNumber(),
                                        body.claimType(),
                                        body.periodFrom(),
                                        body.periodTo(),
                                        body.claimedAmount(),
                                        documents))
                        .getId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(queries.one(context, id));
    }

    /** SRS-11-13: the Maker validates the claim and submits it to the Checker (202). */
    @PostMapping("/v1/claims/{id}/validations")
    @PreAuthorize("@authz.gate('I-03','VALIDATE')")
    public ResponseEntity<SubmittedApproval> validate(
            @PathVariable @Min(1) long id, @Valid @RequestBody ReasonBody body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(labour.validate(contexts.current(), id, body.reason()));
    }

    /** SRS-11-19: the taxable flag of a medical claim confirmed before its invoice. */
    @PostMapping("/v1/claims/{id}/taxable-confirmations")
    @PreAuthorize("@authz.gate('I-03','EDIT')")
    public ResponseEntity<ClaimQueryService.ClaimRow> confirmTaxable(
            @PathVariable @Min(1) long id, @Valid @RequestBody TaxableBody body) {
        return noStore(maintenance.confirmTaxable(contexts.current(), id, body.taxable()));
    }

    /** SRS-11-14, A-41: a supporting document added by the Maker from the order-copy card. */
    @PostMapping(
            value = "/v1/claims/{id}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-03','EDIT')")
    public ResponseEntity<DocumentStore.AttachmentView> attach(
            @PathVariable @Min(1) long id,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @PastOrPresent @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingOffice,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        labour.attach(
                                contexts.current(),
                                id,
                                new LabourClaimService.Document(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingOffice,
                                        file.getOriginalFilename(),
                                        bytes(file))));
    }

    /** SRS-11-21: the IOGA of a medical claim, for the Checker's approval (202). */
    @PostMapping("/v1/claims/{id}/iogas")
    @PreAuthorize("@authz.gate('I-03','EDIT')")
    public ResponseEntity<SubmittedApproval> ioga(
            @PathVariable @Min(1) long id, @Valid @RequestBody IogaBody body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(
                        maintenance.recordIoga(
                                contexts.current(),
                                id,
                                body.number(),
                                body.date(),
                                body.amount(),
                                body.originatingOffice(),
                                body.reason()));
    }

    /** SRS-11-23: the Claim Register as the screen filters it (Excel or CSV). */
    @GetMapping("/v1/claims/register")
    @PreAuthorize("@authz.gate('I-03','EXPORT')")
    public ResponseEntity<StreamingResponseBody> registerExport(
            @RequestParam(required = false) @Pattern(regexp = MEMBER) String memberType,
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Pattern(regexp = CODE) String claimType,
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) @Pattern(regexp = YYMM) String yymm,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        ClaimQueryService.Filter f =
                new ClaimQueryService.Filter(memberType, status, claimType, eis, yymm);
        exports.checkSize(queries.registerSize(context, f));
        return exports.stream("claim-register", format, sink -> queries.register(context, f, sink));
    }

    /** SRS-11-23 / 11-21: the Medical Register of a month (Excel or CSV). */
    @GetMapping("/v1/claims/medical-register")
    @PreAuthorize("@authz.gate('I-03','EXPORT')")
    public ResponseEntity<StreamingResponseBody> medicalRegister(
            @RequestParam @Pattern(regexp = YYMM) String yymm,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(queries.medicalRegisterSize(context, yymm));
        return exports.stream(
                "medical-register-" + yymm,
                format,
                sink -> queries.medicalRegister(context, yymm, sink));
    }

    /** SRS-11-16: I-16, the cafeteria selections received from HRMS (read-only). */
    @GetMapping("/v1/cafeteria-selections")
    @PreAuthorize("@authz.gate('I-16','VIEW')")
    public ResponseEntity<Paged<CafeteriaQueryService.Selection>> cafeteria(
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) @Pattern(regexp = "^[0-9]{4}-[0-9]{2}$") String finYear,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                cafeteria.selections(contexts.current(), eis, finYear, paging.resolve(page, size)));
    }

    /** SRS-G-13: the I-16 list as the screen filters it (Excel or CSV). */
    @GetMapping("/v1/cafeteria-selections/export")
    @PreAuthorize("@authz.gate('I-16','EXPORT')")
    public ResponseEntity<StreamingResponseBody> cafeteriaExport(
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) @Pattern(regexp = "^[0-9]{4}-[0-9]{2}$") String finYear,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(cafeteria.size(context, eis, finYear));
        return exports.stream(
                "cafeteria-selections",
                format,
                sink -> cafeteria.selections(context, eis, finYear, sink));
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
