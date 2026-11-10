package in.gov.fci.payroll.cpf.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.cpf.orchestration.InterestRateService;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.workflow.api.Otp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * M-19 PF Interest Rates (SRS-6-11, BR-CPF-033). The Rule Approver decides in the Approvals Inbox
 * (W-01 / X-02).
 *
 * <pre>
 * GET  /v1/cpf/interest-rates                  rates and pending changes   M-19 VIEW
 * POST /v1/cpf/interest-rates                  record a rate               M-19 CREATE -> M19_CREATE
 * POST /v1/cpf/interest-rates/{id}/declare     declare a recorded rate     M-19 EDIT   -> M19_EDIT
 * </pre>
 *
 * No DELETE: a rate is replaced by a later one, never deleted (R-DEL).
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/cpf/interest-rates")
public class InterestRateController {

    public record RateRequest(
            @NotBlank @Pattern(regexp = "^\\d{4}-\\d{2}$") String finYear,
            @NotBlank @Pattern(regexp = "^(CPF|PF|VPF|GPF)$") String fundType,
            @NotBlank @Pattern(regexp = "^(FCI_TRUST|RPFC)$") String rateSource,
            @NotNull @Digits(integer = 3, fraction = 2) BigDecimal ratePercent,
            @NotNull LocalDate appliesFrom,
            LocalDate appliesTo,
            @Size(max = 500) @PrintableAscii String applicabilityNote,
            @Digits(integer = 3, fraction = 2) BigDecimal bonusInterestPct,
            boolean declared,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    public record DeclareRequest(
            @NotNull @Digits(integer = 3, fraction = 2) BigDecimal ratePercent,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    private final CurrentAccess contexts;
    private final InterestRateService service;

    public InterestRateController(CurrentAccess contexts, InterestRateService service) {
        this.contexts = contexts;
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('M-19','VIEW')")
    public ResponseEntity<InterestRateService.Rates> list() {
        return noStore(service.list(contexts.current()));
    }

    @PostMapping
    @PreAuthorize("@authz.gate('M-19','CREATE')")
    public ResponseEntity<InterestRateService.PendingChange> recordRate(
            @Valid @RequestBody RateRequest r) {
        return accepted(
                service.recordRate(
                        contexts.current(),
                        new InterestRateService.RateRequest(
                                r.finYear(),
                                r.fundType(),
                                r.rateSource(),
                                r.ratePercent(),
                                r.appliesFrom(),
                                r.appliesTo(),
                                r.applicabilityNote(),
                                r.bonusInterestPct(),
                                r.declared(),
                                r.reason()),
                        Otp.proof(r.otp())));
    }

    @PostMapping("/{id}/declare")
    @PreAuthorize("@authz.gate('M-19','EDIT')")
    public ResponseEntity<InterestRateService.PendingChange> declare(
            @PathVariable @Positive Long id, @Valid @RequestBody DeclareRequest r) {
        return accepted(
                service.declare(
                        contexts.current(), id, r.ratePercent(), r.reason(), Otp.proof(r.otp())));
    }

    private static <T> ResponseEntity<T> accepted(T body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
