package in.gov.fci.payroll.tax.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.tax.orchestration.Form16Service;
import in.gov.fci.payroll.tax.orchestration.ReturnService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
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
 * C-10 Form 16 and C-11 Form 24Q (SRS-7-5, SRS-7-7, SRS-7-8, SRS-7-9, SRS-7-21, SRS-7-31). The
 * Payroll Checker decides releases in the Approvals Inbox (W-01).
 *
 * <pre>
 * GET  /v1/tax/form16-certificates?finYear&status&page             certificates in the offices      C-10 VIEW
 * POST /v1/tax/form16-certificates/generate                        Part B of the paying unit        C-10 CREATE
 * POST /v1/tax/form16-certificates/{id}/part-a       (multipart)   Part A, merged                   C-10 EDIT
 * POST /v1/tax/form16-certificates/release                         merged certificates to release   C-10 SUBMIT -> C10_RELEASE
 * POST /v1/tax/form16-certificates/{id}/reissue                    against a correction return      C-10 EDIT
 * GET  /v1/tax/form16-certificates/{id}/part-a                     the certificate document         E-01 (own, released) or C-10 VIEW
 * GET  /v1/tax/form16-certificates/{id}/part-b                     Part B as PDF (OD-144)           E-01 (own, released) or C-10 VIEW
 * POST /v1/tax/form16-bulk-files                                  queue the C-10 Bulk PDF (202)    C-10 VIEW
 * GET  /v1/tax/form16-bulk-files?finYear                          the user's newest bulk file      C-10 VIEW
 * GET  /v1/tax/form16-bulk-files/{id}/file                        its ZIP, to its requester only   C-10 VIEW
 * GET  /v1/tax/form16-certificates/mine                            the member's released ones       E-01 VIEW
 * GET  /v1/tax/returns?finYear&page                   the unit's returns               C-11 VIEW
 * GET  /v1/tax/returns/{id}/deductees?page            deductees, PAN masked            C-11 VIEW
 * GET  /v1/tax/challans?finYear                       the unit's challans              C-11 VIEW
 * POST /v1/tax/challans?finYear                       one challan keyed (l.2625)       C-11 CREATE
 * POST /v1/tax/challans/import?finYear  (multipart)   the challan file                 C-11 CREATE
 * POST /v1/tax/returns/generate                       a quarter, original or correction C-11 CREATE
 * POST /v1/tax/returns/{id}/validate                  itemised checks and the utility  C-11 SUBMIT
 * POST /v1/tax/returns/{id}/release                   to the Checker                   C-11 SUBMIT -> C11_RELEASE
 * POST /v1/tax/returns/{id}/filings     (multipart)   the filing record                C-11 EDIT
 * GET  /v1/tax/returns/{id}/documents/{documentId}     the filing evidence              C-11 VIEW
 * </pre>
 *
 * No DELETE (R-DEL). Tracker API-08-6.
 */
@RestController
@Validated
@Profile("online")
public class StatutoryOutputController {

    private static final String FIN_YEAR = "^[0-9]{4}-[0-9]{2}$";

    public record YearRequest(@NotBlank @Pattern(regexp = FIN_YEAR) String finYear) {}

    /** C-10: the year and, optionally, the members to generate for (none: the whole unit). */
    public record GenerateCertificatesRequest(
            @NotBlank @Pattern(regexp = FIN_YEAR) String finYear,
            @Size(max = 1000)
                    List<@NotBlank @Pattern(regexp = "^[0-9A-Za-z]{1,20}$") String> eis) {}

    public record ReasonRequest(@NotBlank @Size(max = 1000) @PrintableAscii String reason) {}

    public record DocumentParticulars(
            @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @Size(max = 60) @PrintableAscii String reference,
            @NotNull LocalDate documentDate,
            @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority) {}

    public record GenerateReturnRequest(
            @NotBlank @Pattern(regexp = FIN_YEAR) String finYear,
            @NotBlank @Pattern(regexp = "^Q[1-4]$") String quarter,
            @Positive Long correctsReturnId) {}

