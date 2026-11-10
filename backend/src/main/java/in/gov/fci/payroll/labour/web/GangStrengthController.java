package in.gov.fci.payroll.labour.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.labour.orchestration.GangStrengthService;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
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

/**
 * Gang strength (SRS-2-48 l.4403; OD-90; API-20-3: the SRS names the report and the alert, not the
 * API):
 *
 * <pre>
 * GET  /v1/labour/gangs/{id}/strength-report?month=YYYY-MM       the Gang Strength Report   M-09 VIEW
 * POST /v1/labour/gangs/{id}/shortfalls/{date}/closures {reason} the Depot Manager closes   M-09 APPROVE
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class GangStrengthController {

    /** The Depot Manager's reason for closing a shortfall alert. */
    public record CloseBody(@NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final GangStrengthService strengths;

    public GangStrengthController(CurrentAccess contexts, GangStrengthService strengths) {
        this.contexts = contexts;
        this.strengths = strengths;
    }

    @GetMapping("/v1/labour/gangs/{id}/strength-report")
    @PreAuthorize("@authz.gate('M-09','VIEW')")
    public ResponseEntity<GangStrengthService.Report> report(
            @PathVariable @Min(1) long id, @RequestParam YearMonth month) {
        return noStore(strengths.report(contexts.current(), id, month));
    }

    @PostMapping("/v1/labour/gangs/{id}/shortfalls/{date}/closures")
    @PreAuthorize("@authz.gate('M-09','APPROVE')")
    public ResponseEntity<Map<String, Integer>> close(
            @PathVariable @Min(1) long id,
            @PathVariable LocalDate date,
            @Valid @RequestBody CloseBody body) {
        return noStore(
                Map.of("closed", strengths.close(contexts.current(), id, date, body.reason())));
    }
}
