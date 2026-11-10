package in.gov.fci.payroll.cpf.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.cpf.orchestration.CpfScreenService;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read APIs of the CPF screens as Wireframes v6.1 draws them (OD-121; SRS-6-27, SRS-6-43,
 * SRS-6-48, SRS-6-49). The sources name the screens, not the API (PENDING API-07-13). Nothing here
 * writes: the writes of I-09 and I-22 stay on their controllers, those of I-12 and I-13 are not
 * built (PENDING 07h-blocked, 07k-blocked).
 *
 * <pre>
 * GET /v1/cpf/deputations/board?yymm=                 I-09 tiles            I-09 VIEW
 * GET /v1/cpf/deputations/members                     I-09 table            I-09 VIEW
 * GET /v1/cpf/opening-balance-corrections/board       I-22 tiles            I-22 VIEW
 * GET /v1/cpf/opening-balance-corrections/lines       I-22 table            I-22 VIEW
 * GET /v1/cpf/supplementary-payments                  I-12 entries          I-12 VIEW
 * GET /v1/cpf/parent-zone-changes                     I-13 table            I-13 VIEW
 * GET /v1/cpf/parent-zone-changes/{reference}         I-13 cards            I-13 VIEW
 * GET /v1/cpf/iocpf-pending                           IOCPF Pending List    I-13 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class CpfScreenController {

    private static final String YYMM = "^\\d{4}(0[1-9]|1[0-2])$";

    /** An HRMS reference as cpf_parent_zone_change holds it (VARCHAR2(50), R-ASCII). */
    private static final String HRMS_REFERENCE = "^[A-Za-z0-9/_-]{1,50}$";

    private final CurrentAccess contexts;
    private final CpfScreenService screens;
    private final Paging paging;

    public CpfScreenController(CurrentAccess contexts, CpfScreenService screens, Paging paging) {
        this.contexts = contexts;
        this.screens = screens;
        this.paging = paging;
    }

    @GetMapping("/v1/cpf/deputations/board")
    @PreAuthorize("@authz.gate('I-09','VIEW')")
    public ResponseEntity<CpfScreenService.DeputationBoard> deputationBoard(
            @RequestParam @Pattern(regexp = YYMM) String yymm) {
        return noStore(screens.deputationBoard(contexts.current(), yymm));
    }

    @GetMapping("/v1/cpf/deputations/members")
    @PreAuthorize("@authz.gate('I-09','VIEW')")
    public ResponseEntity<Paged<CpfScreenService.DeputedRow>> deputedMembers(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(screens.deputedMembers(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/cpf/opening-balance-corrections/board")
    @PreAuthorize("@authz.gate('I-22','VIEW')")
    public ResponseEntity<CpfScreenService.CorrectionBoard> correctionBoard() {
        return noStore(screens.correctionBoard(contexts.current()));
    }

    @GetMapping("/v1/cpf/opening-balance-corrections/lines")
    @PreAuthorize("@authz.gate('I-22','VIEW')")
    public ResponseEntity<Paged<CpfScreenService.CorrectionLine>> correctionLines(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(screens.correctionLines(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/cpf/supplementary-payments")
    @PreAuthorize("@authz.gate('I-12','VIEW')")
    public ResponseEntity<Paged<CpfScreenService.SupplementaryRow>> supplementaryPayments(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                screens.supplementaryPayments(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/cpf/parent-zone-changes")
    @PreAuthorize("@authz.gate('I-13','VIEW')")
    public ResponseEntity<Paged<CpfScreenService.ZoneChangeRow>> zoneChanges(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(screens.zoneChanges(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/cpf/parent-zone-changes/{reference}")
    @PreAuthorize("@authz.gate('I-13','VIEW')")
    public ResponseEntity<CpfScreenService.ZoneChangeDetail> zoneChange(
            @PathVariable @Pattern(regexp = HRMS_REFERENCE) String reference) {
        return noStore(screens.zoneChange(contexts.current(), reference));
    }

    @GetMapping("/v1/cpf/iocpf-pending")
    @PreAuthorize("@authz.gate('I-13','VIEW')")
    public ResponseEntity<Paged<CpfScreenService.IocpfRow>> iocpfPending(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(screens.iocpfPending(contexts.current(), paging.resolve(page, size)));
    }
}
