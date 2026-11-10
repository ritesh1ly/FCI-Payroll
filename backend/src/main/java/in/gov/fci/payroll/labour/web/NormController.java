package in.gov.fci.payroll.labour.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.labour.business.NormRules;
import in.gov.fci.payroll.labour.orchestration.NormService;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
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
 * M-06 Incentive Norms API (plan 20 phase 20d; SRS-3-6, SRS-3-12; API-20-1):
 *
 * <pre>
 * GET  /v1/labour/norm-sets          the depots' norm sets, paged    M-06 VIEW
 * GET  /v1/labour/norm-sets/depots   the depots to choose            M-06 VIEW
 * POST /v1/labour/norm-sets   a new norm set of a depot (202)        M-06 CREATE
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class NormController {

    /** One slab band. */
    public record SlabBody(
            @NotNull @Pattern(regexp = "^(BAND_1|BAND_2)$") String table,
            @Min(1) @Max(99) int order,
            @Min(1) long from,
            @Min(1) Long to,
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
                    BigDecimal ratePercent) {}

    /** A norm set; {@code mouCircularId} is the depot's union MoU on M-25 (SRS M-06). */
    public record NormBody(
            @NotNull Long depotLocationId,
            @NotNull LocalDate effectiveFrom,
            @NotNull @Min(0) @Max(999_999) Long handling,
            @NotNull @Min(0) @Max(999_999) Long height,
            @NotNull @Min(0) @Max(999_999) Long lead,
            @NotNull @Min(1) @Max(999_999) Long minLabourNumber,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 6, fraction = 2)
                    BigDecimal minSlabWeight,
            @Size(max = 100) @PrintableAscii String mouReference,
            @NotNull @Size(min = 1, max = 40) List<@Valid SlabBody> slabs,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Positive Long mouCircularId) {}

    private final CurrentAccess contexts;
    private final NormService norms;
    private final Paging paging;

    public NormController(CurrentAccess contexts, NormService norms, Paging paging) {
        this.contexts = contexts;
        this.norms = norms;
        this.paging = paging;
    }

    @GetMapping("/v1/labour/norm-sets")
    @PreAuthorize("@authz.gate('M-06','VIEW')")
    public ResponseEntity<Paged<NormService.NormSet>> page(
            @RequestParam(required = false) Long depot,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(norms.page(contexts.current(), depot, paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/norm-sets/depots")
    @PreAuthorize("@authz.gate('M-06','VIEW')")
    public ResponseEntity<List<NormService.Depot>> depots() {
        return noStore(norms.depots(contexts.current()));
    }

    @PostMapping("/v1/labour/norm-sets")
    @PreAuthorize("@authz.gate('M-06','CREATE')")
    public ResponseEntity<SubmittedApproval> propose(@Valid @RequestBody NormBody b) {
        SubmittedApproval item =
                norms.propose(
                        contexts.current(),
                        new NormService.Proposal(
                                b.depotLocationId(),
                                b.effectiveFrom(),
                                new NormRules.Norms(
                                        b.handling(),
                                        b.height(),
                                        b.lead(),
                                        b.minLabourNumber(),
                                        b.minSlabWeight()),
                                b.mouReference(),
                                b.slabs().stream()
                                        .map(
                                                s ->
                                                        new NormRules.Slab(
                                                                s.table(),
                                                                s.order(),
                                                                s.from(),
                                                                s.to(),
                                                                s.ratePercent()))
                                        .toList(),
                                b.reason(),
                                b.mouCircularId()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(item);
    }
}
