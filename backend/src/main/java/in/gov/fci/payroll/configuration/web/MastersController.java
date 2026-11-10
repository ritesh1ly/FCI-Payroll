package in.gov.fci.payroll.configuration.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.configuration.orchestration.GlMappingService;
import in.gov.fci.payroll.configuration.orchestration.OtherParameterService;
import in.gov.fci.payroll.configuration.orchestration.RateMasterService;
import in.gov.fci.payroll.configuration.orchestration.RateScreenService;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.workflow.api.Otp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
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
 * The dynamic master screens (target DDL v4.0): M-04 staff rates and M-22 special rates (rate
 * sets), M-23 other parameters, M-24 GL mapping. Each endpoint has its own fixed permission gate
 * (never one derived from the request), the service checks it again and keeps each screen to its
 * own catalogue, and every change goes through maker-checker: a proposal answers 202 and grants
 * nothing until the Rule Approver (who is never the maker) approves it in the inbox.
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/masters")
public class MastersController {

    private static final String CODE = "^[A-Z][A-Z0-9_]{1,39}$";
    private static final String COMPUTED_ON = "^[A-Za-z0-9 +/().,%-]*$";

    public record LineRequest(
            Long designationId,
            @Size(max = 10) @PrintableAscii String categoryCode,
            @Pattern(regexp = "^(IDA|CDA|FND)?$") String payPattern,
            BigDecimal basicSlabFrom,
            BigDecimal basicSlabTo,
            @Pattern(regexp = "^[XYZ]?$") String cityClass,
            @NotBlank @Pattern(regexp = CODE) String rateCode,
            @NotNull BigDecimal rateValue,
            @Pattern(regexp = "^(PERCENT|INR|UNIT)?$") String unit) {}

    public record RateVersionRequest(
            @NotNull LocalDate effectiveFrom,
            Long circularOrderId,
            @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Size(max = 200) @Pattern(regexp = COMPUTED_ON) String computedOn,
            @Valid Otp otp) {}

