package in.gov.fci.payroll.configuration.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.configuration.orchestration.AccountingMasterService;
import in.gov.fci.payroll.configuration.orchestration.RateScreenService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The reads Wireframes v6.1 draws over the M-04, M-22 and M-24 masters (OD-121; API-M04-1,
 * API-M22-1, API-M24-1 in PENDING: the sources give the screens, not the API). Each endpoint has
 * its own fixed gate and the service checks it again.
 *
 * <pre>
 * GET  /v1/masters/staff-rates/dearness-versions          DA register        M-04 VIEW
 * GET  /v1/masters/staff-rates/city-classes               HRA and CCA        M-04 VIEW
 * GET  /v1/masters/staff-rates/versions/{id}/orders       stored orders      M-04 VIEW
 * POST /v1/masters/staff-rates/versions/{id}/order        order copy         M-04 CREATE
 * GET  /v1/masters/staff-rates/versions/{id}/files/{f}    order download     M-04 VIEW
 * GET  /v1/masters/special-rates/export                   the filtered list  M-22 EXPORT
 * GET  /v1/masters/accounting                             the seven masters  M-24 VIEW
 * GET  /v1/masters/accounting/{code}/records              records, read only M-24 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/masters")
public class MasterScreensController {

    private final CurrentAccess contexts;
    private final RateScreenService rates;
    private final AccountingMasterService accounting;
    private final ExportStreaming exports;
    private final Paging paging;

    public MasterScreensController(
            CurrentAccess contexts,
            RateScreenService rates,
            AccountingMasterService accounting,
            ExportStreaming exports,
            Paging paging) {
        this.contexts = contexts;
        this.rates = rates;
        this.accounting = accounting;
        this.exports = exports;
        this.paging = paging;
    }

    @GetMapping("/staff-rates/dearness-versions")
    @PreAuthorize("@authz.gate('M-04','VIEW')")
    public ResponseEntity<Paged<RateScreenService.DearnessRow>> dearness(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(Paged.slice(rates.dearness(contexts.current()), paging.resolve(page, size)));
    }

    @GetMapping("/staff-rates/city-classes")
    @PreAuthorize("@authz.gate('M-04','VIEW')")
    public ResponseEntity<Paged<RateScreenService.CityClassRow>> cityClasses(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(rates.cityClasses(contexts.current()), paging.resolve(page, size)));
    }

    @GetMapping("/staff-rates/versions/{id}/orders")
    @PreAuthorize("@authz.gate('M-04','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> orders(
            @PathVariable @Positive Long id) {
        return noStore(rates.orders(contexts.current(), id));
    }

    @PostMapping(
            value = "/staff-rates/versions/{id}/order",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('M-04','CREATE')")
    public ResponseEntity<DocumentStore.AttachmentView> order(
            @PathVariable @Positive Long id,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam(required = false) @Size(max = 200) @PrintableAscii
                    String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        rates.attachOrder(
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
    @GetMapping("/staff-rates/versions/{id}/files/{fileId}")
    @PreAuthorize("@authz.gate('M-04','VIEW')")
    public ResponseEntity<byte[]> file(
            @PathVariable @Positive Long id,
            @PathVariable @Positive Long fileId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                rates.file(contexts.current(), request.getRemoteAddr(), id, fileId);
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

    @GetMapping("/special-rates/export")
    @PreAuthorize("@authz.gate('M-22','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportSpecial(
            @RequestParam(required = false) @Pattern(regexp = "^(IDA|CDA)?$") String pattern,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z_]{0,40}$") String group,
            @RequestParam(required = false) @Size(max = 100) @PrintableAscii String q,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(rates.specialExportSize(context, pattern, group, q));
        return exports.stream(
                "m22-rate-masters",
                format,
                sink -> rates.exportSpecial(context, pattern, group, q, sink));
    }

    @GetMapping("/accounting")
    @PreAuthorize("@authz.gate('M-24','VIEW')")
    public ResponseEntity<Paged<AccountingMasterService.MasterRow>> accounting(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(accounting.list(contexts.current()), paging.resolve(page, size)));
    }

    @GetMapping("/accounting/{code}/records")
    @PreAuthorize("@authz.gate('M-24','VIEW')")
    public ResponseEntity<Paged<AccountingMasterService.RecordRow>> accountingRecords(
            @PathVariable @Pattern(regexp = "^[A-Z_]{2,40}$") String code,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageSpec spec = paging.resolve(page, size);
        return noStore(accounting.records(contexts.current(), code, spec.page(), spec.size()));
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
