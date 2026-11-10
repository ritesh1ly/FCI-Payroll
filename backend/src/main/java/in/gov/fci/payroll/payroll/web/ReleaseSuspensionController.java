package in.gov.fci.payroll.payroll.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.payroll.orchestration.ReleaseSuspensionService;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * O-02 Release Suspension (plan 05 phase 05h; SRS-15-35, FRS BR-SEC-007 (SRS label BR-SEC-006,
 * CLR-43)). The SRS names the rule, not the API (PENDING API-05-3).
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/release-suspensions")
public class ReleaseSuspensionController {

    public record SuspendBody(
            @NotNull @Pattern(regexp = "^(ALL_UNITS|UNIT)$") String scope,
            @Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9-]{1,20}$") String unitCode,
            @NotNull @Pattern(regexp = "^(ALL|BANK_FILE|EBS_AP_POSTING|APPS_REMITTANCE)$")
                    String channel,
            @NotBlank @Size(max = 1000) @PrintableAscii String reason) {}

    public record ReasonBody(@NotBlank @Size(max = 1000) @PrintableAscii String reason) {}

    public record Created(long id) {}

    private final CurrentAccess contexts;
    private final ReleaseSuspensionService suspensions;
    private final Paging paging;

    public ReleaseSuspensionController(
            CurrentAccess contexts, ReleaseSuspensionService suspensions, Paging paging) {
        this.contexts = contexts;
        this.suspensions = suspensions;
        this.paging = paging;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('O-02','VIEW')")
    public ResponseEntity<Paged<ReleaseSuspensionService.SuspensionView>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(suspensions.list(contexts.current(), paging.resolve(page, size)));
    }

    /** SRS-15-35: Finance at HQ suspends release at once (201). */
    @PostMapping
    @PreAuthorize("@authz.gate('O-02','SUSPEND')")
    public ResponseEntity<Created> suspend(@Valid @RequestBody SuspendBody body) {
        long id =
                suspensions.suspend(
                        contexts.current(),
                        body.scope(),
                        body.unitCode(),
                        body.channel(),
                        body.reason());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(new Created(id));
    }

    /** SRS-15-35: Finance at HQ asks for the resumption (202: the HQ Payroll Checker decides). */
    @PostMapping("/{id}/resumptions")
    @PreAuthorize("@authz.gate('O-02','RESUME')")
    public ResponseEntity<Created> resume(
            @PathVariable @Min(1) long id, @Valid @RequestBody ReasonBody body) {
        long approval = suspensions.requestResume(contexts.current(), id, body.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(new Created(approval));
    }
}
