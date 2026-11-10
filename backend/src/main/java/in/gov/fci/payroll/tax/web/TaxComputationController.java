package in.gov.fci.payroll.tax.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.tax.business.TaxableEarnings;
import in.gov.fci.payroll.tax.orchestration.TaxComputationService;
import in.gov.fci.payroll.tax.orchestration.TaxWhatIfService;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
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
 * C-07 Statutory Computation, income tax and PT (SRS-7-1 … SRS-7-4, SRS-7-12, SRS-7-19, SRS-7-27):
 * the Income Tax Forecast of a member, projected in-year and actual once every month is locked,
 * with the approved inputs and, where the computation cannot be made, its reasons.
 *
 * <pre>
 * GET /v1/tax/forecasts/{eis}?finYear                  the forecast              C-07 VIEW
 * GET /v1/tax/forecasts/{eis}/export?finYear&format    the forecast, CSV / Excel C-07 EXPORT
 * POST /v1/tax/forecasts/{eis}/what-ifs?finYear        a what-if forecast        C-09 RUN
 * POST /v1/tax/forecasts/mine/what-ifs?finYear         the employee's what-if    E-01 VIEW
 * </pre>
 *
 * <p>A what-if (SRS-7-28) is a simulation run: its record is kept, its result is returned once and
 * changes nothing (TaxWhatIfService). Tracker API-08-5.
 *
 * <p>SRS-7-27 asks for Excel and PDF: PDF waits for the platform's PDF decision (plan 01 D-6).
 *
 * <p>Read-only: the monthly TDS and PT are written by the payroll engines through {@code
 * tax.api.TaxDeductions}. Tracker API-08-4.
 */
@RestController
@Validated
@Profile("online")
public class TaxComputationController {

    private final CurrentAccess contexts;
    private final TaxComputationService service;
    private final TaxWhatIfService whatIfs;
    private final ExportStreaming exports;

    public TaxComputationController(
            CurrentAccess contexts,
            TaxComputationService service,
            TaxWhatIfService whatIfs,
            ExportStreaming exports) {
        this.contexts = contexts;
        this.service = service;
        this.whatIfs = whatIfs;
        this.exports = exports;
    }

    /** SRS-7-28: the employee's what-if on their own year. */
    @PostMapping("/v1/tax/forecasts/mine/what-ifs")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<TaxWhatIfService.Result> myWhatIf(
            @RequestParam(required = false) @Pattern(regexp = "^[0-9]{4}-[0-9]{2}$") String finYear,
            @RequestBody TaxWhatIfService.WhatIf change) {
        return noStore(whatIfs.mine(contexts.current(), finYear, change));
    }

    /** SRS-7-28: a what-if forecast of a member of the user's offices. */
    @PostMapping("/v1/tax/forecasts/{eis}/what-ifs")
    @PreAuthorize("@authz.gate('C-09','RUN')")
    public ResponseEntity<TaxWhatIfService.Result> whatIf(
            @PathVariable @Pattern(regexp = "^[0-9A-Za-z]{1,20}$") String eis,
            @RequestParam(required = false) @Pattern(regexp = "^[0-9]{4}-[0-9]{2}$") String finYear,
            @RequestBody TaxWhatIfService.WhatIf change) {
        return noStore(whatIfs.forMember(contexts.current(), eis, finYear, change));
    }

    @GetMapping("/v1/tax/forecasts/{eis}")
    @PreAuthorize("@authz.gate('C-07','VIEW')")
    public ResponseEntity<TaxComputationService.Forecast> forecast(
            @PathVariable @Pattern(regexp = "^[0-9A-Za-z]{1,20}$") String eis,
            @RequestParam(required = false) @Pattern(regexp = "^[0-9]{4}-[0-9]{2}$")
                    String finYear) {
        return noStore(service.forecast(contexts.current(), eis, finYear));
    }

    /**
     * SRS-7-27: the Income Tax Forecast of the member as a download (actual earning descriptions).
     */
    @GetMapping("/v1/tax/forecasts/{eis}/export")
    @PreAuthorize("@authz.gate('C-07','EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(
            @PathVariable @Pattern(regexp = "^[0-9A-Za-z]{1,20}$") String eis,
            @RequestParam(required = false) @Pattern(regexp = "^[0-9]{4}-[0-9]{2}$") String finYear,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        TaxComputationService.Forecast f = service.forecast(contexts.current(), eis, finYear);
        return exports.stream(
                "income-tax-forecast-" + f.eis() + "-" + f.finYear(),
                format,
                sink -> {
                    sink.header("Item", "Detail", "Amount");
                    sink.row("Member", f.eis() + " " + f.name(), "");
                    sink.row("Financial year", f.finYear() + " (" + f.view() + ")", "");
                    sink.row(
                            "Regime",
                            f.inputs().regime() == null ? "not chosen" : f.inputs().regime().name(),
                            "");
                    // SRS-7-27: a separated or retired member's year is shown as such.
                    sink.row("Member status", String.valueOf(f.employmentStatus()), "");
                    for (TaxableEarnings.Month m :
                            f.earnings()
                                    .map(TaxableEarnings.Year::months)
                                    .orElse(java.util.List.of())) {
                        for (TaxableEarnings.Line l : m.lines()) {
                            sink.row(
                                    "Earning " + m.yymm() + (m.actual() ? "" : " (projected)"),
                                    l.description(),
                                    l.amount());
                        }
                    }
                    // SRS-7-27, SRS-7-29: the employer's NPS contribution as income, with its
                    // matching exemption.
                    java.math.BigDecimal nps =
                            f.earnings()
                                    .map(TaxableEarnings.Year::employerNps)
                                    .orElse(java.math.BigDecimal.ZERO);
                    if (nps.signum() > 0) {
                        sink.row("Employer NPS contribution", "income", nps);
                        sink.row("Employer NPS contribution", "matching exemption (deducted)", nps);
                    }
                    for (TaxComputationService.IncomeView i : f.inputs().otherIncome()) {
                        sink.row("Other income", i.head(), i.amount());
                    }
                    for (TaxComputationService.DeductionView d : f.inputs().deductions()) {
                        sink.row(
                                "Deduction " + d.sectionCode(),
                                "claimed " + d.claimed().toPlainString(),
                                d.allowed());
                    }
                    if (f.inputs().transportRebate().signum() > 0) {
                        sink.row("Transport rebate", "", f.inputs().transportRebate());
                    }
                    if (f.result().isPresent()) {
                        var r = f.result().get();
                        sink.row("Taxable income", "", r.taxableIncome());
                        sink.row("Tax on slabs", "", r.slabTax());
                        sink.row("Surcharge", "", r.surcharge());
                        sink.row("Cess", "", r.cess());
                        sink.row(
                                "Annual tax",
                                r.higherRateApplied() ? "higher rate without a valid PAN" : "",
                                r.annualTax());
                        sink.row("Monthly TDS", "", r.monthlyTds());
                    }
                    for (String reason : f.reasons()) {
                        sink.row("Not computed", reason, "");
                    }
                });
    }
}
