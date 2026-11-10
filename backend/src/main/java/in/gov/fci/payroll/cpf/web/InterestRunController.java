package in.gov.fci.payroll.cpf.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.cpf.business.InterestRunRules;
import in.gov.fci.payroll.cpf.orchestration.InterestRunService;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-12 CPF Interest Run - Calculate / Re-Run / Freeze (SRS-6-45, SRS-6-46) for the zone of the
 * user's office. The AGM (CPF) decides a freeze in the Approvals Inbox (W-01), without OTP.
 *
 * <pre>
 * GET  /v1/cpf/interest-runs?finYear=                  tiles and run audit list    C-12 VIEW
 * GET  /v1/cpf/interest-runs/not-calculated?finYear=   members not yet calculated  C-12 VIEW
 * POST /v1/cpf/interest-runs                           run / re-run                C-12 CREATE
 * POST /v1/cpf/interest-runs/freeze                    request the freeze          C-12 SUBMIT -> C12_SUBMIT
 * </pre>
 *
 * No DELETE and no un-freeze: runs and the freeze are kept for good (R-DEL, {@code
 * trg_cpf_freeze_irreversible}). Tracker API-07-10: the SRS gives the fields, not this contract.
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/cpf/interest-runs")
public class InterestRunController {

    private static final String FIN_YEAR = "^\\d{4}-\\d{2}$";

    public record RunRequest(
            @NotBlank @Pattern(regexp = FIN_YEAR) String finYear,
            @NotNull InterestRunRules.Scope scope,
            @Pattern(regexp = "^[A-Za-z0-9]{1,20}$") String eis,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    public record FreezeRequest(
            @NotBlank @Pattern(regexp = FIN_YEAR) String finYear,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final InterestRunService service;
    private final Paging paging;

    public InterestRunController(
            CurrentAccess contexts, InterestRunService service, Paging paging) {
        this.paging = paging;
        this.contexts = contexts;
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('C-12','VIEW')")
    public ResponseEntity<InterestRunService.Summary> summary(
            @RequestParam @NotBlank @Pattern(regexp = FIN_YEAR) String finYear) {
        return noStore(service.summary(contexts.current(), finYear));
    }

    @GetMapping("/not-calculated")
    @PreAuthorize("@authz.gate('C-12','VIEW')")
    public ResponseEntity<Paged<InterestRunService.NotCalculated>> notCalculated(
            @RequestParam @NotBlank @Pattern(regexp = FIN_YEAR) String finYear,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                service.notCalculated(contexts.current(), finYear, paging.resolve(page, size)));
    }

    @PostMapping
    @PreAuthorize("@authz.gate('C-12','CREATE')")
    public ResponseEntity<InterestRunService.RunView> run(@Valid @RequestBody RunRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        service.run(
                                contexts.current(),
                                new InterestRunService.RunRequest(
                                        r.finYear(), r.scope(), r.eis(), r.reason())));
    }

    @PostMapping("/freeze")
    @PreAuthorize("@authz.gate('C-12','SUBMIT')")
    public ResponseEntity<InterestRunService.FreezeRequested> freeze(
            @Valid @RequestBody FreezeRequest r) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(service.requestFreeze(contexts.current(), r.finYear(), r.reason()));
    }
}
