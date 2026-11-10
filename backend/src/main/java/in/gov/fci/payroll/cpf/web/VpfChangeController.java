package in.gov.fci.payroll.cpf.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.cpf.orchestration.VpfChangeService;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member's VPF subscription change (plan 14 phase 14c; BR-ESEVA-006). The SRS names the flow,
 * not the API (PENDING API-14-1).
 *
 * <pre>
 * GET  /v1/cpf/vpf-changes/mine               the member's VPF and requests      E-01 VIEW
 * POST /v1/cpf/vpf-changes/mine               a new monthly VPF                  E-01 CREATE
 * GET  /v1/cpf/vpf-changes                    the requests of the offices        E-02 VIEW
 * POST /v1/cpf/vpf-changes/{id}/verifications the Maker sends it to the Checker  E-02 SUBMIT
 * POST /v1/cpf/vpf-changes/{id}/returns       the Maker returns it to the member E-02 SUBMIT
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class VpfChangeController {

    /** The requested monthly VPF (DDL NUMBER(12,2), not negative). */
    public record AmountBody(
            @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal amount) {}

    /** The reason a request goes back to the member. */
    public record ReasonBody(@NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final VpfChangeService service;
    private final Paging paging;

    public VpfChangeController(CurrentAccess contexts, VpfChangeService service, Paging paging) {
        this.contexts = contexts;
        this.service = service;
        this.paging = paging;
    }

    @GetMapping("/v1/cpf/vpf-changes/mine")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<VpfChangeService.Mine> mine() {
        return noStore(service.mine(contexts.current()));
    }

    @PostMapping("/v1/cpf/vpf-changes/mine")
    @PreAuthorize("@authz.gate('E-01','CREATE')")
    public ResponseEntity<VpfChangeService.Request> request(@Valid @RequestBody AmountBody body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(service.request(contexts.current(), body.amount()));
    }

    @GetMapping("/v1/cpf/vpf-changes")
    @PreAuthorize("@authz.gate('E-02','VIEW')")
    public ResponseEntity<Paged<VpfChangeService.Request>> page(
            @RequestParam(required = false)
                    @Pattern(regexp = "^(SUBMITTED|APPROVED|REJECTED|RETURNED|APPLIED|CANCELLED)$")
                    String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(service.page(contexts.current(), status, paging.resolve(page, size)));
    }

    @PostMapping("/v1/cpf/vpf-changes/{id}/verifications")
    @PreAuthorize("@authz.gate('E-02','SUBMIT')")
    public ResponseEntity<VpfChangeService.Request> verify(@PathVariable @Min(1) long id) {
        return noStore(service.verify(contexts.current(), id));
    }

    @PostMapping("/v1/cpf/vpf-changes/{id}/returns")
    @PreAuthorize("@authz.gate('E-02','SUBMIT')")
    public ResponseEntity<VpfChangeService.Request> returnToMember(
            @PathVariable @Min(1) long id, @Valid @RequestBody ReasonBody body) {
        return noStore(service.returnToMember(contexts.current(), id, body.reason()));
    }
}
