package in.gov.fci.payroll.payroll.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.payroll.orchestration.PayslipPdfService;
import in.gov.fci.payroll.payroll.orchestration.PayslipRegisterService;
import in.gov.fci.payroll.payroll.orchestration.PayslipService;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.platform.web.Responses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member's own staff payslips in eSeva (plan 16 phase 16e-3; SRS-1-10, SRS-17-1, SRS-17-7). The
 * SRS names the screen, not the API (PENDING API-16-1).
 *
 * <pre>
 * GET  /v1/payslips/mine                      the member's locked months, paged  E-01 SELF
 * GET  /v1/payslips/mine/{yymm}               one locked month's payslip         E-01 SELF
 * GET  /v1/payslips/previews/{session}/{yymm} a month in the support preview     E-01 VIEW
 * GET  /v1/payslips/periods                   the O-01 periods                   O-01 VIEW
 * GET  /v1/payslips/summary                   the O-01 tiles                     O-01 VIEW
 * GET  /v1/payslips/register                  the O-01 register, paged           O-01 VIEW
 * GET  /v1/payslips/members/{eis}/{yymm}      one committed month in scope       O-01 VIEW
 * POST /v1/payslip-runs                       bulk generation of a locked month  O-01 GENERATE
 * GET  /v1/payslips/mine/{yymm}/pdf           the own payslip as PDF             E-01 SELF
 * GET  /v1/payslips/previews/{session}/{yymm}/pdf  the preview month as PDF      E-01 VIEW
 * GET  /v1/payslips/members/{eis}/{yymm}/pdf  one member's payslip as PDF        O-01 VIEW
 * GET  /v1/payslips/mine/zip?months=a,b      own locked months, one ZIP         E-01 SELF
 * GET  /v1/payslips/mine/bulk-settings        the most months of one ZIP         E-01 SELF
 * POST /v1/payslip-bulk-files                 queue the O-01 Bulk PDF job (202)  O-01 VIEW
 * GET  /v1/payslip-bulk-files?yymm&level      the user's newest bulk file        O-01 VIEW
 * GET  /v1/payslip-bulk-files/{id}/file       its ZIP, to its requester only     O-01 VIEW
 * </pre>
 *
 * <p>The PDFs (OD-144, LIB-1) read through the same services as the JSON reads, so each keeps its
 * screen's access and scope rule; every download is logged (SRS-20-24, SRS-22-29).
 */
@RestController
@Validated
@Profile("online")
public class PayslipController {

    private static final String YYMM = "^\\d{4}(0[1-9]|1[0-2])$";

    /** O-01 bulk generation: the month and the drawn level. */
    public record RunBody(
            @NotNull @Pattern(regexp = YYMM) String yymm, PayslipRegisterService.Level level) {}

    private final CurrentAccess contexts;
    private final PayslipService payslips;
    private final PayslipRegisterService register;
    private final Paging paging;
    private final PayslipPdfService pdfs;
    private final ExportStreaming downloads;

    public PayslipController(
            CurrentAccess contexts,
            PayslipService payslips,
            PayslipRegisterService register,
            Paging paging,
            PayslipPdfService pdfs,
            ExportStreaming downloads) {
        this.contexts = contexts;
        this.payslips = payslips;
        this.register = register;
        this.paging = paging;
        this.pdfs = pdfs;
        this.downloads = downloads;
    }

    /** OD-144: the member's own locked month as PDF (SRS-1-10, SRS-17-7). */
    @GetMapping("/v1/payslips/mine/{yymm}/pdf")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<byte[]> payslipPdf(
            @PathVariable @Pattern(regexp = "^\\d{6}$") String yymm) {
        return downloads.document("payslip-" + yymm, pdfs.mine(contexts.current(), yymm), 1);
    }

