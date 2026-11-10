package in.gov.fci.payroll.payroll.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.payroll.orchestration.ComputationResults;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.YearMonth;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * C-02 Employee / Staff Payroll Computation, the read APIs of the screen as Wireframes v6.1 draws
 * it (OD-121; SRS l.3686-3692, FRS §7.4; API-16-5 in PENDING: the sources give no contract). Every
 * read is C-02 VIEW at the session's cycle unit; the export is C-02 EXPORT (SRS-G-13). The engine,
 * its gates and the undo are untouched: nothing here writes.
 *
 * <pre>
 * GET /v1/cycles/{period}/results-period             the period the results show
 * GET /v1/cycles/{period}/results[?filters]          Employee-wise Result, one page
 * GET /v1/cycles/{period}/results/summary[?filters]  tiles and the totals line
 * GET /v1/cycles/{period}/results/export[?filters]   the list as a file
 * GET /v1/cycles/{period}/results/{id}/trace         calculation trace of one member
 * GET /v1/cycles/{period}/pli-payments               PLI payments with their invoice status
 * </pre>
 */
@RestController
@RequestMapping("/v1/cycles/{period}")
@Profile("online")
@Validated
public class ComputationResultsController {

    private static final String PERIOD = "^\\d{4}-(0[1-9]|1[0-2])$";

    /** C-02 Pay Pattern (SRS l.3686): IDA, CDA (with FND, which follows it), or absent for both. */
    private static final String PATTERN = "^(IDA|CDA)$";

    /** The drawn Status filter: the groups of the M-16 list C02_RESULT_FILTER. */
    private static final String STATUS = "^(COMPUTED|EXCEPTION|HELD)$";

    /** List search: an EIS number or a name, printable ASCII (R-ASCII), no wildcard. */
    private static final String SEARCH = "^[A-Za-z0-9 .'\\-]{0,60}$";

    private final CurrentAccess contexts;
    private final ComputationResults results;
    private final ExportStreaming exports;
    private final Paging paging;

    public ComputationResultsController(
            CurrentAccess contexts,
            ComputationResults results,
            ExportStreaming exports,
            Paging paging) {
        this.contexts = contexts;
        this.results = results;
        this.exports = exports;
        this.paging = paging;
    }

    /**
     * The period shown: this one when it has a run, else the unit's latest with one; 204 if none.
     */
    @GetMapping("/results-period")
    @PreAuthorize("@authz.gate('C-02','VIEW')")
    public ResponseEntity<ComputationResults.ResultsPeriod> resultsPeriod(
            @PathVariable @Pattern(regexp = PERIOD) String period) {
        return results.resultsPeriod(contexts.current(), YearMonth.parse(period))
                .map(found -> noStore(found))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/results")
    @PreAuthorize("@authz.gate('C-02','VIEW')")
    public ResponseEntity<Paged<ComputationResults.ResultRow>> results(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @RequestParam(required = false) @Pattern(regexp = PATTERN) String payPattern,
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Pattern(regexp = SEARCH) String search,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                results.results(
                        contexts.current(),
                        YearMonth.parse(period),
                        new ComputationResults.Filter(payPattern, status, search),
                        paging.resolve(page, size)));
    }

    @GetMapping("/results/summary")
    @PreAuthorize("@authz.gate('C-02','VIEW')")
    public ResponseEntity<ComputationResults.Summary> summary(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @RequestParam(required = false) @Pattern(regexp = PATTERN) String payPattern,
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Pattern(regexp = SEARCH) String search) {
        return noStore(
                results.summary(
                        contexts.current(),
                        YearMonth.parse(period),
                        new ComputationResults.Filter(payPattern, status, search)));
    }

    /**
     * SRS-G-13: the filtered list; a list too large for one download is refused before any byte.
     */
    @GetMapping("/results/export")
    @PreAuthorize("@authz.gate('C-02','EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @RequestParam(required = false) @Pattern(regexp = PATTERN) String payPattern,
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Pattern(regexp = SEARCH) String search,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        YearMonth month = YearMonth.parse(period);
        ComputationResults.Filter filter =
                new ComputationResults.Filter(payPattern, status, search);
        exports.checkSize(results.exportSize(context, month, filter));
        return exports.stream(
                "computation-results",
                format,
                sink -> results.export(context, month, filter, sink));
    }

    @GetMapping("/results/{resultId}/trace")
    @PreAuthorize("@authz.gate('C-02','VIEW')")
    public ResponseEntity<ComputationResults.Trace> trace(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @PathVariable @Min(1) long resultId) {
        return noStore(results.trace(contexts.current(), YearMonth.parse(period), resultId));
    }

    @GetMapping("/pli-payments")
    @PreAuthorize("@authz.gate('C-02','VIEW')")
    public ResponseEntity<Paged<ComputationResults.PliPayment>> pliPayments(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                results.pliPayments(
                        contexts.current(), YearMonth.parse(period), paging.resolve(page, size)));
    }
}
