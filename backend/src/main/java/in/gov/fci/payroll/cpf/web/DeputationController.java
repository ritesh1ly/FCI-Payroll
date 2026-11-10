package in.gov.fci.payroll.cpf.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.cpf.business.model.CpfDeputationRecovery.EntryType;
import in.gov.fci.payroll.cpf.orchestration.DeputationService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

/**
 * I-09 CPF Adjustment / Recovery Adjustment for deputed members (plan 07 phase 07g; SRS-6-27 …
 * 6-30). Tracker API-07-7.
 *
 * <pre>
 * GET  /v1/cpf/borrowing-organisations                      the organisations      I-09 VIEW
 * POST /v1/cpf/borrowing-organisations                      add one                I-09 CREATE
 * GET  /v1/cpf/deputations/{eis}                            the deputed member     I-09 VIEW
 * GET  /v1/cpf/deputation-entries?page&size                 the register           I-09 VIEW
 * POST /v1/cpf/deputation-entries  (multipart)              record and submit      I-09 CREATE
 * POST /v1/cpf/deputation-entries/{id}/evidence  (multipart) add evidence files   I-09 CREATE
 * POST /v1/cpf/deputation-entries/{id}/evidence/{fileId}/cancel  cancel a file    I-09 CREATE
 * GET  /v1/cpf/deputation-entries/{id}/evidence/{fileId}    the evidence           I-09 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class DeputationController {

    private static final String EIS = "^[0-9A-Za-z]{1,20}$";

    /** A borrowing organisation as the Manager (CPF) adds it. */
    public record NewOrganisation(
            @Pattern(regexp = "^[A-Z0-9_-]{1,30}$") String code,
            @Size(max = 250) @PrintableAscii String name,
            @Size(max = 500) @PrintableAscii String contact) {}

    private final CurrentAccess contexts;
    private final DeputationService service;
    private final Paging paging;

    public DeputationController(CurrentAccess contexts, DeputationService service, Paging paging) {
        this.contexts = contexts;
        this.service = service;
        this.paging = paging;
    }

    @GetMapping("/v1/cpf/borrowing-organisations")
    @PreAuthorize("@authz.gate('I-09','VIEW')")
    public ResponseEntity<List<DeputationService.Organisation>> organisations() {
        return noStore(service.organisations(contexts.current()));
    }

    @PostMapping("/v1/cpf/borrowing-organisations")
    @PreAuthorize("@authz.gate('I-09','CREATE')")
    public ResponseEntity<DeputationService.Organisation> addOrganisation(
            @RequestBody @Valid NewOrganisation body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        service.addOrganisation(
                                contexts.current(), body.code(), body.name(), body.contact()));
    }

    @GetMapping("/v1/cpf/deputations/{eis}")
    @PreAuthorize("@authz.gate('I-09','VIEW')")
    public ResponseEntity<DeputationService.Member> member(
            @PathVariable @Pattern(regexp = EIS) String eis) {
        return noStore(service.member(contexts.current(), eis));
    }

    @GetMapping("/v1/cpf/deputation-entries")
    @PreAuthorize("@authz.gate('I-09','VIEW')")
    public ResponseEntity<Paged<DeputationService.EntryView>> register(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(service.register(contexts.current()), paging.resolve(page, size)));
    }

    @PostMapping(
            value = "/v1/cpf/deputation-entries",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-09','CREATE')")
    @SuppressWarnings("java:S107") // the form's fields, each validated where it arrives
    public ResponseEntity<DeputationService.EntryView> record(
            @RequestParam @NotBlank @Pattern(regexp = EIS) String eis,
            @RequestParam @NotNull @Positive Long borrowingOrgId,
            @RequestParam @NotNull EntryType type,
            @RequestParam(required = false) @Positive Long sanctionId,
            @RequestParam @NotBlank @Pattern(regexp = "^[0-9]{6}$") String payrollYymm,
            @RequestParam(required = false) @Digits(integer = 12, fraction = 2)
                    BigDecimal instalment,
            @RequestParam(required = false) @Digits(integer = 12, fraction = 2) BigDecimal employee,
            @RequestParam(required = false) @Digits(integer = 12, fraction = 2) BigDecimal employer,
            @RequestParam(required = false) @Digits(integer = 12, fraction = 2) BigDecimal vpf,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority,
            @RequestPart("file") List<MultipartFile> files) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        service.recordWithFiles(
                                contexts.current(),
                                new DeputationService.Entry(
                                        eis,
                                        borrowingOrgId,
                                        type,
                                        sanctionId,
                                        payrollYymm,
                                        instalment,
                                        employee,
                                        employer,
                                        vpf),
                                evidence(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingAuthority,
                                        files),
                                null));
    }

    /** I-09 Remittance Evidence: files added to an entry after submission (up to five active). */
    @PostMapping(
            value = "/v1/cpf/deputation-entries/{id}/evidence",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-09','CREATE')")
    public ResponseEntity<List<DeputationService.EvidenceFile>> addEvidence(
            @PathVariable @Positive Long id,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority,
            @RequestPart("file") List<MultipartFile> files) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        service.addEvidence(
                                contexts.current(),
                                id,
                                evidence(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingAuthority,
                                        files)));
    }

    /** What a cancellation of a wrong evidence file carries. */
    public record CancelEvidence(@NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    /** I-09 Remittance Evidence: a wrong file is cancelled with the reason, never deleted. */
    @PostMapping("/v1/cpf/deputation-entries/{id}/evidence/{fileId}/cancel")
    @PreAuthorize("@authz.gate('I-09','CREATE')")
    public ResponseEntity<List<DeputationService.EvidenceFile>> cancelEvidence(
            @PathVariable @Positive Long id,
            @PathVariable @Positive Long fileId,
            @Valid @RequestBody CancelEvidence body) {
        return noStore(service.cancelEvidence(contexts.current(), id, fileId, body.reason()));
    }

    private static List<DeputationService.Evidence> evidence(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingAuthority,
            List<MultipartFile> files) {
        return files.stream()
                .map(
                        f ->
                                new DeputationService.Evidence(
                                        documentType,
                                        reference,
                                        orderDate,
                                        issuingAuthority,
                                        f.getOriginalFilename(),
                                        bytes(f)))
                .toList();
    }

    /** Downloads the evidence; served as an attachment so the browser never renders it inline. */
    @GetMapping("/v1/cpf/deputation-entries/{id}/evidence/{fileId}")
    @PreAuthorize("@authz.gate('I-09','VIEW')")
    public ResponseEntity<byte[]> evidence(
            @PathVariable @Positive Long id,
            @PathVariable @Positive Long fileId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                service.evidence(contexts.current(), request.getRemoteAddr(), id, fileId);
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
