package in.gov.fci.payroll.tax.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.tax.business.model.ItDeclaration.Kind;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import in.gov.fci.payroll.tax.orchestration.DeclarationService;
import in.gov.fci.payroll.tax.orchestration.DeclarationService.LineVerification;
import in.gov.fci.payroll.tax.orchestration.DeclarationService.NewLine;
import in.gov.fci.payroll.tax.orchestration.DeclarationService.Proof;
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
 * Income-tax declarations: the member's on E-01, the Payroll Maker's review on I-07 (SRS-7-1,
 * SRS-7-24, SRS-17-2; BR-TAX-014). The Payroll Checker decides in the Approvals Inbox (W-01).
 *
 * <pre>
 * GET  /v1/tax/declarations/mine                    the member's year              E-01 VIEW
 * POST /v1/tax/declarations/mine  (multipart)       a new round                    E-01 CREATE
 * GET  /v1/tax/declarations?finYear&status&page     the rounds in the offices      I-07 VIEW
 * GET  /v1/tax/declarations/{id}                    one round                      I-07 VIEW
 * POST /v1/tax/declarations/{id}/verify             verify, to the Checker         I-07 SUBMIT -> I07_SUBMIT
 * POST /v1/tax/declarations/{id}/return             back to the member             I-07 SUBMIT
 * POST /v1/tax/regimes/reset                        reset a frozen regime          I-07 EDIT -> I07_RESET
 * GET  /v1/tax/declarations/{id}/proofs/{proofId}   a proof                        E-01 or I-07 VIEW
 * </pre>
 *
 * The new round is a JSON part {@code request} beside the {@code file} parts: the n-th file with
 * the n-th line that names a proof. No DELETE: a round is superseded, returned or approved (R-DEL).
 * Tracker API-08-2: the SRS gives the fields and the flow, not this contract.
 */
@RestController
@Validated
@Profile("online")
public class DeclarationController {

    private static final String SECTION = "^[0-9A-Za-z()]{1,20}$";
    private static final String FIN_YEAR = "^[0-9]{4}-[0-9]{2}$";

    /** The particulars of a proof (SRS-G-30). */
    public record ProofParticulars(
            @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @Size(max = 60) @PrintableAscii String reference,
            @NotNull LocalDate documentDate,
            @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority) {}

    public record LineRequest(
            @NotNull Kind kind,
            @NotBlank @Pattern(regexp = SECTION) String sectionCode,
            @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal declared,
            @Valid ProofParticulars proof) {}

    public record DeclarationRequest(
            Regime regime, @Size(max = 60) List<@NotNull @Valid LineRequest> lines) {}

    public record VerifyLine(
            @NotNull @Positive Long lineId,
            @NotNull @Digits(integer = 12, fraction = 2) BigDecimal verified) {}

    public record VerifyRequest(@NotNull @Size(max = 60) List<@NotNull @Valid VerifyLine> lines) {}

    public record ReasonRequest(@NotBlank @Size(max = 1000) @PrintableAscii String reason) {}

