package in.gov.fci.payroll.eseva.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.eseva.orchestration.DocumentMails;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The status of the e-mailed member documents (OD-148; SRS-17-1, SRS-17-12). No drawn screen object
 * shows it (wireframe v6.1 frozen, OD-121; O-01 draws Email / Email batch disabled, WF-1540), so it
 * is read through the API only (WIREFRAME_GAPS WF-2002; the SRS names no API, PENDING API-14-3).
 *
 * <pre>
 * GET /v1/document-mails            one office's e-mails of a document and period   C-02 VIEW (payslip) / C-10 VIEW (Form 16) at the office
 * GET /v1/document-mails/channel    the channel: capacity, pause, outbox counts      X-03 VIEW
 * GET /v1/eseva/document-mails/mine the member's own e-mailed documents             E-01 SELF
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class DocumentMailController {

    private final CurrentAccess contexts;
    private final DocumentMails mails;
    private final Paging paging;

    public DocumentMailController(CurrentAccess contexts, DocumentMails mails, Paging paging) {
        this.contexts = contexts;
        this.mails = mails;
        this.paging = paging;
    }

    @GetMapping("/v1/document-mails")
    @PreAuthorize("@authz.gateEither('C-02','VIEW','C-10','VIEW')")
    public ResponseEntity<DocumentMails.UnitView> unit(
            @RequestParam @Pattern(regexp = "^(PAYSLIP|FORM16)$") String documentType,
            @RequestParam @Positive long orgUnitId,
            @RequestParam @Pattern(regexp = "^(\\d{6}|\\d{4}-\\d{2})$") String period,
            @RequestParam(required = false) @Pattern(regexp = "^(QUEUED|SENT|FAILED|SUPPRESSED)$")
                    String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                mails.unit(
                        contexts.current(),
                        documentType,
                        orgUnitId,
                        period,
                        status,
                        paging.resolve(page, size)));
    }

    @GetMapping("/v1/document-mails/channel")
    @PreAuthorize("@authz.gate('X-03','VIEW')")
    public ResponseEntity<DocumentMails.ChannelView> channel() {
        return noStore(mails.channel(contexts.current()));
    }

    @GetMapping("/v1/eseva/document-mails/mine")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<Paged<DocumentMails.Mine>> mine(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(mails.mine(contexts.current(), paging.resolve(page, size)));
    }
}
