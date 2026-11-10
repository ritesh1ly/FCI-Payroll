package in.gov.fci.payroll.payroll.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.payroll.orchestration.CycleControlOverview;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.YearMonth;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * C-01 Payroll Cycle Control, the read of its own screen (SRS l.1755-1767, SRS-15-12, SRS-15-5,
 * SRS-G-32). The SRS names the screen, not the API (PENDING API-05-22). The C-02 reads stay gated
 * on C-02: a role C-01 draws without C-02 reads here, and only what C-01 shows.
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/cycles")
public class CycleOverviewController {

    private static final String PERIOD = "^\\d{4}-(0[1-9]|1[0-2])$";

    private final CurrentAccess contexts;
    private final CycleControlOverview overview;
    private final Paging paging;

    public CycleOverviewController(
            CurrentAccess contexts, CycleControlOverview overview, Paging paging) {
        this.contexts = contexts;
        this.overview = overview;
        this.paging = paging;
    }

    @GetMapping("/{period}/overview")
    @PreAuthorize("@authz.gate('C-01','VIEW')")
    public ResponseEntity<CycleControlOverview.Overview> overview(
            @PathVariable @Pattern(regexp = PERIOD) String period) {
        return noStore(overview.overview(contexts.current(), YearMonth.parse(period)));
    }

    /** SRS-15-12: the Period Register of the unit, newest first. */
    @GetMapping("/period-register")
    @PreAuthorize("@authz.gate('C-01','VIEW')")
    public ResponseEntity<Paged<CycleControlOverview.PeriodRow>> periodRegister(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(overview.periods(contexts.current(), paging.resolve(page, size)));
    }

    /** SRS-15-39: the Pre-Payroll Exception Report by office. */
    @GetMapping("/{period}/pre-payroll-report")
    @PreAuthorize("@authz.gate('C-01','VIEW')")
    public ResponseEntity<Paged<CycleControlOverview.ReportRow>> report(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                overview.report(
                        contexts.current(), YearMonth.parse(period), paging.resolve(page, size)));
    }

    @GetMapping("/{period}/orders")
    @PreAuthorize("@authz.gate('C-01','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> orders(
            @PathVariable @Pattern(regexp = PERIOD) String period) {
        return noStore(overview.orders(contexts.current(), YearMonth.parse(period)));
    }

    /** SRS-G-32: stores the override approval note; the right is the Maker's C-02 CREATE. */
    @PostMapping(value = "/{period}/order", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('C-02','CREATE')")
    public ResponseEntity<DocumentStore.AttachmentView> order(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam(required = false) @Size(max = 200) @PrintableAscii
                    String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        DocumentStore.NewAttachment order =
                new DocumentStore.NewAttachment(
                        null,
                        null,
                        documentType,
                        reference,
                        orderDate,
                        issuingAuthority,
                        file.getOriginalFilename(),
                        bytes(file),
                        false);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(overview.attachOrder(contexts.current(), YearMonth.parse(period), order));
    }

    /** Downloads a stored note; served as an attachment so the browser never renders it inline. */
    @GetMapping("/{period}/files/{fileId}")
    @PreAuthorize("@authz.gate('C-01','VIEW')")
    public ResponseEntity<byte[]> file(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @PathVariable @Positive Long fileId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                overview.file(
                        contexts.current(),
                        request.getRemoteAddr(),
                        YearMonth.parse(period),
                        fileId);
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

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
