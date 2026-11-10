package in.gov.fci.payroll.labour.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.labour.orchestration.LabourScreenService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The reads of M-02, M-05, M-06 and M-09 as Wireframes v6.1 draws them (OD-121; SRS-2-27, SRS-2-47,
 * SRS-2-48, SRS-2-49, SRS-3-6, SRS-5-2, SRS-5-3, SRS-G-13, SRS-G-30). The SRS names the screens,
 * not the API (PENDING API-20-10). Only the order copy writes; no DELETE (SRS-G-37).
 *
 * <pre>
 * GET  /v1/labour/registers/members                 the engine's labour, paged       M-02 VIEW
 * GET  /v1/labour/registers/members/export          the filtered list                M-02 EXPORT
 * GET  /v1/labour/registers/attachments             temporary attachments, paged     M-02 VIEW
 * GET  /v1/labour/registers/light-duty-orders       Light Duty orders, paged         M-02 VIEW
 * GET  /v1/labour/members/{id}/documents            the member's stored orders       M-02 VIEW
 * POST /v1/labour/members/{id}/documents            stores an order (201)            M-02 EDIT
 * GET  /v1/labour/members/{id}/documents/{fileId}   one stored order                 M-02 VIEW
 * GET  /v1/labour/registers/gangs                   the engine's gangs, paged        M-09 VIEW
 * GET  /v1/labour/registers/gangs/summary           the structural figures           M-09 VIEW
 * GET  /v1/labour/registers/gangs/export            the filtered register            M-09 EXPORT
 * GET  /v1/labour/registers/gang-strengths          gang strength today, paged       M-09 VIEW
 * GET  /v1/labour/registers/norms                   depot norm sets, paged           M-06 VIEW
 * GET  /v1/labour/registers/norms/summary           the tiles                        M-06 VIEW
 * GET  /v1/labour/registers/norms/export            the filtered register            M-06 EXPORT
 * GET  /v1/labour/registers/norms/{id}/slabs        a set's above-norm bands         M-06 VIEW
 * GET  /v1/labour/registers/asor-rates              NWNP ASOR sets, paged            M-05 VIEW
 * GET  /v1/labour/registers/sor-rates               NWNP SOR lines, paged            M-05 VIEW
 * GET  /v1/labour/registers/min-wages               NWNP minimum wage by location    M-05 VIEW
 * GET  /v1/labour/registers/rate-tables/export      the engine's rate table          M-05 EXPORT
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class LabourScreenController {

    private static final String TYPE = "^(DEPT_LABOUR|DPS_LABOUR|NWNP_LABOUR)?$";
    private static final String GANG_CODE = "^[A-Za-z0-9-]{0,20}$";

    private final CurrentAccess contexts;
    private final LabourScreenService screens;
    private final Paging paging;
    private final ExportStreaming exports;

    public LabourScreenController(
            CurrentAccess contexts,
            LabourScreenService screens,
            Paging paging,
            ExportStreaming exports) {
        this.contexts = contexts;
        this.screens = screens;
        this.paging = paging;
        this.exports = exports;
    }

    @GetMapping("/v1/labour/registers/members")
    @PreAuthorize("@authz.gate('M-02','VIEW')")
    public ResponseEntity<Paged<LabourScreenService.MemberLine>> members(
            @RequestParam(required = false) @Pattern(regexp = TYPE) String type,
            @RequestParam(required = false) @Min(1) Long depot,
            @RequestParam(required = false) @Pattern(regexp = GANG_CODE) String gang,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                screens.members(
                        contexts.current(),
                        new LabourScreenService.MemberFilter(type, depot, gang, q),
                        paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/registers/members/export")
    @PreAuthorize("@authz.gate('M-02','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportMembers(
            @RequestParam(required = false) @Pattern(regexp = TYPE) String type,
            @RequestParam(required = false) @Min(1) Long depot,
            @RequestParam(required = false) @Pattern(regexp = GANG_CODE) String gang,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String q,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        LabourScreenService.MemberFilter filter =
                new LabourScreenService.MemberFilter(type, depot, gang, q);
        exports.checkSize(screens.memberExportSize(context, filter));
        return exports.stream(
                "m02-labour-master", format, sink -> screens.exportMembers(context, filter, sink));
    }

    @GetMapping("/v1/labour/registers/attachments")
    @PreAuthorize("@authz.gate('M-02','VIEW')")
    public ResponseEntity<Paged<LabourScreenService.AttachmentLine>> attachments(
            @RequestParam(required = false) @Pattern(regexp = TYPE) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(screens.attachments(contexts.current(), type, paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/registers/light-duty-orders")
    @PreAuthorize("@authz.gate('M-02','VIEW')")
    public ResponseEntity<Paged<LabourScreenService.LightDutyLine>> lightDuty(
            @RequestParam(required = false) @Pattern(regexp = TYPE) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                screens.lightDutyOrders(contexts.current(), type, paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/members/{id}/documents")
    @PreAuthorize("@authz.gate('M-02','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> documents(
            @PathVariable @Min(1) long id) {
        return noStore(screens.orders(contexts.current(), id));
    }

    /** SRS-G-30: Establishment / HR stores a labour transfer or other order (201). */
    @PostMapping(
            value = "/v1/labour/members/{id}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('M-02','EDIT')")
    public ResponseEntity<DocumentStore.AttachmentView> attach(
            @PathVariable @Min(1) long id,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam(required = false) @Size(max = 200) @PrintableAscii
                    String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        screens.attachOrder(
                                contexts.current(),
                                id,
                                new LabourScreenService.OrderCopy(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingAuthority,
                                        file.getOriginalFilename(),
                                        content)));
    }

    /** One stored order, served as an attachment so the browser never renders it inline. */
    @GetMapping("/v1/labour/members/{id}/documents/{fileId}")
    @PreAuthorize("@authz.gate('M-02','VIEW')")
    public ResponseEntity<byte[]> document(
            @PathVariable @Min(1) long id,
            @PathVariable @Min(1) long fileId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                screens.order(contexts.current(), request.getRemoteAddr(), id, fileId);
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

    @GetMapping("/v1/labour/registers/gangs")
    @PreAuthorize("@authz.gate('M-09','VIEW')")
    public ResponseEntity<Paged<LabourScreenService.GangLine>> gangs(
            @RequestParam(required = false) @Pattern(regexp = TYPE) String type,
            @RequestParam(required = false) @Min(1) Long depot,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                screens.gangs(
                        contexts.current(),
                        new LabourScreenService.GangFilter(type, depot, q),
                        paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/registers/gangs/summary")
    @PreAuthorize("@authz.gate('M-09','VIEW')")
    public ResponseEntity<LabourScreenService.GangSummary> gangSummary(
            @RequestParam(required = false) @Pattern(regexp = TYPE) String type,
            @RequestParam(required = false) @Min(1) Long depot,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String q) {
        return noStore(
                screens.gangSummary(
                        contexts.current(), new LabourScreenService.GangFilter(type, depot, q)));
    }

    @GetMapping("/v1/labour/registers/gangs/export")
    @PreAuthorize("@authz.gate('M-09','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportGangs(
            @RequestParam(required = false) @Pattern(regexp = TYPE) String type,
            @RequestParam(required = false) @Min(1) Long depot,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String q,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        LabourScreenService.GangFilter filter = new LabourScreenService.GangFilter(type, depot, q);
        exports.checkSize(screens.gangExportSize(context, filter));
        return exports.stream(
                "m09-gang-register", format, sink -> screens.exportGangs(context, filter, sink));
    }

    @GetMapping("/v1/labour/registers/gang-strengths")
    @PreAuthorize("@authz.gate('M-09','VIEW')")
    public ResponseEntity<Paged<LabourScreenService.StrengthLine>> strengths(
            @RequestParam(required = false) @Pattern(regexp = TYPE) String type,
            @RequestParam(required = false) @Min(1) Long depot,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                screens.strengthToday(
                        contexts.current(),
                        new LabourScreenService.GangFilter(type, depot, null),
                        paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/registers/norms")
    @PreAuthorize("@authz.gate('M-06','VIEW')")
    public ResponseEntity<Paged<LabourScreenService.NormLine>> norms(
            @RequestParam(required = false) @Min(1) Long depot,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z_]{0,20}$") String normType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                screens.norms(
                        contexts.current(),
                        new LabourScreenService.NormFilter(depot, normType, q),
                        paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/registers/norms/summary")
    @PreAuthorize("@authz.gate('M-06','VIEW')")
    public ResponseEntity<LabourScreenService.NormSummary> normSummary() {
        return noStore(screens.normSummary(contexts.current()));
    }

    @GetMapping("/v1/labour/registers/norms/export")
    @PreAuthorize("@authz.gate('M-06','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportNorms(
            @RequestParam(required = false) @Min(1) Long depot,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z_]{0,20}$") String normType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String q,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        LabourScreenService.NormFilter filter =
                new LabourScreenService.NormFilter(depot, normType, q);
        exports.checkSize(screens.normExportSize(context, filter));
        return exports.stream(
                "m06-depot-norms", format, sink -> screens.exportNorms(context, filter, sink));
    }

    @GetMapping("/v1/labour/registers/norms/{id}/slabs")
    @PreAuthorize("@authz.gate('M-06','VIEW')")
    public ResponseEntity<List<LabourScreenService.SlabLine>> slabs(@PathVariable @Min(1) long id) {
        return noStore(screens.slabs(contexts.current(), id));
    }

    @GetMapping("/v1/labour/registers/asor-rates")
    @PreAuthorize("@authz.gate('M-05','VIEW')")
    public ResponseEntity<Paged<LabourScreenService.AsorLine>> asor(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(screens.asorRates(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/registers/sor-rates")
    @PreAuthorize("@authz.gate('M-05','VIEW')")
    public ResponseEntity<Paged<LabourScreenService.SorLine>> sor(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(screens.sorRates(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/registers/min-wages")
    @PreAuthorize("@authz.gate('M-05','VIEW')")
    public ResponseEntity<List<LabourScreenService.MinWageLine>> minWages() {
        return noStore(screens.minWages(contexts.current()));
    }

    @GetMapping("/v1/labour/registers/rate-tables/export")
    @PreAuthorize("@authz.gate('M-05','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportRates(
            @RequestParam(required = false) @Pattern(regexp = TYPE) String type,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(screens.rateExportSize(context, type));
        return exports.stream(
                "m05-labour-rates", format, sink -> screens.exportRates(context, type, sink));
    }
}
