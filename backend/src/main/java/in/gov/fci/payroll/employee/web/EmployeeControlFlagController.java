package in.gov.fci.payroll.employee.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.employee.orchestration.EmployeeControlFlagService;
import in.gov.fci.payroll.employee.orchestration.EmployeeFlagService;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
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
 * M-01 Control flags: Hold and Stop-Payment (SRS-1-7, SRS-1-11; FRS §7.8.1 Scenario 4; US-GAP-4).
 * The SRS names the screen, not the API (PENDING API-16-1).
 *
 * <pre>
 * GET  /v1/employees/{eis}/control-flags   the flags and their pending changes   M-01 VIEW
 * POST /v1/employees/{eis}/control-flags   set or clear a flag (202)              M-01 FLAG (service)
 * </pre>
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/employees/{eis}/control-flags")
public class EmployeeControlFlagController {

    /** The flag, Y (set) or N (clear), the date it applies from and the reason. */
    public record ProposalBody(
            @NotNull @Pattern(regexp = "^(HOLD|STOP_PAYMENT)$") String flagCode,
            @NotNull @Pattern(regexp = "^[YN]$") String value,
            @NotNull LocalDate effectiveFrom,
            @NotNull @Size(max = 500) @PrintableAscii String reason) {}

    public record Submitted(long approvalId, String status) {}

    private final CurrentAccess contexts;
    private final EmployeeControlFlagService flags;

    public EmployeeControlFlagController(CurrentAccess contexts, EmployeeControlFlagService flags) {
        this.contexts = contexts;
        this.flags = flags;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('M-01','VIEW')")
    public ResponseEntity<EmployeeControlFlagService.Flags> flags(
            @PathVariable @Size(max = 20) String eis) {
        return noStore(flags.flags(contexts.current(), eis));
    }

    /** The change with its order (SRS-G-30, SRS-G-32) in one request. */
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
            // Optional here so a missing order is the service's own refusal, audited (SRS-20-27).
            @RequestPart(value = "file", required = false) MultipartFile file) {
        SubmittedApproval item =
                flags.propose(
                        contexts.current(),
                        eis,
                        new EmployeeControlFlagService.Proposal(
                                body.flagCode(), body.value(), body.effectiveFrom(), body.reason()),
                        new EmployeeFlagService.Document(
                                documentType,
                                reference,
                                orderDate,
                                issuingAuthority,
                                file == null ? null : file.getOriginalFilename(),
                                file == null ? null : bytes(file)));
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
