package in.gov.fci.payroll.tax.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.tax.orchestration.TdsReviewService;
import jakarta.validation.constraints.Pattern;
import java.time.YearMonth;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The TDS cases flagged for the Payroll Checker's review of the cycle (FRS V0.5 §14.4 Step 4;
 * US-GAP-29), shown on C-01 Payroll Cycle Control. The SRS names the screen, not the API (PENDING
 * API-08-7).
 *
 * <pre>
 * GET /v1/tax/flagged-tds-cases?period=YYYY-MM&amp;page&amp;size   one page        C-01 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class TdsReviewController {

    private final CurrentAccess contexts;
    private final TdsReviewService service;
    private final Paging paging;

    public TdsReviewController(CurrentAccess contexts, TdsReviewService service, Paging paging) {
        this.contexts = contexts;
        this.service = service;
        this.paging = paging;
    }

    @GetMapping("/v1/tax/flagged-tds-cases")
    @PreAuthorize("@authz.gate('C-01','VIEW')")
    public ResponseEntity<Paged<TdsReviewService.FlaggedCase>> flagged(
            @RequestParam @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String period,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                service.flagged(
                        contexts.current(), YearMonth.parse(period), paging.resolve(page, size)));
    }
}