    public record ResetRequest(
            @NotNull @Positive Long employeeId,
            @NotBlank @Pattern(regexp = FIN_YEAR) String finYear,
            @NotBlank @Size(max = 1000) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final DeclarationService service;
    private final Paging paging;

    public DeclarationController(
            CurrentAccess contexts, DeclarationService service, Paging paging) {
        this.contexts = contexts;
        this.service = service;
        this.paging = paging;
    }

    @GetMapping("/v1/tax/declarations/mine")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<DeclarationService.MyDeclarations> mine() {
        return noStore(service.mine(contexts.current()));
    }

    @PostMapping(
            value = "/v1/tax/declarations/mine",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('E-01','CREATE')")
    public ResponseEntity<DeclarationService.SubmissionView> submit(
            @Valid @RequestPart("request") DeclarationRequest r,
            @RequestPart(name = "file", required = false) List<MultipartFile> file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(service.submit(contexts.current(), r.regime(), lines(r.lines(), file)));
    }

    @GetMapping("/v1/tax/declarations")
    @PreAuthorize("@authz.gate('I-07','VIEW')")
    public ResponseEntity<Paged<DeclarationService.SubmissionView>> register(
            @RequestParam(required = false) @Pattern(regexp = FIN_YEAR) String finYear,
            @RequestParam(required = false)
                    @Pattern(regexp = "^(SUBMITTED|VERIFIED|APPROVED|RETURNED|SUPERSEDED)$")
                    String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                service.register(contexts.current(), finYear, status, paging.resolve(page, size)));
    }

    @GetMapping("/v1/tax/declarations/{id}")
    @PreAuthorize("@authz.gate('I-07','VIEW')")
    public ResponseEntity<DeclarationService.SubmissionView> get(@PathVariable @Positive Long id) {
        return noStore(service.get(contexts.current(), id));
    }

    @PostMapping("/v1/tax/declarations/{id}/verify")
    @PreAuthorize("@authz.gate('I-07','SUBMIT')")
    public ResponseEntity<DeclarationService.SubmissionView> verify(
            @PathVariable @Positive Long id, @Valid @RequestBody VerifyRequest r) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(
                        service.verify(
                                contexts.current(),
                                id,
                                r.lines().stream()
                                        .map(l -> new LineVerification(l.lineId(), l.verified()))
                                        .toList()));
    }

    @PostMapping("/v1/tax/declarations/{id}/return")
    @PreAuthorize("@authz.gate('I-07','SUBMIT')")
    public ResponseEntity<DeclarationService.SubmissionView> returnToMember(
            @PathVariable @Positive Long id, @Valid @RequestBody ReasonRequest r) {
        return noStore(service.returnToMember(contexts.current(), id, r.reason()));
    }

    @PostMapping("/v1/tax/regimes/reset")
    @PreAuthorize("@authz.gate('I-07','EDIT')")
    public ResponseEntity<Map<String, Long>> reset(@Valid @RequestBody ResetRequest r) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(
                        Map.of(
                                "approvalId",
                                service.proposeReset(
                                        contexts.current(),
                                        r.employeeId(),
                                        r.finYear(),
                                        r.reason())));
    }

    /**
     * Either screen, its step-up included (security review 2026-10-31: {@code can} skipped the
     * step-up of a sensitive I-07 role, so a lapsed step-up still downloaded PAN-bearing proofs).
     */
    @GetMapping("/v1/tax/declarations/{id}/proofs/{proofId}")
    @PreAuthorize("@authz.gateEither('E-01','SELF','I-07','VIEW')")
    public ResponseEntity<byte[]> proof(
            @PathVariable @Positive Long id,
            @PathVariable @Positive Long proofId,
            HttpServletRequest request) {
        DocumentStore.Content content =
                service.proof(contexts.current(), request.getRemoteAddr(), id, proofId);
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

    /** The lines with their proofs: the n-th file belongs to the n-th line naming a proof. */
    private static List<NewLine> lines(List<LineRequest> requested, List<MultipartFile> files) {
        List<LineRequest> each = requested == null ? List.of() : requested;
        List<MultipartFile> parts = files == null ? List.of() : files;
        if (each.stream().filter(l -> l.proof() != null).count() != parts.size()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        List<NewLine> out = new ArrayList<>();
        int next = 0;
        for (LineRequest l : each) {
            Proof proof = null;
            if (l.proof() != null) {
                MultipartFile part = parts.get(next++);
                String reference = l.proof().reference();
                proof =
                        new Proof(
                                l.proof().documentType(),
                                reference == null || reference.isBlank() ? null : reference.strip(),
                                l.proof().documentDate(),
                                l.proof().issuingAuthority(),
                                part.getOriginalFilename(),
                                bytes(part));
            }
            out.add(new NewLine(l.kind(), l.sectionCode(), l.declared(), proof));
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
