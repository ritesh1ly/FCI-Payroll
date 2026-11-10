package in.gov.fci.payroll.employee.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.employee.orchestration.EmployeeFlagService;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
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
 * M-01 Payroll allowance flags (plan 16 phase 16d-2; SRS-1-4, SRS-1-11). The SRS names the screen,
 * not the API (PENDING API-16-1).
 *
 * <pre>
 * GET  /v1/employees/{eis}/allowance-flags          the flags today               M-01 VIEW
 * GET  /v1/employees/{eis}/allowance-flags/history  every version, paged          M-01 VIEW
 * POST /v1/employees/{eis}/allowance-flags          propose a version (202)       M-01 FLAG (service)
 * </pre>
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/employees/{eis}/allowance-flags")
public class EmployeeFlagController {

    /**
     * SRS-1-11 / BR-EMPM-005 / 006: the flag, its value, the date it applies from, the end date
     * when it has one (US-GAP-39) and the reason.
     */
    public record ProposalBody(
            @NotNull @Pattern(regexp = "^[A-Z_]{1,30}$") String flagCode,
            @NotNull @Pattern(regexp = "^[YN]$") String value,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotNull @Size(max = 500) @PrintableAscii String reason) {}

    public record Submitted(long approvalId, String status) {}

    private final CurrentAccess contexts;
    private final EmployeeFlagService flags;
    private final Paging paging;

    public EmployeeFlagController(
            CurrentAccess contexts, EmployeeFlagService flags, Paging paging) {
        this.contexts = contexts;
        this.flags = flags;
        this.paging = paging;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('M-01','VIEW')")
    public ResponseEntity<EmployeeFlagService.Flags> flags(
            @PathVariable @Size(max = 20) String eis) {
        return noStore(flags.flags(contexts.current(), eis));
    }

    @GetMapping("/history")
    @PreAuthorize("@authz.gate('M-01','VIEW')")
    public ResponseEntity<Paged<EmployeeFlagService.FlagVersion>> history(
            @PathVariable @Size(max = 20) String eis,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(flags.history(contexts.current(), eis, paging.resolve(page, size)));
    }

    /** SRS-1-26: the proposal with its supporting document (SRS-G-30) in one request. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('M-01','VIEW')")
    public ResponseEntity<Submitted> propose(
            @PathVariable @Size(max = 20) String eis,
            @Valid @RequestPart("flag") ProposalBody body,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate orderDate,
            @RequestParam @NotBlank @Size(max = 200) @PrintableAscii String issuingAuthority,
            @RequestPart("file") MultipartFile file) {
        SubmittedApproval item =
                flags.propose(
                        contexts.current(),
                        eis,
                        new EmployeeFlagService.Proposal(
                                body.flagCode(),
                                body.value(),
                                body.effectiveFrom(),
                                body.effectiveTo(),
                                body.reason()),
                        new EmployeeFlagService.Document(
                                documentType,
                                reference,
                                orderDate,
                                issuingAuthority,
                                file.getOriginalFilename(),
                                bytes(file)));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(new Submitted(item.id(), item.status().name()));
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