    /** OD-144, SRS-17-6: the support preview's month as PDF. */
    @GetMapping("/v1/payslips/previews/{session}/{yymm}/pdf")
    @PreAuthorize("@authz.gate('E-01','VIEW')")
    public ResponseEntity<byte[]> previewPdf(
            @PathVariable @Min(1) long session,
            @PathVariable @Pattern(regexp = "^\\d{6}$") String yymm) {
        return downloads.document(
                "payslip-" + yymm, pdfs.ofPreview(contexts.current(), session, yymm), 1);
    }

    /** OD-144, O-01 PDF / row PDF: one member's committed month in scope. */
    @GetMapping("/v1/payslips/members/{eis}/{yymm}/pdf")
    @PreAuthorize("@authz.gate('O-01','VIEW')")
    public ResponseEntity<byte[]> memberPdf(
            @PathVariable @Size(max = 20) String eis,
            @PathVariable @Pattern(regexp = YYMM) String yymm) {
        return downloads.document(
                "payslip-" + eis.replaceAll("[^A-Za-z0-9]", "-") + "-" + yymm,
                pdfs.ofStaff(contexts.current(), eis, yymm),
                1);
    }

    // ---------------------------------------------------------------- bulk (OD-149)

    /** O-01 Bulk PDF: the month and the level. */
    public record BulkBody(
            @NotNull @Pattern(regexp = YYMM) String yymm, PayslipRegisterService.Level level) {}

    /** E-01: the most months one bulk download carries (null: not configured). */
    public record BulkSettings(Integer maxPeriods) {}

    /** OD-149: what the E-01 month picker offers (the setting is read, never assumed). */
    @GetMapping("/v1/payslips/mine/bulk-settings")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<BulkSettings> bulkSettings() {
        return noStore(new BulkSettings(pdfs.memberBulkMax().orElse(null)));
    }

    /**
     * OD-149 (owner 2026-11-09: "bulk download will be zipped, single month PDF"): the months the
     * member chose ({@code months=202601,202603}) as one ZIP of single-month PDFs; one month
     * answers that month's PDF. Checked before any byte is sent.
     */
    @GetMapping("/v1/payslips/mine/zip")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<
                    org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            mineZip(@RequestParam @Pattern(regexp = "^\\d{6}(,\\d{6}){0,119}$") String months) {
        in.gov.fci.payroll.platform.security.AccessContext context = contexts.current();
        List<String> chosen = pdfs.memberMonths(context, List.of(months.split(",")));
        if (chosen.size() == 1) {
            String yymm = chosen.getFirst();
            // One month is its plain PDF (owner: "single month PDF"), with the single download's
            // headers; the body type is the streamed one this endpoint declares.
            ResponseEntity<byte[]> pdf =
                    downloads.document("payslip-" + yymm, pdfs.mine(context, yymm), 1);
            byte[] bytes = pdf.getBody() == null ? new byte[0] : pdf.getBody();
            return ResponseEntity.status(pdf.getStatusCode())
                    .headers(pdf.getHeaders())
                    .body(out -> out.write(bytes));
        }
        return downloads.streamZip(
                "payslips-" + chosen.getFirst() + "-" + chosen.getLast(),
                PayslipPdfService.zipTime(chosen),
                zip -> pdfs.writeMemberZip(context, chosen, zip));
    }

    /** SRS-NF-2, OD-149: queues the O-01 Bulk PDF of the user's unit (202 with the request). */
    @PostMapping("/v1/payslip-bulk-files")
    @PreAuthorize("@authz.gate('O-01','VIEW')")
    public ResponseEntity<PayslipPdfService.BulkFile> requestBulk(
            @Valid @RequestBody BulkBody body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(pdfs.requestBulk(contexts.current(), body.yymm(), body.level()));
    }