    public record ParameterVersionRequest(
            @NotNull LocalDate effectiveFrom,
            BigDecimal numericValue,
            BigDecimal ceilingValue,
            BigDecimal percentValue,
            @Pattern(regexp = "^(\\d{2}:\\d{2})?$") String windowFromTime,
            @Pattern(regexp = "^(\\d{2}:\\d{2})?$") String windowToTime,
            @Min(1) @Max(9999) Integer instalmentCount,
            @Size(max = 100) @PrintableAscii String payrollTypeScope,
            boolean confirmed,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    public record MappingVersionRequest(
            @NotNull LocalDate effectiveFrom,
            @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9-]{1,19}$") String naturalAccount,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    private final CurrentAccess contexts;
    private final RateMasterService rates;
    private final OtherParameterService parameters;
    private final GlMappingService mappings;
    private final Paging paging;

    public MastersController(
            CurrentAccess contexts,
            RateMasterService rates,
            OtherParameterService parameters,
            GlMappingService mappings,
            Paging paging) {
        this.paging = paging;
        this.contexts = contexts;
        this.rates = rates;
        this.parameters = parameters;
        this.mappings = mappings;
    }

    // ---------------------------------------------------------------- M-04 staff rate tables

    @GetMapping("/staff-rates")
    @PreAuthorize("@authz.gate('M-04','VIEW')")
    public ResponseEntity<Paged<RateMasterService.RateMasterView>> staffRates(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        rates.list(contexts.current(), RateMasterService.Screen.STAFF),
                        paging.resolve(page, size)));
    }

    @GetMapping("/staff-rates/{code}/versions")
    @PreAuthorize("@authz.gate('M-04','VIEW')")
    public ResponseEntity<Paged<RateMasterService.VersionView>> staffRateHistory(
            @PathVariable @Pattern(regexp = CODE) String code,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        rates.history(contexts.current(), RateMasterService.Screen.STAFF, code),
                        paging.resolve(page, size)));
    }

    @PostMapping("/staff-rates/{code}/versions")
    @PreAuthorize("@authz.gate('M-04','CREATE')")
    public ResponseEntity<RateMasterService.VersionView> proposeStaffRate(
            @PathVariable @Pattern(regexp = CODE) String code,
            @Valid @RequestBody RateVersionRequest request) {
        return accepted(
                rates.propose(
                        contexts.current(),
                        RateMasterService.Screen.STAFF,
                        code,
                        rateVersion(request),
                        Otp.proof(request.otp())));
    }

    // ---------------------------------------------------------------- M-22 special compensatory
    // and other rate masters

    @GetMapping("/special-rates")
    @PreAuthorize("@authz.gate('M-22','VIEW')")
    public ResponseEntity<Paged<RateMasterService.RateMasterView>> specialRates(
            @RequestParam(required = false) @Pattern(regexp = "^(IDA|CDA)?$") String pattern,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z_]{0,40}$") String group,
            @RequestParam(required = false) @Size(max = 100) @PrintableAscii String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        RateScreenService.filter(
                                rates.list(contexts.current(), RateMasterService.Screen.SPECIAL),
                                pattern,
                                group,
                                q),
                        paging.resolve(page, size)));
    }

    @GetMapping("/special-rates/{code}/versions")
    @PreAuthorize("@authz.gate('M-22','VIEW')")
    public ResponseEntity<Paged<RateMasterService.VersionView>> specialRateHistory(
            @PathVariable @Pattern(regexp = CODE) String code,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        rates.history(contexts.current(), RateMasterService.Screen.SPECIAL, code),
                        paging.resolve(page, size)));
    }

    @PostMapping("/special-rates/{code}/versions")
    @PreAuthorize("@authz.gate('M-22','CREATE')")
    public ResponseEntity<RateMasterService.VersionView> proposeSpecialRate(
            @PathVariable @Pattern(regexp = CODE) String code,
            @Valid @RequestBody RateVersionRequest request) {
        return accepted(
                rates.propose(
                        contexts.current(),
                        RateMasterService.Screen.SPECIAL,
                        code,
                        rateVersion(request),
                        Otp.proof(request.otp())));
    }

    // ---------------------------------------------------------------- M-23 other parameters

    @GetMapping("/other-parameters")
    @PreAuthorize("@authz.gate('M-23','VIEW')")
    public ResponseEntity<Paged<OtherParameterService.ParameterRow>> otherParameters(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(parameters.list(contexts.current()), paging.resolve(page, size)));
    }

    @GetMapping("/other-parameters/{code}/versions")
    @PreAuthorize("@authz.gate('M-23','VIEW')")
    public ResponseEntity<Paged<OtherParameterService.ParameterVersion>> otherParameterHistory(
            @PathVariable @Pattern(regexp = CODE) String code,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        parameters.history(contexts.current(), code), paging.resolve(page, size)));
    }

    @PostMapping("/other-parameters/{code}/versions")
    @PreAuthorize("@authz.gate('M-23','CREATE')")
    public ResponseEntity<OtherParameterService.ParameterVersion> proposeOtherParameter(
            @PathVariable @Pattern(regexp = CODE) String code,
            @Valid @RequestBody ParameterVersionRequest r) {
        return accepted(
                parameters.propose(
                        contexts.current(),
                        code,
                        new OtherParameterService.ProposedParameter(
                                r.effectiveFrom(),
                                r.numericValue(),
                                r.ceilingValue(),
                                r.percentValue(),
                                r.windowFromTime(),
                                r.windowToTime(),
                                r.instalmentCount(),
                                r.payrollTypeScope(),
                                r.confirmed(),
                                r.reason()),
                        Otp.proof(r.otp())));
    }

    // ---------------------------------------------------------------- M-24 accounting mapping

    @GetMapping("/gl-mappings")
    @PreAuthorize("@authz.gate('M-24','VIEW')")
    public ResponseEntity<Paged<GlMappingService.ComponentRow>> glMappings(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(Paged.slice(mappings.list(contexts.current()), paging.resolve(page, size)));
    }

    @GetMapping("/gl-mappings/{payCode}/versions")
    @PreAuthorize("@authz.gate('M-24','VIEW')")
    public ResponseEntity<Paged<GlMappingService.MappingVersion>> glMappingHistory(
            @PathVariable @Min(1) @Max(999999) int payCode,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        mappings.history(contexts.current(), payCode), paging.resolve(page, size)));
    }

    @PostMapping("/gl-mappings/{payCode}/versions")
    @PreAuthorize("@authz.gate('M-24','CREATE')")
    public ResponseEntity<GlMappingService.MappingVersion> proposeGlMapping(
            @PathVariable @Min(1) @Max(999999) int payCode,
            @Valid @RequestBody MappingVersionRequest r) {
        return accepted(
                mappings.propose(
                        contexts.current(),
                        payCode,
                        new GlMappingService.ProposedMapping(
                                r.effectiveFrom(), r.naturalAccount(), r.reason()),
                        Otp.proof(r.otp())));
    }

    private static RateMasterService.ProposedVersion rateVersion(RateVersionRequest r) {
        return new RateMasterService.ProposedVersion(
                r.effectiveFrom(),
                r.circularOrderId(),
                r.lines().stream()
                        .map(
                                l ->
                                        new RateMasterService.LineView(
                                                l.designationId(),
                                                l.categoryCode(),
                                                l.payPattern(),
                                                l.basicSlabFrom(),
                                                l.basicSlabTo(),
                                                l.cityClass(),
                                                l.rateCode(),
                                                l.rateValue(),
                                                l.unit()))
                        .toList(),
                r.reason(),
                r.computedOn());
    }

    private static <T> ResponseEntity<T> accepted(T body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(body);
    }
}
