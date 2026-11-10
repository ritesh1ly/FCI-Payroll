package in.gov.fci.payroll.tax.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.tax.business.OutputRules;
import in.gov.fci.payroll.tax.business.model.OutputField.Binding;
import in.gov.fci.payroll.tax.orchestration.OutputDefinitionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * M-17 Statutory Output Definitions (SRS M-17, BR-TAX-013). The Rule Approver decides in the
 * Approvals Inbox (W-01).
 *
 * <pre>
 * GET  /v1/tax/output-definitions                    the statutory outputs, their versions and state    M-17 VIEW
 * POST /v1/tax/output-definitions/{key}/versions     a new effective-dated version                      M-17 CREATE -> M17_VERSION
 * </pre>
 *
 * The catalogue is fixed (the outputs the SRS names); there is no DELETE (R-DEL). The catalogue is
 * a bounded list returned whole. Tracker API-08-5.
 */
@RestController
@Validated
@Profile("online")
public class OutputDefinitionController {

    public record FieldRequest(
            @NotBlank @Size(max = 200) @PrintableAscii String label,
            @NotNull Binding binding,
            @NotBlank @Size(max = 1000) @PrintableAscii String reference,
            @Size(max = 60) @PrintableAscii String formatMask) {}

    public record ControlRequest(
            @NotBlank @Size(max = 200) @PrintableAscii String label,
            @NotBlank
                    @Pattern(regexp = "^(POSTED_PAYROLL|GL_POSTING_STATEMENT|BANK_ADVICE|CHALLAN)$")
                    String reconcilesAgainst,
            @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal tolerance,
            boolean releaseGate) {}

    public record VersionRequest(
            @NotNull LocalDate effectiveFrom,
            @NotBlank @Pattern(regexp = "^(PDF|EXCEL|CSV|PRESCRIBED_FILE|HTML)$")
                    String layoutFormat,
            @Size(max = 4000) @PrintableAscii String layoutNote,
            @Size(max = 100) @Pattern(regexp = "^[A-Z0-9_.]{0,100}$") String roundingRuleKey,
            boolean periodLockRequired,
            @NotNull @Size(min = 1, max = 200) List<@NotNull @Valid FieldRequest> fields,
            @Size(max = 20) List<@NotNull @Valid ControlRequest> controls,
            @NotBlank @Size(max = 1000) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final OutputDefinitionService service;

    public OutputDefinitionController(CurrentAccess contexts, OutputDefinitionService service) {
        this.contexts = contexts;
        this.service = service;
    }

    @GetMapping("/v1/tax/output-definitions")
    @PreAuthorize("@authz.gate('M-17','VIEW')")
    public ResponseEntity<List<OutputDefinitionService.OutputView>> catalogue() {
        return noStore(service.catalogue(contexts.current()));
    }

    @PostMapping("/v1/tax/output-definitions/{key}/versions")
    @PreAuthorize("@authz.gate('M-17','CREATE')")
    public ResponseEntity<Map<String, Long>> propose(
            @PathVariable @Pattern(regexp = "^[A-Z0-9_]{1,60}$") String key,
            @Valid @RequestBody VersionRequest r) {
        Long approval =
                service.propose(
                        contexts.current(),
                        new OutputDefinitionService.Proposal(
                                key,
                                r.effectiveFrom(),
                                r.layoutFormat(),
                                r.layoutNote(),
                                r.roundingRuleKey(),
                                r.periodLockRequired(),
                                r.fields().stream()
                                        .map(
                                                f ->
                                                        new OutputRules.Field(
                                                                f.label(),
                                                                f.binding(),
                                                                f.reference(),
                                                                f.formatMask()))
                                        .toList(),
                                r.controls() == null
                                        ? List.of()
                                        : r.controls().stream()
                                                .map(
                                                        c ->
                                                                new OutputRules.Control(
                                                                        c.label(),
                                                                        c.reconcilesAgainst(),
                                                                        c.tolerance(),
                                                                        c.releaseGate()))
                                                .toList(),
                                null,
                                null),
                        r.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("approvalId", approval));
    }
}
