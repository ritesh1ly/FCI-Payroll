package in.gov.fci.payroll.arrear.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.arrear.business.WageRevisionRules;
import in.gov.fci.payroll.arrear.orchestration.WageRevisionService;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.workflow.api.Submitted;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * I-25 Wage Revision, steps 1 to 4 (plan 13 phase 13e; SRS-8-25). The SRS names the screen, not the
 * API (PENDING API-13-1).
 *
 * <pre>
 * GET  /v1/wage-revisions                       the revisions (paged)                 I-25 VIEW
 * POST /v1/wage-revisions                       define one (Headquarters)             I-25 CREATE
 * GET  /v1/wage-revisions/{id}                  definition, nine steps, totals        I-25 VIEW
 * POST /v1/wage-revisions/{id}/simulations      simulate (changes no pay)             I-25 CREATE
 * POST /v1/wage-revisions/{id}/submissions      for the HQ Checker's OTP approval     I-25 CREATE
 * GET  /v1/wage-revisions/{id}/members          the members in the offices (paged)    I-25 VIEW
 * GET  /v1/wage-revisions/{id}/members/export   the simulation sheet (file)           I-25 EXPORT
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class WageRevisionController {

    private static final String TYPE = "^(STAFF_IDA|STAFF_CDA|DEPT_LABOUR|DPS_LABOUR|NWNP_LABOUR)$";

    public record DefinitionBody(
            @NotNull @Size(min = 1, max = 250) @PrintableAscii String name,
            @NotNull Long circularId,
            @NotNull @Size(min = 1, max = 5) List<@Pattern(regexp = TYPE) String> payrollTypes,
            @Pattern(regexp = "^(ALL_INDIA|SELECTED)$") String scope,
            @NotNull LocalDate effectiveFrom,
            @NotNull WageRevisionRules.Fitment fitment,
            @NotNull @DecimalMin("0.0001") @DecimalMax("9999999999") BigDecimal value,
            @DecimalMin("0") @DecimalMax("9999999999") BigDecimal minimum,
            @DecimalMin("0") @DecimalMax("9999999999") BigDecimal maximum,
            @NotNull WageRevisionRules.Rounding rounding,
            Integer instalments,
            @NotNull @Size(min = 1, max = 500) @PrintableAscii String reason) {}

    public record SubmissionBody(@NotNull @Size(max = 500) @PrintableAscii String remark) {}

    private final CurrentAccess contexts;
    private final WageRevisionService revisions;
    private final Paging paging;
    private final ExportStreaming exports;

    public WageRevisionController(
            CurrentAccess contexts,
            WageRevisionService revisions,
            Paging paging,
            ExportStreaming exports) {
        this.contexts = contexts;
        this.revisions = revisions;
        this.paging = paging;
        this.exports = exports;
    }

    @GetMapping("/v1/wage-revisions")
    @PreAuthorize("@authz.gate('I-25','VIEW')")
    public ResponseEntity<Paged<WageRevisionService.Summary>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(revisions.revisions(contexts.current(), paging.resolve(page, size)));
    }

    @PostMapping("/v1/wage-revisions")
    @PreAuthorize("@authz.gate('I-25','CREATE')")
    public ResponseEntity<WageRevisionService.Detail> define(@Valid @RequestBody DefinitionBody b) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        revisions.define(
                                contexts.current(),
                                new WageRevisionService.Definition(
                                        b.name(),
                                        b.circularId(),
                                        b.payrollTypes(),
                                        b.scope(),
                                        b.effectiveFrom(),
                                        new WageRevisionRules.Rule(
                                                b.fitment(),
                                                b.value(),
                                                b.minimum(),
                                                b.maximum(),
                                                b.rounding()),
                                        b.instalments(),
                                        b.reason())));
    }

    @GetMapping("/v1/wage-revisions/{id}")
    @PreAuthorize("@authz.gate('I-25','VIEW')")
    public ResponseEntity<WageRevisionService.Detail> detail(@PathVariable long id) {
        return noStore(revisions.detail(contexts.current(), id));
    }

    @PostMapping("/v1/wage-revisions/{id}/simulations")
    @PreAuthorize("@authz.gate('I-25','CREATE')")
    public ResponseEntity<WageRevisionService.Detail> simulate(@PathVariable long id) {
        return noStore(revisions.simulate(contexts.current(), id));
    }

    /** The Checker approves in W-01 with the OTP WAGE_REVISION_HQ. */
    @PostMapping("/v1/wage-revisions/{id}/submissions")
    @PreAuthorize("@authz.gate('I-25','CREATE')")
    public ResponseEntity<Submitted> submit(
            @PathVariable long id, @Valid @RequestBody SubmissionBody body) {
        SubmittedApproval item = revisions.submit(contexts.current(), id, body.remark());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new Submitted(item.id(), item.status().name()));
    }

    @GetMapping("/v1/wage-revisions/{id}/members")
    @PreAuthorize("@authz.gate('I-25','VIEW')")
    public ResponseEntity<Paged<WageRevisionService.MemberRow>> members(
            @PathVariable long id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(revisions.members(contexts.current(), id, paging.resolve(page, size)));
    }

    @GetMapping("/v1/wage-revisions/{id}/members/export")
    @PreAuthorize("@authz.gate('I-25','EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(
            @PathVariable long id, @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(revisions.membersSize(context, id));
        return exports.stream(
                "wage-revision-members",
                format,
                sink -> revisions.exportMembers(context, id, sink));
    }
}
