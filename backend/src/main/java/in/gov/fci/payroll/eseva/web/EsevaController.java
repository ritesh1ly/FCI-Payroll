package in.gov.fci.payroll.eseva.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.eseva.orchestration.MemberNotifications;
import in.gov.fci.payroll.eseva.orchestration.MemberOverviewService;
import in.gov.fci.payroll.eseva.orchestration.PrivacyNoticeService;
import in.gov.fci.payroll.eseva.orchestration.SubmissionLogService;
import in.gov.fci.payroll.eseva.orchestration.SupportPreviewService;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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
 * eSeva (plan 14; SRS-17-6, SRS-17-8, SRS-17-18, SRS-SEC-9). The SRS names the screens, not the API
 * (PENDING API-14-1).
 *
 * <pre>
 * GET  /v1/eseva/submissions                         the Submission Log, paged       E-02 VIEW
 * POST /v1/eseva/support-previews                    one recorded support preview    E-02 SUPPORT or E-01 VIEW (staff)
 * POST /v1/eseva/support-previews/{id}/closures       the preview closed (FRS l.6958) E-02 SUPPORT or E-01 VIEW (staff)
 * GET  /v1/eseva/support-previews/{id}/overview       the member's overview (preview) E-01 VIEW
 * GET  /v1/eseva/overview/mine                        the member's overview           E-01 SELF
 * GET  /v1/eseva/privacy-notice/mine                 the member's notice             E-01 SELF
 * POST /v1/eseva/privacy-notice/mine/acknowledgements  the member acknowledges     E-01 SELF
 * GET  /v1/eseva/notifications/mine              the member's notices, paged     E-01 SELF
 * POST /v1/eseva/notifications/mine/{id}/reads   a notice read                   E-01 SELF
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class EsevaController {

    /** A support preview: the member's employee number and the reason (SRS-17-8). */
    public record PreviewBody(
            @NotBlank @Size(max = 20) @PrintableAscii String eis,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final SubmissionLogService log;
    private final SupportPreviewService previews;
    private final PrivacyNoticeService notices;
    private final MemberNotifications notifications;
    private final MemberOverviewService overview;
    private final Paging paging;

    public EsevaController(
            CurrentAccess contexts,
            SubmissionLogService log,
            SupportPreviewService previews,
            PrivacyNoticeService notices,
            MemberNotifications notifications,
            MemberOverviewService overview,
            Paging paging) {
        this.contexts = contexts;
        this.log = log;
        this.previews = previews;
        this.notices = notices;
        this.notifications = notifications;
        this.overview = overview;
        this.paging = paging;
    }

    @GetMapping("/v1/eseva/submissions")
    @PreAuthorize("@authz.gate('E-02','VIEW')")
    public ResponseEntity<Paged<SubmissionLogService.Row>> submissions(
            @RequestParam(required = false) @Size(max = 20) String eis,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z_]{1,30}$") String type,
            @RequestParam(required = false)
                    @Pattern(regexp = "^(SUBMITTED|ROUTED|ACTIONED|REJECTED)$")
                    String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                log.page(
                        contexts.current(),
                        new SubmissionLogService.Filter(eis, type, status),
                        paging.resolve(page, size)));
    }

    /** FRS V0.5 l.6958 (US-GAP-261): the staff user closes the support preview. */
    @PostMapping("/v1/eseva/support-previews/{id}/closures")
    @PreAuthorize("@authz.gateEither('E-02','SUPPORT','E-01','VIEW')")
    public ResponseEntity<Void> closePreview(@PathVariable @Positive long id) {
        previews.close(contexts.current(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/eseva/support-previews")
    @PreAuthorize("@authz.gateEither('E-02','SUPPORT','E-01','VIEW')")
    public ResponseEntity<SupportPreviewService.Preview> preview(
            @Valid @RequestBody PreviewBody body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(previews.preview(contexts.current(), body.eis(), body.reason()));
    }

    /** SRS-17-6, l.496: the overview of the member of the user's own open support session. */
    @GetMapping("/v1/eseva/support-previews/{id}/overview")
    @PreAuthorize("@authz.gate('E-01','VIEW')")
    public ResponseEntity<MemberOverviewService.Overview> previewOverview(
            @PathVariable @Positive long id) {
        return noStore(overview.ofPreview(contexts.current(), id));
    }

    /** SRS-17-1, SRS-17-17: the signed-in member's own overview. */
    @GetMapping("/v1/eseva/overview/mine")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<MemberOverviewService.Overview> ownOverview() {
        return noStore(overview.mine(contexts.current()));
    }

    @GetMapping("/v1/eseva/privacy-notice/mine")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<PrivacyNoticeService.Notice> notice() {
        return noStore(notices.mine(contexts.current()));
    }

    @PostMapping("/v1/eseva/privacy-notice/mine/acknowledgements")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<PrivacyNoticeService.Notice> acknowledge() {
        return noStore(notices.acknowledge(contexts.current()));
    }

    @GetMapping("/v1/eseva/notifications/mine")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<Paged<MemberNotifications.Notice>> notifications(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(notifications.mine(contexts.current(), paging.resolve(page, size)));
    }

    @PostMapping("/v1/eseva/notifications/mine/{id}/reads")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<Void> read(@PathVariable @Min(1) long id) {
        notifications.read(contexts.current(), id);
        return ResponseEntity.noContent().build();
    }
}
