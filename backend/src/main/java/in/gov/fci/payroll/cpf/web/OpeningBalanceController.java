package in.gov.fci.payroll.cpf.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.cpf.orchestration.OpeningBalanceService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * I-22 PF / CPF Opening Balance Correction (SRS-6-43, SRS-6-44). The AGM (CPF) and the Headquarters
 * CPF approver decide in the Approvals Inbox (W-01 / X-02), each with OTP.
 *
 * <pre>
 * GET  /v1/cpf/opening-balances?eis=&amp;asAt=             member and current opening   I-22 VIEW
 * GET  /v1/cpf/opening-balance-corrections               the correction register      I-22 VIEW
 * POST /v1/cpf/opening-balance-corrections (multipart)   raise, with the evidence     I-22 CREATE -> I22_CREATE
 * GET  /v1/cpf/opening-balance-corrections/{ref}/evidence/{fileId}   evidence         I-22 VIEW
 * </pre>
 *
 * No DELETE: a correction is applied, returned or rejected, never deleted (R-DEL). Tracker
 * API-07-1: the SRS gives the fields, not this contract.
 */
@RestController
@Validated
@Profile("online")
public class OpeningBalanceController {

    private static final String EIS = "^[A-Za-z0-9]{1,20}$";
    private static final String REF = "^OB-\\d{4}-\\d{6}$";

    private final CurrentAccess contexts;
    private final OpeningBalanceService service;
    private final Paging paging;

    public OpeningBalanceController(
            CurrentAccess contexts, OpeningBalanceService service, Paging paging) {
        this.paging = paging;
        this.contexts = contexts;
        this.service = service;
    }

    @GetMapping("/v1/cpf/opening-balances")
    @PreAuthorize("@authz.gate('I-22','VIEW')")
    public ResponseEntity<OpeningBalanceService.Current> current(
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            // SRS I-22 "Member (CPF Code / UAN)" (l.2234): CPF code (20) or UAN (12 digits).
            @RequestParam(required = false) @Pattern(regexp = "^[A-Za-z0-9/-]{1,20}$")
                    String cpfCode,
            @RequestParam(required = false) @Pattern(regexp = "^[0-9]{12}$") String uan,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt) {
        return noStore(service.current(contexts.current(), eis, cpfCode, uan, asAt));
    }

    @GetMapping("/v1/cpf/opening-balance-corrections")
    @PreAuthorize("@authz.gate('I-22','VIEW')")
    public ResponseEntity<Paged<OpeningBalanceService.CorrectionView>> register(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(service.register(contexts.current()), paging.resolve(page, size)));
    }

    @PostMapping(
            value = "/v1/cpf/opening-balance-corrections",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-22','CREATE')")
    @SuppressWarnings("java:S107") // the form's fields, each validated where it arrives
    public ResponseEntity<OpeningBalanceService.CorrectionView> raise(
            @RequestParam @NotBlank @Pattern(regexp = EIS) String eis,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate asAtDate,
            @RequestParam(required = false) @Digits(integer = 12, fraction = 2) BigDecimal employee,
            @RequestParam(required = false) @Digits(integer = 12, fraction = 2) BigDecimal employer,
            @RequestParam(required = false) @Digits(integer = 12, fraction = 2) BigDecimal vpf,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z0-9_]{1,40}$") String reasonCode,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate interestEffectiveDate,
            @RequestParam @NotBlank @Size(max = 1000) @PrintableAscii String remarks,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        service.raise(
                                contexts.current(),
                                new OpeningBalanceService.Request(
                                        eis,
                                        asAtDate,
                                        employee,
                                        employer,
                                        vpf,
                                        reasonCode,
                                        interestEffectiveDate,
                                        remarks.strip()),
                                new OpeningBalanceService.Evidence(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingAuthority,
                                        file.getOriginalFilename(),
                                        bytes(file)),
                                null));
    }

    /** Downloads the evidence; served as an attachment so the browser never renders it inline. */
    @GetMapping("/v1/cpf/opening-balance-corrections/{ref}/evidence/{fileId}")
    @PreAuthorize("@authz.gate('I-22','VIEW')")
    public ResponseEntity<byte[]> evidence(
            @PathVariable @Pattern(regexp = REF) String ref,
            @PathVariable @Positive Long fileId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                service.evidence(contexts.current(), request.getRemoteAddr(), ref, fileId);
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
