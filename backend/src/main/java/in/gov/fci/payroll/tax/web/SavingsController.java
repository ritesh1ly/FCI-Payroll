package in.gov.fci.payroll.tax.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.tax.business.model.ExternalIncomeDeclaration.Type;
import in.gov.fci.payroll.tax.orchestration.SavingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * I-18 Savings & Tax Rebate Declarations (SRS-7-11, SRS-7-25; BR-TAX-007, BR-TAX-015). The Payroll
 * Checker decides in the Approvals Inbox (W-01).
 *
 * <pre>
 * GET  /v1/tax/savings?finYear&status&page                    entries in the offices          I-18 VIEW
 * GET  /v1/tax/savings/members/{eis}?finYear                  a member's year                 I-18 VIEW
 * POST /v1/tax/savings/members/{eis}/entries   (multipart)    an entry with its document      I-18 CREATE
 * POST /v1/tax/savings/entries/{id}/submit                    to the Checker                  I-18 SUBMIT -> I18_SUBMIT
 * POST /v1/tax/savings/entries/{id}/cancel                    withdraw a saved entry          I-18 EDIT
 * POST /v1/tax/savings/members/{eis}/transport-rebate         the year's rebate months        I-18 SUBMIT -> I18_REBATE
 * GET  /v1/tax/savings/entries/{id}/documents/{documentId}    the supporting document         I-18 VIEW
 * </pre>
 *
 * An entry is a JSON part {@code request} beside its one {@code file} part. No DELETE: a saved
 * entry is cancelled, a decided one stays (R-DEL). Tracker API-08-3: the SRS gives the fields, not
 * this contract.
 */
@RestController
@Validated
@Profile("online")
public class SavingsController {

    private static final String EIS = "^[0-9A-Za-z]{1,20}$";
    private static final String FIN_YEAR = "^[0-9]{4}-[0-9]{2}$";

    public record DocumentParticulars(
            @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @Size(max = 60) @PrintableAscii String reference,
            @NotNull LocalDate documentDate,
            @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority) {}

    public record EntryRequest(
            @NotNull Type type,
            @Size(max = 20) @Pattern(regexp = "^[0-9A-Za-z()]{0,20}$") String sectionCode,
            @Size(max = 60) @PrintableAscii String incomeHead,
            @Size(max = 500) @PrintableAscii String description,
            @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal tdsOtherSource,
            @NotNull @Valid DocumentParticulars document) {}

    public record ReasonRequest(@NotBlank @Size(max = 1000) @PrintableAscii String reason) {}

    public record RebateRequest(
            @NotNull @Size(min = 1, max = 12)
                    Map<
                                    @Pattern(regexp = "^[0-9]{6}$") String,
                                    @NotNull @Positive @Digits(integer = 10, fraction = 2)
                                    BigDecimal>
                            months,
            @NotBlank @Size(max = 1000) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final SavingsService service;
    private final Paging paging;

    public SavingsController(CurrentAccess contexts, SavingsService service, Paging paging) {
        this.contexts = contexts;
        this.service = service;
        this.paging = paging;
    }

    @GetMapping("/v1/tax/savings")
    @PreAuthorize("@authz.gate('I-18','VIEW')")
    public ResponseEntity<Paged<SavingsService.EntryView>> register(
            @RequestParam(required = false) @Pattern(regexp = FIN_YEAR) String finYear,
            @RequestParam(required = false)
                    @Pattern(regexp = "^(MAKER_SAVED|PENDING_CHECKER|APPROVED|REJECTED|CANCELLED)$")
                    String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                service.register(contexts.current(), finYear, status, paging.resolve(page, size)));
    }

    @GetMapping("/v1/tax/savings/members/{eis}")
    @PreAuthorize("@authz.gate('I-18','VIEW')")
    public ResponseEntity<SavingsService.MemberYear> member(
            @PathVariable @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) @Pattern(regexp = FIN_YEAR) String finYear) {
        return noStore(service.member(contexts.current(), eis, finYear));
    }

    @PostMapping(
            value = "/v1/tax/savings/members/{eis}/entries",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-18','CREATE')")
    public ResponseEntity<SavingsService.EntryView> save(
            @PathVariable @Pattern(regexp = EIS) String eis,
            @Valid @RequestPart("request") EntryRequest r,
            @RequestPart("file") MultipartFile file) {
        String reference = r.document().reference();
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        service.save(
                                contexts.current(),
                                eis,
                                new SavingsService.NewEntry(
                                        r.type(),
                                        r.sectionCode(),
                                        r.incomeHead(),
                                        r.description(),
                                        r.amount(),
                                        r.tdsOtherSource()),
                                new SavingsService.Document(
                                        r.document().documentType(),
                                        reference == null || reference.isBlank()
                                                ? null
                                                : reference.strip(),
                                        r.document().documentDate(),
                                        r.document().issuingAuthority(),
                                        file.getOriginalFilename(),
                                        bytes(file))));
    }

    @PostMapping("/v1/tax/savings/entries/{id}/submit")
    @PreAuthorize("@authz.gate('I-18','SUBMIT')")
    public ResponseEntity<SavingsService.EntryView> submit(@PathVariable @Positive Long id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(service.submit(contexts.current(), id));
    }

    @PostMapping("/v1/tax/savings/entries/{id}/cancel")
    @PreAuthorize("@authz.gate('I-18','EDIT')")
    public ResponseEntity<SavingsService.EntryView> cancel(
            @PathVariable @Positive Long id, @Valid @RequestBody ReasonRequest r) {
        return noStore(service.cancel(contexts.current(), id, r.reason()));
    }

    @PostMapping("/v1/tax/savings/members/{eis}/transport-rebate")
    @PreAuthorize("@authz.gate('I-18','SUBMIT')")
    public ResponseEntity<Map<String, Long>> rebate(
            @PathVariable @Pattern(regexp = EIS) String eis, @Valid @RequestBody RebateRequest r) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(
                        Map.of(
                                "approvalId",
                                service.proposeRebate(
                                        contexts.current(), eis, r.months(), r.reason())));
    }

    @GetMapping("/v1/tax/savings/entries/{id}/documents/{documentId}")
    @PreAuthorize("@authz.gate('I-18','VIEW')")
    public ResponseEntity<byte[]> document(
            @PathVariable @Positive Long id,
            @PathVariable @Positive Long documentId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                service.document(contexts.current(), request.getRemoteAddr(), id, documentId);
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

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
