package in.gov.fci.payroll.configuration.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.configuration.business.ComponentRules;
import in.gov.fci.payroll.configuration.orchestration.ComponentCatalogueService;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * M-07 Component Catalogue (plan 06 phase 06d; SRS M-07). Tracker API-06-2.
 *
 * <pre>
 * GET  /v1/pay-components?class&amp;type&amp;source&amp;taxable&amp;cpf&amp;page&amp;size   the catalogue  M-07 VIEW
 * GET  /v1/pay-components/export                              export         M-07 EXPORT
 * GET  /v1/pay-components/{code}/usages                        where used     M-07 VIEW
 * POST /v1/pay-components                                      define (202)   M-07 CREATE
 * POST /v1/pay-components/{code}/revisions                     revise (202)   M-07 CREATE
 * POST /v1/pay-components/{code}/retirements                   retire (202)   M-07 EDIT
 * </pre>
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/pay-components")
public class ComponentCatalogueController {

    private static final String CODE = "^[A-Z_]{1,20}$";

    /** A component as the Rule Administrator defines or revises it. */
    public record ComponentBody(
            @NotNull @Min(1) @Max(999) Integer payCode,
            @NotBlank @Size(max = 100) @PrintableAscii String name,
            @NotNull @Pattern(regexp = "^(EARNING|DEDUCTION)$") String type,
            @Pattern(regexp = "^[A-Z_]{1,40}$") String payslipGroup,
            @Pattern(regexp = "^[A-Z0-9_.]{1,100}$") String basisRuleKey,
            @NotNull @Pattern(regexp = "^(ENT|CALC|HRMS|RULE|SYS|ESS|INTF)$") String source,
            @NotNull @Pattern(regexp = "^[A-Z0-9][A-Z0-9-]{1,19}$") String naturalAccount,
            @NotNull @Pattern(regexp = "^(Y|N|PARTLY)$") String taxable,
            @NotNull Boolean inCpfBase,
            @NotNull @Size(min = 1, max = 4) Set<@Pattern(regexp = CODE) String> payrollTypes,
            @NotNull LocalDate effectiveFrom,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            // SRS-LV-15 / BR-LEAVE-007 (US-GAP-298): the M-07 "Subject to LWP" attribute.
            Boolean subjectToLwp,
            // SRS l.1909, SC-06-0028: DDL pay_component.eligibility_rule_key, optional.
            @Pattern(regexp = "^[A-Z0-9_.*]{1,100}$") String eligibilityRuleKey) {

        ComponentRules.Definition definition() {
            return new ComponentRules.Definition(
                    payCode,
                    name,
                    type,
                    payslipGroup,
                    basisRuleKey,
                    source,
                    naturalAccount,
                    taxable,
                    inCpfBase,
                    payrollTypes,
                    effectiveFrom,
                    subjectToLwp,
                    eligibilityRuleKey);
        }
    }

    /** A retirement: the last day the code is in force. */
    public record RetireBody(
            @NotNull LocalDate lastDay, @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final ComponentCatalogueService service;
    private final Paging paging;
    private final ExportStreaming exports;

    public ComponentCatalogueController(
            CurrentAccess contexts,
            ComponentCatalogueService service,
            Paging paging,
            ExportStreaming exports) {
        this.exports = exports;
        this.contexts = contexts;
        this.service = service;
        this.paging = paging;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('M-07','VIEW')")
    public ResponseEntity<Paged<ComponentCatalogueService.ComponentRow>> list(
            @RequestParam(name = "class", required = false) @Pattern(regexp = "^(STAFF|LABOUR)$")
                    String componentClass,
            @RequestParam(required = false) @Pattern(regexp = "^(EARNING|DEDUCTION)$") String type,
            @RequestParam(required = false) @Pattern(regexp = "^(ENT|CALC|HRMS|RULE|SYS|ESS|INTF)$")
                    String source,
            @RequestParam(required = false) @Pattern(regexp = "^(Y|N|PARTLY)$") String taxable,
            @RequestParam(required = false) @Pattern(regexp = "^(Y|N)$") String cpf,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        service.list(
                                contexts.current(),
                                new ComponentCatalogueService.Filter(
                                        componentClass, type, source, taxable, cpf, q)),
                        paging.resolve(page, size)));
    }

    /** ACT Export: the catalogue as filtered (API-06-1). */
    @GetMapping("/export")
    @PreAuthorize("@authz.gate('M-07','EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(name = "class", required = false) @Pattern(regexp = "^(STAFF|LABOUR)$")
                    String componentClass,
            @RequestParam(required = false) @Pattern(regexp = "^(EARNING|DEDUCTION)$") String type,
            @RequestParam(required = false) @Pattern(regexp = "^(ENT|CALC|HRMS|RULE|SYS|ESS|INTF)$")
                    String source,
            @RequestParam(required = false) @Pattern(regexp = "^(Y|N|PARTLY)$") String taxable,
            @RequestParam(required = false) @Pattern(regexp = "^(Y|N)$") String cpf,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        ComponentCatalogueService.Filter filter =
                new ComponentCatalogueService.Filter(componentClass, type, source, taxable, cpf, q);
        exports.checkSize(service.exportSize(context, filter));
        return exports.stream(
                "component-catalogue", format, sink -> service.export(context, filter, sink));
    }

    @GetMapping("/{code}/usages")
    @PreAuthorize("@authz.gate('M-07','VIEW')")
    public ResponseEntity<List<ComponentCatalogueService.Usage>> usages(
            @PathVariable @Min(1) @Max(999) int code) {
        return noStore(service.whereUsed(contexts.current(), code));
    }

    @PostMapping
    @PreAuthorize("@authz.gate('M-07','CREATE')")
    public ResponseEntity<SubmittedApproval> define(@Valid @RequestBody ComponentBody body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.define(contexts.current(), body.definition(), body.reason()));
    }

    @PostMapping("/{code}/revisions")
    @PreAuthorize("@authz.gate('M-07','CREATE')")
    public ResponseEntity<SubmittedApproval> revise(
            @PathVariable @Min(1) @Max(999) int code, @Valid @RequestBody ComponentBody body) {
        if (body.payCode() != code) {
            throw new in.gov.fci.payroll.platform.error.FciException(
                    in.gov.fci.payroll.platform.error.CommonErrorCode.VALIDATION_FAILED);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.revise(contexts.current(), body.definition(), body.reason()));
    }

    @PostMapping("/{code}/retirements")
    @PreAuthorize("@authz.gate('M-07','EDIT')")
    public ResponseEntity<SubmittedApproval> retire(
            @PathVariable @Min(1) @Max(999) int code, @Valid @RequestBody RetireBody body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.retire(contexts.current(), code, body.lastDay(), body.reason()));
    }
}
