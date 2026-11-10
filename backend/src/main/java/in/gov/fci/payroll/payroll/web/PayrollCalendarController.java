package in.gov.fci.payroll.payroll.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.payroll.business.CalendarRules;
import in.gov.fci.payroll.payroll.orchestration.CycleParametersService;
import in.gov.fci.payroll.payroll.orchestration.PayrollCalendarService;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.YearMonth;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * M-10 Payroll Calendar & Cycle Setup (plan 05 phase 05f; SRS-15-33, SRS M-10, SRS-15-1). The SRS
 * names the screen and its fields, not the API (PENDING API-05-1).
 */
@RestController
@Validated
@Profile("online")
public class PayrollCalendarController {

    private static final String FIN_YEAR = "^\\d{4}-\\d{2}$";

    /** One period of a proposal; the month as YYYY-MM. */
    public record PeriodBody(
            @NotNull @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String month,
            @NotNull LocalDate start,
            @NotNull LocalDate end,
            @NotNull LocalDate freezeTarget,
            LocalDate computeBy,
            LocalDate verifyBy,
            @NotNull LocalDate lockTarget,
            LocalDate disbursement,
            @NotNull LocalDate supplementaryFrom,
            @NotNull LocalDate supplementaryTo,
            @NotNull LocalDate mdpFrom,
            @NotNull LocalDate mdpTo) {

        CalendarRules.Period period() {
            return new CalendarRules.Period(
                    YearMonth.parse(month),
                    start,
                    end,
                    freezeTarget,
                    computeBy,
                    verifyBy,
                    lockTarget,
                    disbursement,
                    supplementaryFrom,
                    supplementaryTo,
                    mdpFrom,
                    mdpTo);
        }
    }

    public record CalendarProposal(
            @NotEmpty @Size(max = 12) List<@Valid @NotNull PeriodBody> periods,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    public record OrderProposal(
            @NotEmpty @Size(max = 100)
                    List<@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,39}$") String> order,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    /** The approval item a proposal opened. */
    public record Proposed(long approvalId) {}

    private final CurrentAccess contexts;
    private final PayrollCalendarService calendar;
    private final CycleParametersService parameters;

    public PayrollCalendarController(
            CurrentAccess contexts,
            PayrollCalendarService calendar,
            CycleParametersService parameters) {
        this.contexts = contexts;
        this.calendar = calendar;
        this.parameters = parameters;
    }

    /** M-10 Cycle Parameters panel (D-23): read-only, each value kept on its own screen. */
    @GetMapping("/v1/cycle-parameters")
    @PreAuthorize("@authz.gate('M-10','VIEW')")
    public ResponseEntity<CycleParametersService.Parameters> cycleParameters() {
        return noStore(parameters.parameters(contexts.current()));
    }

    @GetMapping("/v1/payroll-calendars")
    @PreAuthorize("@authz.gate('M-10','VIEW')")
    public ResponseEntity<List<String>> years() {
        return noStore(calendar.years(contexts.current()));
    }

    @GetMapping("/v1/payroll-calendars/{finYear}")
    @PreAuthorize("@authz.gate('M-10','VIEW')")
    public ResponseEntity<PayrollCalendarService.YearView> year(
            @PathVariable @Pattern(regexp = FIN_YEAR) String finYear) {
        return noStore(calendar.year(contexts.current(), finYear));
    }

    /** SRS-15-33: the Administrator proposes the year's periods (202: the Checker approves). */
    @PostMapping("/v1/payroll-calendars/{finYear}/proposals")
    @PreAuthorize("@authz.gate('M-10','EDIT')")
    public ResponseEntity<Proposed> propose(
            @PathVariable @Pattern(regexp = FIN_YEAR) String finYear,
            @Valid @RequestBody CalendarProposal body) {
        long id =
                calendar.propose(
                        contexts.current(),
                        finYear,
                        body.periods().stream().map(PeriodBody::period).toList(),
                        body.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(new Proposed(id));
    }

    @GetMapping("/v1/run-order-steps")
    @PreAuthorize("@authz.gate('M-10','VIEW')")
    public ResponseEntity<PayrollCalendarService.RunOrderView> runOrder() {
        return noStore(calendar.runOrder(contexts.current()));
    }

    /** SRS-15-1 / SRS-15-33: the Administrator proposes a new run order (202). */
    @PostMapping("/v1/run-order-steps/proposals")
    @PreAuthorize("@authz.gate('M-10','REORDER')")
    public ResponseEntity<Proposed> proposeOrder(@Valid @RequestBody OrderProposal body) {
        long id = calendar.proposeOrder(contexts.current(), body.order(), body.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(new Proposed(id));
    }
}
