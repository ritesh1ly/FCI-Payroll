package in.gov.fci.payroll.arrear.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.arrear.orchestration.WageRevisionBoardService;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.constraints.Positive;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * I-25 tiles and on-screen status report as Wireframes v6.1 draws them (OD-121; SRS-8-25 step 8):
 * the SRS names the report, not the API (PENDING API-13-3).
 *
 * <pre>
 * GET /v1/wage-revisions/summary         the four tiles                       I-25 VIEW
 * GET /v1/wage-revisions/{id}/offices    Revision Status Report, DO-wise      I-25 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class WageRevisionBoardController {

    private final CurrentAccess contexts;
    private final WageRevisionBoardService board;
    private final Paging paging;

    public WageRevisionBoardController(
            CurrentAccess contexts, WageRevisionBoardService board, Paging paging) {
        this.contexts = contexts;
        this.board = board;
        this.paging = paging;
    }

    @GetMapping("/v1/wage-revisions/summary")
    @PreAuthorize("@authz.gate('I-25','VIEW')")
    public ResponseEntity<WageRevisionBoardService.Board> summary() {
        return noStore(board.board(contexts.current()));
    }

    @GetMapping("/v1/wage-revisions/{id}/offices")
    @PreAuthorize("@authz.gate('I-25','VIEW')")
    public ResponseEntity<Paged<WageRevisionBoardService.OfficeRow>> offices(
            @PathVariable @Positive long id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(board.offices(contexts.current(), id, paging.resolve(page, size)));
    }
}
