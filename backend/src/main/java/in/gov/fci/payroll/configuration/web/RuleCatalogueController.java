package in.gov.fci.payroll.configuration.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.configuration.orchestration.RuleCatalogueService;
import in.gov.fci.payroll.configuration.orchestration.RuleReadThroughService;
import in.gov.fci.payroll.configuration.orchestration.RuleSimulationService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * M-08 Rule / Parameter Umbrella Catalogue (SRS-19-4, SRS-19-14, SRS-19-21): the catalogue, a
 * rule's versions, the pending changes of M-08, M-14 and M-15, and the Rule Administrator's
 * proposals (a version, an end date), each answered 202 and in force only after the Rule Approver
 * approves it in the inbox. Each endpoint has its own permission gate; the service checks the
 * action again (CREATE for a rule's first version, EDIT after) and Headquarters.
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/rules")
public class RuleCatalogueController {

    /** A rule key as the catalogue holds it (rule_definition.rule_key, VARCHAR2(100)). */
    static final String RULE_KEY = "^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$";

    public record BandRequest(
            @NotNull @Positive Integer order,
            @NotNull @Digits(integer = 10, fraction = 2) BigDecimal from,
            @Digits(integer = 10, fraction = 2) BigDecimal to,
            @NotNull @Digits(integer = 10, fraction = 2) BigDecimal rateOrAmount,
            @Digits(integer = 10, fraction = 2) BigDecimal step,
            @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,39}$") String qualifier) {}

    public record VersionRequest(
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @Digits(integer = 10, fraction = 2) BigDecimal value,
            @Size(max = 4000) @PrintableAscii String formula,
            @Size(max = 200) List<@Valid @NotNull BandRequest> bands,
            @Pattern(regexp = "^(" + "[A-Za-z0-9][A-Za-z0-9._-]{0,99}" + ")?$")
                    String roundingRuleKey,
            @NotNull @Positive Long circularId,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    public record EndRequest(
            @NotNull LocalDate endOn,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    /** The sample of an impact simulation (06e): a payroll type and an office, null = every. */
    public record SimulationRequest(
            @Pattern(regexp = "^(STAFF|DEPT_LABOUR|DPS_LABOUR|NWNP_LABOUR)$") String payrollType,
            @Positive Long officeId) {}

    private final CurrentAccess contexts;
    private final RuleCatalogueService catalogue;
    private final RuleSimulationService simulations;
    private final Paging paging;
    private final ExportStreaming exports;
    private final RuleReadThroughService readThrough;

    public RuleCatalogueController(
            CurrentAccess contexts,
            RuleCatalogueService catalogue,
            RuleSimulationService simulations,
            Paging paging,
            ExportStreaming exports,
            RuleReadThroughService readThrough) {
        this.readThrough = readThrough;
        this.paging = paging;
        this.exports = exports;
        this.contexts = contexts;
        this.catalogue = catalogue;
        this.simulations = simulations;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('M-08','VIEW')")
    public ResponseEntity<RuleCatalogueService.Catalogue> rules() {
        return noStore(catalogue.catalogue(contexts.current()));
    }

    /** M-18 Statutory Rate Seed: its own rules, on M-18 VIEW (SRS-6-47; PENDING API-07-13). */
    @GetMapping("/register/m-18")
    @PreAuthorize("@authz.gate('M-18','VIEW')")
    public ResponseEntity<List<RuleCatalogueService.RuleRow>> statutoryRegister() {
        return noStore(catalogue.register(contexts.current(), "M-18"));
    }

    /** M-19 CPF / VPF Parameters: its own rules, on M-19 VIEW (SRS M-19; PENDING API-07-13). */
    @GetMapping("/register/m-19")
    @PreAuthorize("@authz.gate('M-19','VIEW')")
    public ResponseEntity<List<RuleCatalogueService.RuleRow>> parameterRegister() {
        return noStore(catalogue.register(contexts.current(), "M-19"));
    }

    /** SRS-19-21: the domains entered on M-14, M-15 and M-20, read-only (API-06-2). */
    @GetMapping("/read-through")
    @PreAuthorize("@authz.gate('M-08','VIEW')")
    public ResponseEntity<List<RuleReadThroughService.ReadThrough>> readThrough() {
        return noStore(readThrough.rows(contexts.current()));
    }

    @GetMapping("/pending")
    @PreAuthorize("@authz.gate('M-08','VIEW')")
    public ResponseEntity<Paged<RuleCatalogueService.PendingItem>> pending(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(catalogue.pending(contexts.current()), paging.resolve(page, size)));
    }

    /** SRS-19-21: the Rule Catalogue Summary report on demand (all rules by type and status). */
    @GetMapping("/export")
    @PreAuthorize("@authz.gate('M-08','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportCatalogue(
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(catalogue.catalogueSize(context));
        return exports.stream(
                "rule-catalogue-summary", format, sink -> catalogue.exportCatalogue(context, sink));
    }

    /** SRS-19-21: the Pending Approval — Cross-Domain report on demand (M-08, M-14, M-15). */
    @GetMapping("/pending/export")
    @PreAuthorize("@authz.gate('M-08','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportPending(
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(catalogue.pendingCountsSize());
        return exports.stream(
                "pending-approval-cross-domain",
                format,
                sink -> catalogue.exportPendingCounts(context, sink));
    }

    @GetMapping("/vocabulary")
    @PreAuthorize("@authz.gate('M-08','VIEW')")
    public ResponseEntity<RuleEngine.Vocabulary> vocabulary() {
        return noStore(catalogue.vocabulary(contexts.current()));
    }

    @GetMapping("/{key}/versions")
    @PreAuthorize("@authz.gate('M-08','VIEW')")
    public ResponseEntity<Paged<RuleCatalogueService.VersionView>> versions(
            @PathVariable @Pattern(regexp = RULE_KEY) String key,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        catalogue.versions(contexts.current(), key), paging.resolve(page, size)));
    }

    /** CREATE or EDIT, checked by the service (the first version of a rule is a CREATE). */
    @PostMapping("/{key}/versions")
    @PreAuthorize("@authz.gateEither('M-08','CREATE','M-08','EDIT')")
    public ResponseEntity<RuleCatalogueService.VersionView> propose(
            @PathVariable @Pattern(regexp = RULE_KEY) String key,
            @Valid @RequestBody VersionRequest r) {
        return accepted(
                catalogue.propose(
                        contexts.current(),
                        key,
                        new RuleCatalogueService.VersionRequest(
                                r.effectiveFrom(),
                                r.effectiveTo(),
                                r.value(),
                                r.formula(),
                                r.bands() == null
                                        ? null
                                        : r.bands().stream()
                                                .map(
                                                        b ->
                                                                new RuleCatalogueService.Band(
                                                                        b.order(),
                                                                        b.from(),
                                                                        b.to(),
                                                                        b.rateOrAmount(),
                                                                        b.step(),
                                                                        b.qualifier()))
                                                .toList(),
                                r.roundingRuleKey(),
                                r.circularId(),
                                r.reason()),
                        Otp.proof(r.otp())));
    }

    @PostMapping("/{key}/end")
    @PreAuthorize("@authz.gate('M-08','EDIT')")
    public ResponseEntity<RuleCatalogueService.PendingEnd> end(
            @PathVariable @Pattern(regexp = RULE_KEY) String key,
            @Valid @RequestBody EndRequest r) {
        return accepted(
                catalogue.end(
                        contexts.current(),
                        key,
                        new RuleCatalogueService.EndRequest(r.endOn(), r.reason()),
                        Otp.proof(r.otp())));
    }

    /** The offices a simulation's sample can be narrowed to (06e). */
    @GetMapping("/simulation-offices")
    @PreAuthorize("@authz.gateEither('M-08','CREATE','M-08','APPROVE')")
    public ResponseEntity<List<RuleSimulationService.Office>> simulationOffices() {
        return noStore(simulations.offices(contexts.current()));
    }

    /** SRS-19-4: the simulations of the version awaiting approval (06e). */
    @GetMapping("/{key}/simulations")
    @PreAuthorize("@authz.gate('M-08','VIEW')")
    public ResponseEntity<Paged<RuleSimulationService.Simulation>> simulations(
            @PathVariable @Pattern(regexp = RULE_KEY) String key,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        simulations.simulations(contexts.current(), key),
                        paging.resolve(page, size)));
    }

    /**
     * SRS-19-4: simulates the version awaiting approval on a sample (Rule Administrator, Rule
     * Approver).
     */
    @PostMapping("/{key}/simulations")
    @PreAuthorize("@authz.gateEither('M-08','CREATE','M-08','APPROVE')")
    public ResponseEntity<RuleSimulationService.Simulation> simulate(
            @PathVariable @Pattern(regexp = RULE_KEY) String key,
            @Valid @RequestBody SimulationRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        simulations.simulate(
                                contexts.current(),
                                key,
                                new RuleSimulationService.Request(r.payrollType(), r.officeId())));
    }

    private static <T> ResponseEntity<T> accepted(T body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
