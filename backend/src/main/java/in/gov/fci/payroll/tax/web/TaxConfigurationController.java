package in.gov.fci.payroll.tax.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.tax.orchestration.TaxConfigurationService;
import in.gov.fci.payroll.tax.orchestration.TaxConfigurationService.Pending;
import in.gov.fci.payroll.tax.orchestration.TaxConfigurationService.SectionProposal;
import in.gov.fci.payroll.tax.orchestration.TaxConfigurationService.StateProposal;
import in.gov.fci.payroll.workflow.api.Otp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The income-tax section master and the PT state master on M-18 (SRS-7-2, SRS-7-4, SRS-7-20,
 * SRS-7-26; plan 08 D-2). The Rule Approver decides in the Approvals Inbox (W-01).
 *
 * <pre>
 * GET  /v1/tax/sections              one page of every section version      M-18 VIEW
 * GET  /v1/tax/sections/pending      section versions awaiting a decision   M-18 VIEW
 * POST /v1/tax/sections              a new version of a section             M-18 CREATE -> M18_SECTION
 * GET  /v1/tax/pt-states             one page of the PT states              M-18 VIEW
 * GET  /v1/tax/pt-states/pending     PT state changes awaiting a decision   M-18 VIEW
 * POST /v1/tax/pt-states             a new state                            M-18 CREATE -> M18_PT_STATE
 * PUT  /v1/tax/pt-states/{code}      new values of a state                  M-18 EDIT   -> M18_PT_STATE
 * </pre>
 *
 * No DELETE: a section ends when a later version starts, a state by its end date (R-DEL). Tracker
 * API-08-1: the SRS gives the masters, not this contract.
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/tax")
public class TaxConfigurationController {

    private static final String STATE_CODE = "^[A-Z]{2,10}$";

    public record SectionRequest(
            @NotBlank @Size(max = 20) @Pattern(regexp = "^[0-9A-Za-z()]{1,20}$") String code,
            @NotBlank @Size(max = 500) @PrintableAscii String description,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal limit,
            @NotBlank @Pattern(regexp = "^(OLD|NEW|BOTH)$") String regime,
            @NotNull LocalDate effectiveFrom,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    /** The values of a state; on a new state also its code. */
    public record StateRequest(
            @Pattern(regexp = STATE_CODE) String code,
            @NotBlank @Size(max = 100) @PrintableAscii String name,
            boolean ptApplicable,
            @Size(max = 50) @PrintableAscii String registrationNumber,
            @Size(max = 20) @Pattern(regexp = "^[A-Z_]{0,20}$") String returnPeriodicity,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    private final CurrentAccess contexts;
    private final TaxConfigurationService service;
    private final Paging paging;

    public TaxConfigurationController(
            CurrentAccess contexts, TaxConfigurationService service, Paging paging) {
        this.contexts = contexts;
        this.service = service;
        this.paging = paging;
    }

    @GetMapping("/sections")
    @PreAuthorize("@authz.gate('M-18','VIEW')")
    public ResponseEntity<Paged<TaxConfigurationService.SectionView>> sections(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(service.sections(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/sections/pending")
    @PreAuthorize("@authz.gate('M-18','VIEW')")
    public ResponseEntity<List<Pending<SectionProposal>>> pendingSections() {
        return noStore(service.pendingSections(contexts.current()));
    }

    @PostMapping("/sections")
    @PreAuthorize("@authz.gate('M-18','CREATE')")
    public ResponseEntity<Pending<SectionProposal>> proposeSection(
            @Valid @RequestBody SectionRequest r) {
        return accepted(
                service.proposeSection(
                        contexts.current(),
                        new SectionProposal(
                                r.code(),
                                r.description(),
                                r.limit(),
                                r.regime(),
                                r.effectiveFrom(),
                                null,
                                null),
                        r.reason(),
                        Otp.proof(r.otp())));
    }

    @GetMapping("/pt-states")
    @PreAuthorize("@authz.gate('M-18','VIEW')")
    public ResponseEntity<Paged<TaxConfigurationService.StateView>> states(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(service.states(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/pt-states/pending")
    @PreAuthorize("@authz.gate('M-18','VIEW')")
    public ResponseEntity<List<Pending<StateProposal>>> pendingStates() {
        return noStore(service.pendingStates(contexts.current()));
    }

    @PostMapping("/pt-states")
    @PreAuthorize("@authz.gate('M-18','CREATE')")
    public ResponseEntity<Pending<StateProposal>> proposeState(@Valid @RequestBody StateRequest r) {
        return accepted(
                service.proposeState(
                        contexts.current(),
                        false,
                        proposal(r.code(), r),
                        r.reason(),
                        Otp.proof(r.otp())));
    }

    @PutMapping("/pt-states/{code}")
    @PreAuthorize("@authz.gate('M-18','EDIT')")
    public ResponseEntity<Pending<StateProposal>> proposeStateChange(
            @PathVariable @Pattern(regexp = STATE_CODE) String code,
            @Valid @RequestBody StateRequest r) {
        return accepted(
                service.proposeState(
                        contexts.current(),
                        true,
                        proposal(code, r),
                        r.reason(),
                        Otp.proof(r.otp())));
    }

    /**
     * The state code is the path on an edit (a code in the body is ignored), the body on a create.
     */
    private static StateProposal proposal(String code, StateRequest r) {
        return new StateProposal(
                code,
                r.name(),
                r.ptApplicable(),
                r.registrationNumber(),
                r.returnPeriodicity(),
                r.effectiveFrom(),
                r.effectiveTo(),
                null,
                null);
    }

    private static <T> ResponseEntity<T> accepted(T body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
