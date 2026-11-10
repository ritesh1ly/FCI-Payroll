package in.gov.fci.payroll.cpf.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.cpf.orchestration.CpfSanctionService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.workflow.api.Otp;
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
import java.util.ArrayList;
import java.util.List;
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
 * The CPF withdrawal chain (SRS-6-4, UC-05; screens E-01 and I-04; plan 07 D-14). Tier 3 is the CPF
 * Sanctioning Authority's decision in the Approvals Inbox (W-01) with OTP.
 *
 * <pre>
 * POST /v1/cpf/sanctions (multipart)            the member raises a request      E-01 CREATE
 * GET  /v1/cpf/sanctions/purposes               purposes in force, checklists    E-01 VIEW
 * GET  /v1/cpf/sanctions/mine                   the member's requests            E-01 VIEW
 * POST /v1/cpf/sanctions/{id}/withdraw          withdraw before the sanction     E-01 CREATE
 * POST /v1/cpf/sanctions/{id}/resubmit          resubmit a returned request      E-01 CREATE
 * GET  /v1/cpf/sanctions?stage=&amp;page=&amp;size=        the withdrawal register          I-04 VIEW
 * GET  /v1/cpf/sanctions/{id}                   one request, tiers, documents    I-04 VIEW
 * GET  /v1/cpf/sanctions/{id}/documents/{fileId}  a document (member or tiers)   E-01 / I-04 VIEW
 * POST /v1/cpf/sanctions/{id}/documents         tier 1: No Caste Dispute cert.   I-04 EDIT
 * POST /v1/cpf/sanctions/{id}/verify            tier 1: forward / return         I-04 EDIT
 * POST /v1/cpf/sanctions/{id}/recommend         tier 2: recommend / return       I-04 SUBMIT -> I04_SUBMIT
 * </pre>
 *
 * The request is a JSON part {@code request} beside the {@code file} parts: the n-th file with the
 * n-th entry of {@code request.documents}. No DELETE: a request is withdrawn, returned, rejected or
 * sanctioned (R-DEL). Tracker API-07-12: the SRS gives the fields, not this contract.
 */
@RestController
@Validated
@Profile("online")
public class CpfSanctionController {

    private static final String CODE = "^[A-Z0-9_]{1,40}$";
    private static final String STAGE =
            "^(UNIT_VERIFICATION|ZONAL_HQ_VALIDATION|COMPETENT_AUTHORITY|SANCTIONED|RETURNED|REJECTED"
                + "|PAYMENT_BLOCKED|PAID|CANCELLED)?$";

    /**
     * The particulars of the n-th attached file (SRS-6-14). A JSON part, not form fields: Spring
     * splits a lone form value at its commas when it binds a list ("Registrar, Delhi").
     */
    public record DocumentParticulars(
            @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @Size(max = 60) @PrintableAscii String reference,
            @NotNull LocalDate documentDate,
            @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority) {}

    /** The member's request (E-01; SRS I-04 field table, BR-CPF-039). */
    public record SanctionRequest(
            @NotBlank @Pattern(regexp = CODE) String purposeCode,
            @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @Size(max = 500) @PrintableAscii String propertyAddress,
            @Size(max = 1000) @PrintableAscii String propertyDescription,
            // A bound on the payload only; the rule (at most 5, SRS I-04 field table) is the
            // service's, so a sixth file answers CPF-422-DOCUMENT-COUNT, not a bare 400.
            @Size(max = 20) List<@NotNull @Valid DocumentParticulars> documents) {}

    public record ReasonRequest(@NotBlank @Size(max = 1000) @PrintableAscii String reason) {}

    public record ResubmitRequest(
            @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @Size(max = 500) @PrintableAscii String propertyAddress,
            @Size(max = 1000) @PrintableAscii String propertyDescription) {}

    public record TierRequest(
            @NotNull CpfSanctionService.Decision decision,
            @Size(max = 1000) @PrintableAscii String remark,
            @Valid Otp otp) {}

    private final CurrentAccess contexts;
    private final CpfSanctionService service;
    private final Paging paging;

    public CpfSanctionController(
            CurrentAccess contexts, CpfSanctionService service, Paging paging) {
        this.contexts = contexts;
        this.service = service;
        this.paging = paging;
    }

