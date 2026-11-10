package in.gov.fci.payroll.tax.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.tax.orchestration.TaxScreenViews;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The reads Wireframes v6.1 draws on M-17, I-07, C-07, C-10 and C-11 (OD-121; SRS-7-1, SRS-7-7,
 * SRS-7-9, SRS-7-12, SRS-7-20, SRS-9-1, BR-TAX-010) and the I-07 order card (SRS-G-30). Read-only
 * but for the order card; no rule lives here. Tracker API-08-8.
 *
 * <pre>
 * GET  /v1/tax/output-definitions/summary              drawn columns, tiles    M-17 VIEW
 * GET  /v1/tax/declarations/summary?finYear            tiles, sections         I-07 VIEW
 * GET  /v1/tax/declarations/members?finYear            the member register     I-07 VIEW
 * GET  /v1/tax/declarations/{id}/orders                order copies            I-07 VIEW
 * POST /v1/tax/declarations/{id}/orders                store an order copy     I-07 SUBMIT
 * GET  /v1/tax/declarations/{id}/files/{fileId}        an order copy           I-07 VIEW
 * GET  /v1/tax/statutory-computation/summary           tiles, CPF table        C-07 VIEW
 * GET  /v1/tax/statutory-computation/eps               EPF wages               C-07 VIEW
 * GET  /v1/tax/statutory-computation/members           tax lines               C-07 VIEW
 * GET  /v1/tax/statutory-computation/nps               NPS as received         C-07 VIEW
 * GET  /v1/tax/form16-certificates/summary?finYear     tiles, chain            C-10 VIEW
 * GET  /v1/tax/form16-certificates/lines?finYear       the register            C-10 VIEW
 * GET  /v1/tax/returns/summary?finYear&amp;quarter         tiles, steps            C-11 VIEW
 * GET  /v1/tax/returns/lines                           the register            C-11 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class TaxScreenController {

    private static final String YEAR = "^\\d{4}-\\d{2}$";

    private final CurrentAccess contexts;
    private final TaxScreenViews views;
    private final Paging paging;

    public TaxScreenController(CurrentAccess contexts, TaxScreenViews views, Paging paging) {
        this.contexts = contexts;
        this.views = views;
        this.paging = paging;
    }

    @GetMapping("/v1/tax/output-definitions/summary")
    @PreAuthorize("@authz.gate('M-17','VIEW')")
    public ResponseEntity<TaxScreenViews.OutputSummary> outputs() {
        return noStore(views.outputSummary(contexts.current()));
    }

    @GetMapping("/v1/tax/declarations/summary")
    @PreAuthorize("@authz.gate('I-07','VIEW')")
    public ResponseEntity<TaxScreenViews.DeclarationSummary> declarations(
            @RequestParam @Pattern(regexp = YEAR) String finYear) {
        return noStore(views.declarationSummary(contexts.current(), finYear));
    }

    @GetMapping("/v1/tax/declarations/members")
    @PreAuthorize("@authz.gate('I-07','VIEW')")
    public ResponseEntity<Paged<TaxScreenViews.DeclarationLine>> declarationMembers(
            @RequestParam @Pattern(regexp = YEAR) String finYear,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                views.declarationMembers(contexts.current(), finYear, paging.resolve(page, size)));
    }

    @GetMapping("/v1/tax/declarations/{id}/orders")
    @PreAuthorize("@authz.gate('I-07','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> orders(
            @PathVariable @Positive Long id) {
        return noStore(views.orders(contexts.current(), id));
    }

    @PostMapping(value = "/v1/tax/declarations/{id}/orders", consumes = "multipart/form-data")
    @PreAuthorize("@authz.gate('I-07','SUBMIT')")
    public ResponseEntity<DocumentStore.AttachmentView> attachOrder(
            @PathVariable @Positive Long id,
            @RequestParam @NotBlank @Size(max = 30) @PrintableAscii String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @PastOrPresent @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam(required = false) @Size(max = 200) @PrintableAscii
                    String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        views.attachOrder(
                                contexts.current(),
                                id,
                                documentType,
                                reference,
                                orderDate,
                                issuingAuthority,
                                file.getOriginalFilename(),
                                bytes(file)));
    }

    /** Downloads the order; served as an attachment so the browser never renders it inline. */
    @GetMapping("/v1/tax/declarations/{id}/files/{fileId}")
    @PreAuthorize("@authz.gate('I-07','VIEW')")
    public ResponseEntity<byte[]> orderFile(
            @PathVariable @Positive Long id,
            @PathVariable @Positive Long fileId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                views.orderFile(contexts.current(), request.getRemoteAddr(), id, fileId);
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

    @GetMapping("/v1/tax/statutory-computation/summary")
    @PreAuthorize("@authz.gate('C-07','VIEW')")
    public ResponseEntity<TaxScreenViews.ComputationSummary> computation() {
        return noStore(views.computationSummary(contexts.current()));
    }

    @GetMapping("/v1/tax/statutory-computation/eps")
    @PreAuthorize("@authz.gate('C-07','VIEW')")
    public ResponseEntity<Paged<TaxScreenViews.EpsLine>> eps(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(views.epsLines(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/tax/statutory-computation/members")
    @PreAuthorize("@authz.gate('C-07','VIEW')")
    public ResponseEntity<Paged<TaxScreenViews.TaxLine>> taxLines(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(views.taxLines(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/tax/statutory-computation/nps")
    @PreAuthorize("@authz.gate('C-07','VIEW')")
    public ResponseEntity<Paged<TaxScreenViews.NpsLine>> nps(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(views.npsLines(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/tax/form16-certificates/summary")
    @PreAuthorize("@authz.gate('C-10','VIEW')")
    public ResponseEntity<TaxScreenViews.CertificateSummary> certificates(
            @RequestParam @Pattern(regexp = YEAR) String finYear) {
        return noStore(views.certificateSummary(contexts.current(), finYear));
    }

    @GetMapping("/v1/tax/form16-certificates/lines")
    @PreAuthorize("@authz.gate('C-10','VIEW')")
    public ResponseEntity<Paged<TaxScreenViews.CertificateLine>> certificateLines(
            @RequestParam @Pattern(regexp = YEAR) String finYear,
            @RequestParam(required = false)
                    @Pattern(regexp = "^(DRAFT|PENDING_PART_A|MERGED|RELEASED)$")
                    String status,
            @RequestParam(required = false) @Min(1) Long office,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                views.certificateLines(
                        contexts.current(), finYear, status, office, paging.resolve(page, size)));
    }

    @GetMapping("/v1/tax/returns/summary")
    @PreAuthorize("@authz.gate('C-11','VIEW')")
    public ResponseEntity<TaxScreenViews.ReturnSummary> returns(
            @RequestParam @Pattern(regexp = YEAR) String finYear,
            @RequestParam @Pattern(regexp = "^Q[1-4]$") String quarter) {
        return noStore(views.returnSummary(contexts.current(), finYear, quarter));
    }

    @GetMapping("/v1/tax/returns/lines")
    @PreAuthorize("@authz.gate('C-11','VIEW')")
    public ResponseEntity<Paged<TaxScreenViews.ReturnLine>> returnLines(
            @RequestParam(required = false) @Min(1) Long office,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(views.returnLines(contexts.current(), office, paging.resolve(page, size)));
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