    /** SRS C-11 l.2625 "User Input": one challan, the columns of a challan-file line. */
    public record ChallanRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{1,30}$") String cin,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{1,20}$") String bsr,
            @NotNull LocalDate date,
            @NotNull @Positive @jakarta.validation.constraints.Digits(integer = 16, fraction = 2)
                    java.math.BigDecimal amount,
            @Size(max = 50) @Pattern(regexp = "^[^,]*$") @PrintableAscii String reference,
            @NotBlank @Pattern(regexp = "^(Q[1-4]|[0-9]{6})$") String period) {}

    public record FilingRequest(
            @NotNull LocalDate filingDate,
            @NotBlank @Size(max = 150) @PrintableAscii String signatoryName,
            @NotBlank @Size(max = 150) @PrintableAscii String signatoryDesignation,
            @Size(max = 100) @PrintableAscii String acknowledgementToken,
            @Valid DocumentParticulars evidence) {}

    private final CurrentAccess contexts;
    private final Form16Service form16;
    private final ReturnService returns;
    private final Paging paging;
    private final in.gov.fci.payroll.platform.web.ExportStreaming downloads;

    public StatutoryOutputController(
            CurrentAccess contexts,
            Form16Service form16,
            ReturnService returns,
            Paging paging,
            in.gov.fci.payroll.platform.web.ExportStreaming downloads) {
        this.contexts = contexts;
        this.form16 = form16;
        this.returns = returns;
        this.paging = paging;
        this.downloads = downloads;
    }

    // ---------------------------------------------------------------- C-10

    @GetMapping("/v1/tax/form16-certificates")
    @PreAuthorize("@authz.gate('C-10','VIEW')")
    public ResponseEntity<Paged<Form16Service.CertificateView>> certificates(
            @RequestParam @Pattern(regexp = FIN_YEAR) String finYear,
            @RequestParam(required = false)
                    @Pattern(regexp = "^(DRAFT|PENDING_PART_A|MERGED|RELEASED)$")
                    String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                form16.register(contexts.current(), finYear, status, paging.resolve(page, size)));
    }

    @PostMapping("/v1/tax/form16-certificates/generate")
    @PreAuthorize("@authz.gate('C-10','CREATE')")
    public ResponseEntity<Form16Service.Generation> generate(
            @Valid @RequestBody GenerateCertificatesRequest r) {
        return noStore(
                form16.generate(
                        contexts.current(),
                        r.finYear(),
                        r.eis() == null ? null : java.util.Set.copyOf(r.eis())));
    }

    @PostMapping(
            value = "/v1/tax/form16-certificates/{id}/part-a",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('C-10','EDIT')")
    public ResponseEntity<Form16Service.CertificateView> partA(
            @PathVariable @Positive Long id,
            @Valid @RequestPart("request") DocumentParticulars d,
            @RequestPart("file") MultipartFile file) {
        return noStore(
                form16.uploadPartA(
                        contexts.current(),
                        id,
                        new Form16Service.PartA(
                                d.documentType(),
                                blankToNull(d.reference()),
                                d.documentDate(),
                                d.issuingAuthority(),
                                file.getOriginalFilename(),
                                bytes(file))));
    }

    @PostMapping("/v1/tax/form16-certificates/release")
    @PreAuthorize("@authz.gate('C-10','SUBMIT')")
    public ResponseEntity<Map<String, Long>> releaseCertificates(
            @Valid @RequestBody YearRequest r) {
        return accepted(
                Map.of("approvalId", form16.requestRelease(contexts.current(), r.finYear())));
    }

    @PostMapping("/v1/tax/form16-certificates/{id}/reissue")
    @PreAuthorize("@authz.gate('C-10','EDIT')")
    public ResponseEntity<Form16Service.CertificateView> reissue(
            @PathVariable @Positive Long id, @Valid @RequestBody ReasonRequest r) {
        return noStore(form16.reissue(contexts.current(), id, r.reason()));
    }

    /**
     * Either screen, its step-up included (security review 2026-10-31: {@code can} skipped the
     * step-up of a sensitive C-10 role for the PAN-bearing Part A).
     */
    @GetMapping("/v1/tax/form16-certificates/{id}/part-a")
    @PreAuthorize("@authz.gateEither('E-01','SELF','C-10','VIEW')")
    public ResponseEntity<byte[]> certificateDocument(
            @PathVariable @Positive Long id, HttpServletRequest request) {
        return download(form16.partA(contexts.current(), request.getRemoteAddr(), id));
    }

    /**
     * OD-144 (LIB-1): the certificate as PDF, Part A (TRACES, once merged) first and the generated
     * Part B after it (SRS-7-9), under Part A's rule (the member's own once released, or C-10 VIEW
     * in scope, its step-up included); SRS-7-5, SRS-7-30 (the prescribed Annexure).
     */
    @GetMapping("/v1/tax/form16-certificates/{id}/part-b")
    @PreAuthorize("@authz.gateEither('E-01','SELF','C-10','VIEW')")
    public ResponseEntity<byte[]> partB(
            @PathVariable @Positive Long id, HttpServletRequest request) {
        Form16Service.CertificatePdf pdf =
                form16.certificatePdf(contexts.current(), request.getRemoteAddr(), id);
        return downloads.document(pdf.fileName(), pdf.pdf(), pdf.pages());
    }

    /** SRS-NF-2, OD-149: queues the C-10 Bulk PDF (a ZIP of the unit's certificates) (202). */
    @PostMapping("/v1/tax/form16-bulk-files")
    @PreAuthorize("@authz.gate('C-10','VIEW')")
    public ResponseEntity<Form16Service.BulkFile> requestBulk(
            @Valid @RequestBody YearRequest body) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(form16.requestBulk(contexts.current(), body.finYear()));
    }

    /** The user's newest C-10 bulk file of the year (204 when none was asked for). */
    @GetMapping("/v1/tax/form16-bulk-files")
    @PreAuthorize("@authz.gate('C-10','VIEW')")
    public ResponseEntity<Form16Service.BulkFile> bulkStatus(
            @RequestParam @Pattern(regexp = FIN_YEAR) String finYear) {
        return form16.bulkStatus(contexts.current(), finYear)
                .map(in.gov.fci.payroll.platform.web.Responses::noStore)
                .orElseGet(
                        () ->
                                ResponseEntity.noContent()
                                        .cacheControl(
                                                org.springframework.http.CacheControl.noStore())
                                        .build());
    }

    /** The ZIP of the user's own finished C-10 bulk job. */
    @GetMapping("/v1/tax/form16-bulk-files/{id}/file")
    @PreAuthorize("@authz.gate('C-10','VIEW')")
    public ResponseEntity<byte[]> bulkFile(
            @PathVariable @Positive Long id, HttpServletRequest request) {
        Form16Service.BulkDownload file =
                form16.bulkFile(contexts.current(), request.getRemoteAddr(), id);
        return downloads.archive(file.name(), file.zip(), file.files());
    }

    @GetMapping("/v1/tax/form16-certificates/mine")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<List<Form16Service.CertificateView>> mine() {
        return noStore(form16.mine(contexts.current()));
    }

    // ---------------------------------------------------------------- C-11

    @GetMapping("/v1/tax/returns")
    @PreAuthorize("@authz.gate('C-11','VIEW')")
    public ResponseEntity<Paged<ReturnService.ReturnView>> returns(
            @RequestParam @Pattern(regexp = FIN_YEAR) String finYear,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(returns.register(contexts.current(), finYear, paging.resolve(page, size)));
    }

    @GetMapping("/v1/tax/returns/{id}/deductees")
    @PreAuthorize("@authz.gate('C-11','VIEW')")
    public ResponseEntity<Paged<ReturnService.DeducteeView>> deductees(
            @PathVariable @Positive Long id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(returns.deductees(contexts.current(), id, paging.resolve(page, size)));
    }

    @GetMapping("/v1/tax/challans")
    @PreAuthorize("@authz.gate('C-11','VIEW')")
    public ResponseEntity<List<ReturnService.ChallanView>> challans(
            @RequestParam @Pattern(regexp = FIN_YEAR) String finYear) {
        return noStore(returns.challans(contexts.current(), finYear));
    }

    @PostMapping("/v1/tax/challans")
    @PreAuthorize("@authz.gate('C-11','CREATE')")
    public ResponseEntity<ReturnService.ChallanView> enterChallan(
            @RequestParam @Pattern(regexp = FIN_YEAR) String finYear,
            @Valid @RequestBody ChallanRequest r) {
        return noStore(
                returns.enterChallan(
                        contexts.current(),
                        finYear,
                        new ReturnService.ChallanEntry(
                                r.cin(),
                                r.bsr(),
                                r.date(),
                                r.amount(),
                                r.reference(),
                                r.period())));
    }

    @PostMapping(value = "/v1/tax/challans/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('C-11','CREATE')")
    public ResponseEntity<ReturnService.Import> importChallans(
            @RequestParam @Pattern(regexp = FIN_YEAR) String finYear,
            @RequestPart("file") MultipartFile file) {
        return noStore(returns.importChallans(contexts.current(), finYear, bytes(file)));
    }

    @PostMapping("/v1/tax/returns/generate")
    @PreAuthorize("@authz.gate('C-11','CREATE')")
    public ResponseEntity<ReturnService.ReturnView> generateReturn(
            @Valid @RequestBody GenerateReturnRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        returns.generate(
                                contexts.current(),
                                r.finYear(),
                                r.quarter(),
                                r.correctsReturnId()));
    }

    @PostMapping("/v1/tax/returns/{id}/validate")
    @PreAuthorize("@authz.gate('C-11','SUBMIT')")
    public ResponseEntity<ReturnService.ReturnView> validate(@PathVariable @Positive Long id) {
        return noStore(returns.validate(contexts.current(), id));
    }

    @PostMapping("/v1/tax/returns/{id}/release")
    @PreAuthorize("@authz.gate('C-11','SUBMIT')")
    public ResponseEntity<Map<String, Long>> releaseReturn(@PathVariable @Positive Long id) {
        return accepted(Map.of("approvalId", returns.requestRelease(contexts.current(), id)));
    }

    @PostMapping(
            value = "/v1/tax/returns/{id}/filings",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('C-11','EDIT')")
    public ResponseEntity<ReturnService.ReturnView> filing(
            @PathVariable @Positive Long id,
            @Valid @RequestPart("request") FilingRequest r,
            @RequestPart(name = "file", required = false) MultipartFile file) {
        DocumentParticulars e = r.evidence();
        return noStore(
                returns.recordFiling(
                        contexts.current(),
                        id,
                        new ReturnService.Filing(
                                r.filingDate(),
                                r.signatoryName(),
                                r.signatoryDesignation(),
                                r.acknowledgementToken(),
                                e == null ? null : e.documentType(),
                                e == null ? null : e.documentDate(),
                                e == null ? null : e.issuingAuthority(),
                                file == null ? null : file.getOriginalFilename(),
                                file == null || e == null ? null : bytes(file),
                                e == null ? null : e.reference())));
    }

    @GetMapping("/v1/tax/returns/{id}/documents/{documentId}")
    @PreAuthorize("@authz.gate('C-11','VIEW')")
    public ResponseEntity<byte[]> evidence(
            @PathVariable @Positive Long id,
            @PathVariable @Positive Long documentId,
            HttpServletRequest request) {
        return download(
                returns.evidence(contexts.current(), request.getRemoteAddr(), id, documentId));
    }

    // ---------------------------------------------------------------- helpers

    private static ResponseEntity<byte[]> download(DocumentStore.Content content) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(content.attachment().fileName())
                                .build()
                                .toString())
                .contentType(MediaType.parseMediaType(content.attachment().contentType()))
                .body(content.bytes());
    }

    private static <T> ResponseEntity<T> accepted(T body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