    /** The user's newest O-01 bulk file of the month and level (204 when none was asked for). */
    @GetMapping("/v1/payslip-bulk-files")
    @PreAuthorize("@authz.gate('O-01','VIEW')")
    public ResponseEntity<PayslipPdfService.BulkFile> bulkStatus(
            @RequestParam @Pattern(regexp = YYMM) String yymm,
            @RequestParam(required = false) PayslipRegisterService.Level level) {
        return pdfs.bulkStatus(contexts.current(), yymm, level)
                .map(Responses::noStore)
                .orElseGet(
                        () ->
                                ResponseEntity.noContent()
                                        .cacheControl(CacheControl.noStore())
                                        .build());
    }

    /** The ZIP of the user's own finished O-01 bulk job. */
    @GetMapping("/v1/payslip-bulk-files/{id}/file")
    @PreAuthorize("@authz.gate('O-01','VIEW')")
    public ResponseEntity<byte[]> bulkFile(
            @PathVariable @Min(1) long id, jakarta.servlet.http.HttpServletRequest request) {
        PayslipPdfService.Download file =
                pdfs.bulkFile(contexts.current(), request.getRemoteAddr(), id);
        return downloads.archive(file.name(), file.zip(), file.files());
    }

    @GetMapping("/v1/payslips/mine")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<Paged<PayslipService.MonthRow>> months(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(payslips.months(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/payslips/mine/{yymm}")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<PayslipService.Payslip> payslip(
            @PathVariable @Pattern(regexp = "^\\d{6}$") String yymm) {
        return noStore(payslips.payslip(contexts.current(), yymm));
    }

    /** SRS-17-6: a locked month of the member of the user's open support preview. */
    @GetMapping("/v1/payslips/previews/{session}/{yymm}")
    @PreAuthorize("@authz.gate('E-01','VIEW')")
    public ResponseEntity<PayslipService.Payslip> preview(
            @PathVariable @Min(1) long session,
            @PathVariable @Pattern(regexp = "^\\d{6}$") String yymm) {
        return noStore(payslips.ofPreview(contexts.current(), session, yymm));
    }

    @GetMapping("/v1/payslips/periods")
    @PreAuthorize("@authz.gate('O-01','VIEW')")
    public ResponseEntity<List<PayslipRegisterService.Period>> periods() {
        return noStore(register.periods(contexts.current()));
    }

    @GetMapping("/v1/payslips/summary")
    @PreAuthorize("@authz.gate('O-01','VIEW')")
    public ResponseEntity<PayslipRegisterService.Summary> summary(
            @RequestParam @Pattern(regexp = YYMM) String yymm,
            @RequestParam(required = false) PayslipRegisterService.Level level) {
        return noStore(register.summary(contexts.current(), yymm, level));
    }

    @GetMapping("/v1/payslips/register")
    @PreAuthorize("@authz.gate('O-01','VIEW')")
    public ResponseEntity<Paged<PayslipRegisterService.Row>> register(
            @RequestParam @Pattern(regexp = YYMM) String yymm,
            @RequestParam(required = false) PayslipRegisterService.Level level,
            @RequestParam(required = false) @Size(max = 60) String text,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                register.register(
                        contexts.current(), yymm, level, text, paging.resolve(page, size)));
    }

    @GetMapping("/v1/payslips/members/{eis}/{yymm}")
    @PreAuthorize("@authz.gate('O-01','VIEW')")
    public ResponseEntity<PayslipService.Payslip> member(
            @PathVariable @Size(max = 20) String eis,
            @PathVariable @Pattern(regexp = YYMM) String yymm) {
        return noStore(payslips.ofStaff(contexts.current(), eis, yymm));
    }

    /** SRS l.3381: the Payroll Maker generates a locked period's payslips (201). */
    @PostMapping("/v1/payslip-runs")
    @PreAuthorize("@authz.gate('O-01','GENERATE')")
    public ResponseEntity<PayslipRegisterService.Generated> generate(
            @Valid @RequestBody RunBody body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(register.generate(contexts.current(), body.yymm(), body.level()));
    }
}
