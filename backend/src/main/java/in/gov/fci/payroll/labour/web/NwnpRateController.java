package in.gov.fci.payroll.labour.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.labour.business.LabourRateRules;
import in.gov.fci.payroll.labour.business.NwnpRateRules;
import in.gov.fci.payroll.labour.orchestration.NwnpRateService;
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
 * M-05 Labour Rate Tables ▸ NWNP API (plan 22 phase 22b; SRS-5-2, SRS-5-3; API-22-1):
 *
 * <pre>
 * GET  /v1/labour/nwnp-rate-sets           the depots' NWNP rate sets, paged        M-05 VIEW
 * GET  /v1/labour/nwnp-rate-sets/options   operations, designations, units, depots  M-05 VIEW
 * POST /v1/labour/nwnp-rate-sets           a new rate set of a depot (202)          M-05 CREATE
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class NwnpRateController {

    /** One operation's rate. */
    public record LineBody(
            @NotBlank @Size(max = 20) @PrintableAscii String operationCode,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2)
                    BigDecimal pieceOfRate,
            @NotBlank @Size(max = 20) @PrintableAscii String unitOfMeasure) {}

    /** One designation's MGW. */
    public record MgwBody(
            @NotNull Long designationId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2)
                    BigDecimal mgw) {}

    /** A rate set. */
    public record SetBody(
            @NotNull Long depotLocationId,
            @NotNull @Pattern(regexp = "^(SOR|ASOR)$") String version,
            @NotNull LocalDate effectiveFrom,
            @NotNull @Size(min = 1, max = 60) List<@Valid LineBody> lines,
            @NotNull @Size(min = 1, max = 40) List<@Valid MgwBody> mgw,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final NwnpRateService rates;
    private final Paging paging;

    public NwnpRateController(CurrentAccess contexts, NwnpRateService rates, Paging paging) {
        this.contexts = contexts;
        this.rates = rates;
        this.paging = paging;
    }

    @GetMapping("/v1/labour/nwnp-rate-sets")
    @PreAuthorize("@authz.gate('M-05','VIEW')")
    public ResponseEntity<Paged<NwnpRateService.RateSet>> page(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(rates.page(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/nwnp-rate-sets/options")
    @PreAuthorize("@authz.gate('M-05','VIEW')")
    public ResponseEntity<NwnpRateService.Options> options() {
        return noStore(rates.options(contexts.current()));
    }

    @PostMapping("/v1/labour/nwnp-rate-sets")
    @PreAuthorize("@authz.gate('M-05','CREATE')")
    public ResponseEntity<SubmittedApproval> propose(@Valid @RequestBody SetBody b) {
        SubmittedApproval item =
                rates.propose(
                        contexts.current(),
                        new NwnpRateService.Proposal(
                                b.depotLocationId(),
                                b.version(),
                                b.effectiveFrom(),
                                b.lines().stream()
                                        .map(
                                                l ->
                                                        new NwnpRateRules.Line(
                                                                l.operationCode(),
                                                                l.pieceOfRate(),
                                                                l.unitOfMeasure()))
                                        .toList(),
                                b.mgw().stream()
                                        .map(
                                                m ->
                                                        new LabourRateRules.Rate(
                                                                m.designationId(), m.mgw()))
                                        .toList(),
                                b.reason()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(item);
    }
}
