package in.gov.fci.payroll.labour.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.labour.business.LabourRateRules;
import in.gov.fci.payroll.labour.orchestration.LabourRateService;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-05 Labour Rate Tables API (plan 21 phase 21c; SRS-4-11; API-21-1):
 *
 * <pre>
 * GET  /v1/labour/rate-sets?payrollType=        the payroll type's rate sets, paged   M-05 VIEW
 * GET  /v1/labour/rate-sets/designations?payrollType=   the designations to rate     M-05 VIEW
 * POST /v1/labour/rate-sets                     a new rate set (202)                  M-05 CREATE
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class LabourRateController {

    private static final String PAYROLL_TYPE = "^(DEPT_LABOUR|DPS_LABOUR|NWNP_LABOUR)$";

    /**
     * One designation's rates: the MGW day rate (DPS), or the Basic wage and DA (Departmental,
     * SRS-3-13); the service checks which the payroll type needs.
     */
    public record RateBody(
            @NotNull Long designationId,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2)
                    BigDecimal mgw,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2)
                    BigDecimal basicWage,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal da) {}

    /** A rate set. */
    public record RateSetBody(
            @NotNull @Pattern(regexp = PAYROLL_TYPE) String payrollType,
            Long depotLocationId,
            @NotNull LocalDate effectiveFrom,
            @NotNull @Size(min = 1, max = 40) List<@Valid RateBody> rates,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal interimRelief,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2)
                    BigDecimal goiRate,
            @Size(max = 200) @PrintableAscii String goiRateSource,
            @Pattern(regexp = "^(PER_DAY|PER_PERIOD|PER_LABOUR)$") String comparisonGrain,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final LabourRateService rates;
    private final Paging paging;

    public LabourRateController(CurrentAccess contexts, LabourRateService rates, Paging paging) {
        this.contexts = contexts;
        this.rates = rates;
        this.paging = paging;
    }

    @GetMapping("/v1/labour/rate-sets")
    @PreAuthorize("@authz.gate('M-05','VIEW')")
    public ResponseEntity<Paged<LabourRateService.RateSet>> page(
            @RequestParam @Pattern(regexp = PAYROLL_TYPE) String payrollType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(rates.page(contexts.current(), payrollType, paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/rate-sets/designations")
    @PreAuthorize("@authz.gate('M-05','VIEW')")
    public ResponseEntity<List<LabourRateService.Designation>> designations(
            @RequestParam @Pattern(regexp = PAYROLL_TYPE) String payrollType) {
        return noStore(rates.designations(contexts.current(), payrollType));
    }

    @PostMapping("/v1/labour/rate-sets")
    @PreAuthorize("@authz.gate('M-05','CREATE')")
    public ResponseEntity<SubmittedApproval> propose(@Valid @RequestBody RateSetBody b) {
        SubmittedApproval item =
                rates.propose(
                        contexts.current(),
                        new LabourRateService.Proposal(
                                b.payrollType(),
                                b.depotLocationId(),
                                b.effectiveFrom(),
                                b.rates().stream()
                                        .map(
                                                r ->
                                                        new LabourRateRules.Rate(
                                                                r.designationId(),
                                                                r.mgw(),
                                                                r.basicWage(),
                                                                r.da()))
                                        .toList(),
                                b.interimRelief(),
                                b.goiRate(),
                                b.goiRateSource(),
                                b.comparisonGrain(),
                                b.reason()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(item);
    }
}