    @PostMapping(value = "/v1/cpf/sanctions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('E-01','CREATE')")
    public ResponseEntity<CpfSanctionService.SanctionView> request(
            @Valid @RequestPart("request") SanctionRequest r,
            @RequestPart(name = "file", required = false) List<MultipartFile> file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        service.request(
                                contexts.current(),
                                new CpfSanctionService.Request(
                                        r.purposeCode(),
                                        r.amount(),
                                        r.propertyAddress(),
                                        r.propertyDescription()),
                                documents(r.documents(), file)));
    }

    @GetMapping("/v1/cpf/sanctions/purposes")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<List<CpfSanctionService.PurposeOption>> purposes() {
        return noStore(service.purposes(contexts.current()));
    }

    @GetMapping("/v1/cpf/sanctions/mine")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<List<CpfSanctionService.SanctionView>> mine() {
        return noStore(service.mine(contexts.current()));
    }

    @PostMapping("/v1/cpf/sanctions/{id}/withdraw")
    @PreAuthorize("@authz.gate('E-01','CREATE')")
    public ResponseEntity<CpfSanctionService.SanctionView> withdraw(
            @PathVariable @Positive Long id, @Valid @RequestBody ReasonRequest r) {
        return noStore(service.withdraw(contexts.current(), id, r.reason()));
    }

    @PostMapping("/v1/cpf/sanctions/{id}/resubmit")
    @PreAuthorize("@authz.gate('E-01','CREATE')")
    public ResponseEntity<CpfSanctionService.SanctionView> resubmit(
            @PathVariable @Positive Long id, @Valid @RequestBody ResubmitRequest r) {
        return noStore(
                service.resubmit(
                        contexts.current(),
                        id,
                        new CpfSanctionService.Request(
                                null, r.amount(), r.propertyAddress(), r.propertyDescription())));
    }

    @GetMapping("/v1/cpf/sanctions")
    @PreAuthorize("@authz.gate('I-04','VIEW')")
    public ResponseEntity<Paged<CpfSanctionService.SanctionView>> register(
            @RequestParam(required = false) @Pattern(regexp = STAGE) String stage,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(service.register(contexts.current(), stage, paging.resolve(page, size)));
    }

    /** SRS-6-4: the I-04 tile, the requests waiting at a tier by type. */
    @GetMapping("/v1/cpf/sanctions/pending-summary")
    @PreAuthorize("@authz.gate('I-04','VIEW')")
    public ResponseEntity<CpfSanctionService.PendingSummary> pendingSummary() {
        return noStore(service.pending(contexts.current()));
    }

    @GetMapping("/v1/cpf/sanctions/{id}")
    @PreAuthorize("@authz.gate('I-04','VIEW')")
    public ResponseEntity<CpfSanctionService.SanctionView> get(@PathVariable @Positive Long id) {
        return noStore(service.get(contexts.current(), id));
    }

    /** A document; served as an attachment so the browser never renders it inline. */
    @GetMapping("/v1/cpf/sanctions/{id}/documents/{fileId}")
    @PreAuthorize("@authz.gateEither('E-01','SELF','I-04','VIEW')")
    public ResponseEntity<byte[]> document(
            @PathVariable @Positive Long id,
            @PathVariable @Positive Long fileId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                service.document(contexts.current(), request.getRemoteAddr(), id, fileId);
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

    /** SRS-6-14 (US-GAP-28): tier 1 attaches the No Caste Dispute Certificate. */
    @PostMapping(
            value = "/v1/cpf/sanctions/{id}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('I-04','EDIT')")
    public ResponseEntity<CpfSanctionService.SanctionView> attachAtUnit(
            @PathVariable @Positive Long id,
            @Valid @RequestPart("document") DocumentParticulars d,
            @RequestPart("file") MultipartFile file) {
        return noStore(
                service.attachAtUnit(
                        contexts.current(), id, documents(List.of(d), List.of(file)).getFirst()));
    }

    @PostMapping("/v1/cpf/sanctions/{id}/verify")
    @PreAuthorize("@authz.gate('I-04','EDIT')")
    public ResponseEntity<CpfSanctionService.SanctionView> verify(
            @PathVariable @Positive Long id, @Valid @RequestBody TierRequest r) {
        return noStore(service.verify(contexts.current(), id, r.decision(), r.remark()));
    }

    @PostMapping("/v1/cpf/sanctions/{id}/recommend")
    @PreAuthorize("@authz.gate('I-04','SUBMIT')")
    public ResponseEntity<CpfSanctionService.SanctionView> recommend(
            @PathVariable @Positive Long id, @Valid @RequestBody TierRequest r) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(
                        service.recommend(
                                contexts.current(),
                                id,
                                r.decision(),
                                r.remark(),
                                Otp.proof(r.otp())));
    }

    /** The n-th file with the n-th particulars; a count that differs is malformed. */
    private static List<CpfSanctionService.Document> documents(
            List<DocumentParticulars> particulars, List<MultipartFile> files) {
        List<DocumentParticulars> each = particulars == null ? List.of() : particulars;
        List<MultipartFile> parts = files == null ? List.of() : files;
        if (each.size() != parts.size()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        List<CpfSanctionService.Document> out = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            DocumentParticulars d = each.get(i);
            String reference = d.reference();
            out.add(
                    new CpfSanctionService.Document(
                            d.documentType(),
                            reference == null || reference.isBlank() ? null : reference.strip(),
                            d.documentDate(),
                            d.issuingAuthority(),
                            parts.get(i).getOriginalFilename(),
                            bytes(parts.get(i))));
        }
        return out;
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
