package in.gov.fci.payroll.settlement.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.settlement.orchestration.SettlementApprovals;
import in.gov.fci.payroll.settlement.orchestration.SettlementOrders;
import in.gov.fci.payroll.settlement.orchestration.SettlementStatementPdf;
import in.gov.fci.payroll.settlement.orchestration.SettlementViews;
import in.gov.fci.payroll.settlement.orchestration.SettlementWork;
import in.gov.fci.payroll.workflow.api.Submitted;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * O-05 Full &amp; Final Settlement (plan 11; SRS-10-1 … 10-32). A case is opened by the approved
 * separation, never through this API (SRS-10-19). The SRS names the screen, not the API (PENDING
 * API-11-1).
 *
 * <pre>
 * GET  /v1/settlement-cases                         the cases of the offices (paged)       O-05 VIEW
 * GET  /v1/settlement-cases/awaiting                cases awaiting each clearance           O-05 VIEW
 * GET  /v1/settlement-cases/summary                 the four tiles of the offices           O-05 VIEW
 * GET  /v1/settlement-cases/invoices                the settlement payments (paged)         O-05 VIEW
 * GET  /v1/settlement-cases/{id}                    one case                                O-05 VIEW
 * GET  /v1/settlement-cases/{id}/orders             the order copies of a case              O-05 VIEW
 * GET  /v1/settlement-cases/{id}/files/{fileId}     one order copy                          O-05 VIEW
 * POST /v1/settlement-cases/{id}/orders             attach an order copy (API-11-2)         O-05 COMPUTE
 * POST /v1/settlement-cases/{id}/no-dues            establishment no-dues                   O-05 CLEAR
 * POST /v1/settlement-cases/{id}/computations       compute / recompute                     O-05 COMPUTE
 * PUT  /v1/settlement-cases/{id}/recoveries/{r}/source  payroll dues or gratuity (+ order)  O-05 COMPUTE
 * POST /v1/settlement-cases/{id}/ex-gratia          ex-gratia with its letter (202)         O-05 COMPUTE
 * POST /v1/settlement-cases/{id}/exclusions         the Checker excludes a clearance        O-05 APPROVE
 * POST /v1/settlement-cases/{id}/submissions        submit for approval (202)               O-05 COMPUTE
 * POST /v1/settlement-cases/{id}/releases           initiate the release (202)              O-05 RELEASE
 * GET  /v1/settlement-cases/{id}/statement          the settlement statement as PDF         O-05 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class SettlementController {

    private static final String EIS = "^[A-Za-z0-9-]{1,20}$";
    private static final String TYPE = "^[A-Z_]{1,40}$";

    public record NoDuesBody(
            @NotNull @Digits(integer = 12, fraction = 2) BigDecimal dues,
            @NotNull @Size(max = 1000) @PrintableAscii String remark) {}

    public record ExclusionBody(
            @NotNull @Pattern(regexp = TYPE) String clearanceType,
            @NotNull @Size(max = 500) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final SettlementViews views;
    private final SettlementWork work;
    private final SettlementApprovals approvals;
    private final SettlementOrders orders;
    private final Paging paging;
    private final in.gov.fci.payroll.platform.web.ExportStreaming downloads;
    private final in.gov.fci.payroll.platform.export.PdfRenderGate gate;

    public SettlementController(
            CurrentAccess contexts,
            SettlementViews views,
            SettlementWork work,
            SettlementApprovals approvals,
            SettlementOrders orders,
            Paging paging,
            in.gov.fci.payroll.platform.web.ExportStreaming downloads,
            in.gov.fci.payroll.platform.export.PdfRenderGate gate) {
        this.contexts = contexts;
        this.views = views;
        this.work = work;
        this.approvals = approvals;
        this.orders = orders;
        this.paging = paging;
        this.downloads = downloads;
        this.gate = gate;
    }

    @GetMapping("/v1/settlement-cases")
    @PreAuthorize("@authz.gate('O-05','VIEW')")
    public ResponseEntity<Paged<SettlementViews.CaseRow>> cases(
            @RequestParam(required = false) Integer stage,
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                views.cases(
                        contexts.current(),
                        new SettlementViews.Filter(stage, eis),
                        paging.resolve(page, size)));
    }

    @GetMapping("/v1/settlement-cases/awaiting")
    @PreAuthorize("@authz.gate('O-05','VIEW')")
    public ResponseEntity<List<SettlementViews.Awaiting>> awaiting() {
        return noStore(views.awaitingCounts(contexts.current()));
    }

    /** O-05 tiles (pack l.5821-5825; SRS-10-6, SRS-10-11, SRS-10-22). */
    @GetMapping("/v1/settlement-cases/summary")
    @PreAuthorize("@authz.gate('O-05','VIEW')")
    public ResponseEntity<SettlementViews.Summary> summary() {
        return noStore(views.summary(contexts.current()));
    }

    /** SRS-G-37, SRS-10-31: the settlement payments with their invoice. */
    @GetMapping("/v1/settlement-cases/invoices")
    @PreAuthorize("@authz.gate('O-05','VIEW')")
    public ResponseEntity<Paged<SettlementViews.InvoiceRow>> invoices(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(views.invoices(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/settlement-cases/{id}/orders")
    @PreAuthorize("@authz.gate('O-05','VIEW')")
    public ResponseEntity<List<DocumentStore.AttachmentView>> orders(@PathVariable long id) {
        return noStore(orders.orders(contexts.current(), id));
    }

    /** Downloads the order; served as an attachment so the browser never renders it inline. */
    @GetMapping("/v1/settlement-cases/{id}/files/{fileId}")
    @PreAuthorize("@authz.gate('O-05','VIEW')")
    public ResponseEntity<byte[]> file(
            @PathVariable long id, @PathVariable long fileId, HttpServletRequest request) {
        DocumentStore.Content content =
                orders.file(contexts.current(), request.getRemoteAddr(), id, fileId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(content.attachment().fileName())
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(content.attachment().contentType()))
                .body(content.bytes());
    }

    /** SRS-G-30, SRS-G-32: the sanction letter or succession document stored with the case. */
    @PostMapping(
            value = "/v1/settlement-cases/{id}/orders",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('O-05','COMPUTE')")
    public ResponseEntity<DocumentStore.AttachmentView> attach(
            @PathVariable long id,
            @RequestParam @Pattern(regexp = TYPE) String documentType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String reference,
            @RequestParam LocalDate orderDate,
            @RequestParam(required = false) @Size(max = 200) @PrintableAscii
                    String issuingAuthority,
            @RequestPart MultipartFile file)
            throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        orders.attach(
                                contexts.current(),
                                id,
                                documentType,
                                reference,
                                orderDate,
                                issuingAuthority,
                                file.getOriginalFilename(),
                                file.getBytes()));
    }

    @GetMapping("/v1/settlement-cases/{id}")
    @PreAuthorize("@authz.gate('O-05','VIEW')")
    public ResponseEntity<SettlementViews.CaseDetail> detail(@PathVariable long id) {
        return noStore(views.detail(contexts.current(), id));
    }

    /**
     * OD-144 (LIB-1), SRS-10-12: the case's settlement statement as PDF, from the same read as the
     * detail (a case outside the session's offices is not found).
     */
    @GetMapping("/v1/settlement-cases/{id}/statement")
    @PreAuthorize("@authz.gate('O-05','VIEW')")
    public ResponseEntity<byte[]> statement(@PathVariable long id) {
        SettlementViews.CaseDetail d = views.detail(contexts.current(), id);
        return downloads.document(
                "settlement-statement-"
                        + d.summary().caseReference().replaceAll("[^A-Za-z0-9]", "-"),
                gate.render("settlement-statement", () -> SettlementStatementPdf.of(d)),
                1);
    }

    @PostMapping("/v1/settlement-cases/{id}/no-dues")
    @PreAuthorize("@authz.gate('O-05','CLEAR')")
    public ResponseEntity<SettlementViews.CaseDetail> noDues(
            @PathVariable long id, @Valid @RequestBody NoDuesBody body) {
        work.establishmentNoDues(contexts.current(), id, body.dues(), body.remark());
        return noStore(views.detail(contexts.current(), id));
    }

    @PostMapping("/v1/settlement-cases/{id}/computations")
    @PreAuthorize("@authz.gate('O-05','COMPUTE')")
    public ResponseEntity<SettlementViews.CaseDetail> compute(@PathVariable long id) {
        work.compute(contexts.current(), id);
        return noStore(views.detail(contexts.current(), id));
    }

    @PutMapping(
            value = "/v1/settlement-cases/{id}/recoveries/{recoveryId}/source",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('O-05','COMPUTE')")
    public ResponseEntity<SettlementViews.CaseDetail> source(
            @PathVariable long id,
            @PathVariable long recoveryId,
            @RequestParam @Pattern(regexp = "^(PAYROLL_DUES|GRATUITY)$") String source,
            @RequestParam(required = false) @Size(max = 500) @PrintableAscii String reason,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String orderRef,
            @RequestParam(required = false) LocalDate orderDate,
            @RequestParam(required = false) @Size(max = 200) @PrintableAscii
                    String issuingAuthority,
            @RequestPart(required = false) MultipartFile order)
            throws IOException {
        approvals.source(
                contexts.current(),
                id,
                recoveryId,
                source,
                reason,
                orderRef,
                orderDate,
                issuingAuthority,
                order == null ? null : order.getOriginalFilename(),
                order == null ? null : order.getBytes());
        return noStore(views.detail(contexts.current(), id));
    }

    @PostMapping(
            value = "/v1/settlement-cases/{id}/ex-gratia",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('O-05','COMPUTE')")
    public ResponseEntity<Submitted> exGratia(
            @PathVariable long id,
            @RequestParam @Size(max = 60) @PrintableAscii String letterNo,
            @RequestParam LocalDate letterDate,
            @RequestParam @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @RequestParam @Size(max = 200) @PrintableAscii String issuingAuthority,
            @RequestPart MultipartFile letter)
            throws IOException {
        return accepted(
                approvals.exGratia(
                        contexts.current(),
                        id,
                        letterNo,
                        letterDate,
                        amount,
                        issuingAuthority,
                        letter.getOriginalFilename(),
                        letter.getBytes()));
    }

    /** SRS-10-14 (US-GAP-219): the Payroll Checker records the named, reasoned exclusion. */
    @PostMapping("/v1/settlement-cases/{id}/exclusions")
    @PreAuthorize("@authz.gate('O-05','APPROVE')")
    public ResponseEntity<SettlementViews.CaseDetail> exclude(
            @PathVariable long id, @Valid @RequestBody ExclusionBody body) {
        approvals.exclude(contexts.current(), id, body.clearanceType(), body.reason());
        return noStore(views.detail(contexts.current(), id));
    }

    @PostMapping("/v1/settlement-cases/{id}/submissions")
    @PreAuthorize("@authz.gate('O-05','COMPUTE')")
    public ResponseEntity<Submitted> submit(@PathVariable long id) {
        return accepted(approvals.submit(contexts.current(), id));
    }

    @PostMapping("/v1/settlement-cases/{id}/releases")
    @PreAuthorize("@authz.gate('O-05','RELEASE')")
    public ResponseEntity<Submitted> release(@PathVariable long id) {
        return accepted(approvals.release(contexts.current(), id));
    }

    private static ResponseEntity<Submitted> accepted(SubmittedApproval item) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new Submitted(item.id(), item.status().name()));
    }
}
