package in.gov.fci.payroll.reporting.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.reporting.orchestration.InvoiceOutputsService;
import in.gov.fci.payroll.reporting.orchestration.OutputWindowsService;
import in.gov.fci.payroll.reporting.orchestration.ReportCatalogueService;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * O-03 Labour Registers &amp; Reports and O-06 Invoice Outputs, the read APIs of the screens as
 * Wireframes v6.1 draws them (OD-121; FRS §6.21, Annexure A.4, A.11; API-15-3 in PENDING: the
 * sources give no contract). Nothing here generates, releases or writes (PENDING 15-labour,
 * 15-invoices).
 *
 * <pre>
 * GET /v1/reports/labour-catalogue?annexure=&amp;engine=      O-03 VIEW
 * GET /v1/reports/cpf-eseva                               O-07 VIEW
 * GET /v1/reports/cpf-eseva/months?yymm=                  O-07 VIEW
 * GET /v1/reports/cpf-reconciliation                      O-08 VIEW
 * GET /v1/reports/invoices/overview?yymm=&amp;unit=           O-06 VIEW
 * GET /v1/reports/invoices/register?yymm=&amp;unit=&amp;type=      O-06 VIEW
 * GET /v1/reports/invoices/lines?yymm=&amp;unit=              O-06 VIEW
 * GET /v1/reports/bank-advice?yymm=                       O-02 VIEW
 * GET /v1/remittances/overview?yymm=                      O-11 VIEW
 * GET /v1/remittances/runs                                O-11 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class ReportScreensController {

    private static final String YYMM = "^\\d{4}(0[1-9]|1[0-2])$";

    /** The word of each labour payroll in applicable_payroll_types (report_definition). */
    private static final Map<String, String> ENGINE_WORD =
            Map.of("DEPT", "DEPARTMENTAL", "DPS", "DPS", "NWNP", "NWNP");

    private final CurrentAccess contexts;
    private final ReportCatalogueService catalogue;
    private final InvoiceOutputsService invoices;
    private final OutputWindowsService windows;
    private final Paging paging;

    public ReportScreensController(
            CurrentAccess contexts,
            ReportCatalogueService catalogue,
            InvoiceOutputsService invoices,
            OutputWindowsService windows,
            Paging paging) {
        this.contexts = contexts;
        this.catalogue = catalogue;
        this.invoices = invoices;
        this.windows = windows;
        this.paging = paging;
    }

    /** O-02: the month's disbursement summary, GL preview and invoice state (read-only). */
    @GetMapping("/v1/reports/bank-advice")
    @PreAuthorize("@authz.gate('O-02','VIEW')")
    public ResponseEntity<OutputWindowsService.BankOverview> bankAdvice(
            @RequestParam(required = false) @Pattern(regexp = YYMM) String yymm) {
        return noStore(windows.bank(contexts.current(), yymm));
    }

    /** O-11: the Monthly Remittance window (SRS-G-52, read-only). */
    @GetMapping("/v1/remittances/overview")
    @PreAuthorize("@authz.gate('O-11','VIEW')")
    public ResponseEntity<OutputWindowsService.RemittanceOverview> remittance(
            @RequestParam(required = false) @Pattern(regexp = YYMM) String yymm) {
        return noStore(windows.remittance(contexts.current(), yymm));
    }

    @GetMapping("/v1/remittances/runs")
    @PreAuthorize("@authz.gate('O-11','VIEW')")
    public ResponseEntity<Paged<OutputWindowsService.RunRow>> runs(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(windows.runs(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/reports/labour-catalogue")
    @PreAuthorize("@authz.gate('O-03','VIEW')")
    public ResponseEntity<Paged<ReportCatalogueService.Entry>> labourCatalogue(
            @RequestParam(defaultValue = "false") boolean annexure,
            @RequestParam(defaultValue = "DEPT") @Pattern(regexp = "^(DEPT|DPS|NWNP)$")
                    String engine,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                catalogue.labour(
                        contexts.current(),
                        annexure,
                        ENGINE_WORD.get(engine),
                        paging.resolve(page, size)));
    }

    /** O-07 CPF &amp; e-Seva Reports: the family in the drawn order (PENDING API-07-13). */
    @GetMapping("/v1/reports/cpf-eseva")
    @PreAuthorize("@authz.gate('O-07','VIEW')")
    public ResponseEntity<Paged<ReportCatalogueService.Entry>> cpfEseva(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(catalogue.cpf(contexts.current(), "O-07", paging.resolve(page, size)));
    }

    /** O-07: Provisional or Final, the month and the month before (BR-CPF-036). */
    @GetMapping("/v1/reports/cpf-eseva/months")
    @PreAuthorize("@authz.gate('O-07','VIEW')")
    public ResponseEntity<ReportCatalogueService.CpfMonths> cpfMonths(
            @RequestParam @Pattern(regexp = YYMM) String yymm) {
        return noStore(catalogue.cpfMonths(contexts.current(), yymm));
    }

    /** O-08 CPF Reconciliation &amp; OBCB Reports: the set of the SRS table. */
    @GetMapping("/v1/reports/cpf-reconciliation")
    @PreAuthorize("@authz.gate('O-08','VIEW')")
    public ResponseEntity<Paged<ReportCatalogueService.Entry>> cpfReconciliation(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(catalogue.cpf(contexts.current(), "O-08", paging.resolve(page, size)));
    }

    @GetMapping("/v1/reports/invoices/overview")
    @PreAuthorize("@authz.gate('O-06','VIEW')")
    public ResponseEntity<InvoiceOutputsService.Overview> invoiceOverview(
            @RequestParam(required = false) @Pattern(regexp = YYMM) String yymm,
            @RequestParam(required = false) Long unit) {
        return noStore(invoices.overview(contexts.current(), yymm, unit));
    }

    @GetMapping("/v1/reports/invoices/register")
    @PreAuthorize("@authz.gate('O-06','VIEW')")
    public ResponseEntity<Paged<InvoiceOutputsService.RegisterRow>> invoiceRegister(
            @RequestParam @Pattern(regexp = YYMM) String yymm,
            @RequestParam(required = false) Long unit,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z_]{1,60}$") String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                invoices.register(
                        contexts.current(), yymm, unit, type, paging.resolve(page, size)));
    }

    @GetMapping("/v1/reports/invoices/lines")
    @PreAuthorize("@authz.gate('O-06','VIEW')")
    public ResponseEntity<Paged<InvoiceOutputsService.InvoiceLine>> invoiceLines(
            @RequestParam @Pattern(regexp = YYMM) String yymm,
            @RequestParam(required = false) Long unit,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(invoices.lines(contexts.current(), yymm, unit, paging.resolve(page, size)));
    }
}
