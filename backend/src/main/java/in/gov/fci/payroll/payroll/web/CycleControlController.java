package in.gov.fci.payroll.payroll.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.payroll.orchestration.CycleGateService;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.YearMonth;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-01 Payroll Cycle Control of the session's cycle unit (plan 05 phase 05b): gates G0 - G6,
 * pre-lock checks and the G6 quarantine queue. Fixed gates per endpoint; the service checks the
 * permission again at the unit, the state and the separation of duties.
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/cycles/{period}")
public class CycleControlController {

    private static final String PERIOD = "^\\d{4}-(0[1-9]|1[0-2])$";

    public record ReasonRequest(@NotBlank @Size(max = 1000) @PrintableAscii String reason) {}

    /**
     * A gate acceptance: the reason and, for G5, the EIS numbers of the members legitimately
     * excluded (SRS l.1524; BMAD TR-010). {@code members} is optional for the other gates.
     */
    public record GateAcceptRequest(
            @NotBlank @Size(max = 1000) @PrintableAscii String reason,
            @Size(max = 100)
                    java.util.List<@Pattern(regexp = "^[A-Za-z0-9-]{1,20}$") String> members) {}

    public record ReviewRequest(
            boolean accepted, @NotBlank @Size(max = 1000) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final CycleGateService gates;
    private final Paging paging;

    public CycleControlController(CurrentAccess contexts, CycleGateService gates, Paging paging) {
        this.paging = paging;
        this.contexts = contexts;
        this.gates = gates;
    }

    /** Evaluates and returns the gates and checks (each evaluation is recorded). */
    @GetMapping("/gates")
    @PreAuthorize("@authz.gate('C-01','VIEW')")
    public ResponseEntity<CycleGateService.GatesView> gates(
            @PathVariable @Pattern(regexp = PERIOD) String period) {
        return noStore(gates.gates(contexts.current(), YearMonth.parse(period)));
    }

    @PostMapping("/gates/{gate}/accept")
    @PreAuthorize("@authz.gate('C-01','APPROVE')")
    public ResponseEntity<CycleGateService.GatesView> accept(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @PathVariable @Pattern(regexp = "^G[0-6]$") String gate,
            @Valid @RequestBody GateAcceptRequest r) {
        return noStore(
                gates.accept(
                        contexts.current(),
                        YearMonth.parse(period),
                        gate,
                        r.reason(),
                        r.members()));
    }

    @PostMapping("/checks/{checkId}/acknowledge")
    @PreAuthorize("@authz.gate('C-01','APPROVE')")
    public ResponseEntity<CycleGateService.GatesView> acknowledge(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @PathVariable @Positive Long checkId,
            @Valid @RequestBody ReasonRequest r) {
        return noStore(
                gates.acknowledge(
                        contexts.current(), YearMonth.parse(period), checkId, r.reason()));
    }

    @GetMapping("/quarantine")
    @PreAuthorize("@authz.gate('C-01','VIEW')")
    public ResponseEntity<Paged<CycleGateService.QuarantineView>> quarantine(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                gates.quarantine(
                        contexts.current(), YearMonth.parse(period), paging.resolve(page, size)));
    }

    @PostMapping("/quarantine/{id}/review")
    @PreAuthorize("@authz.gate('C-01','APPROVE')")
    public ResponseEntity<CycleGateService.GatesView> review(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @PathVariable @Positive Long id,
            @Valid @RequestBody ReviewRequest r) {
        return noStore(
                gates.reviewMember(
                        contexts.current(), YearMonth.parse(period), id, r.accepted(), r.reason()));
    }
}
