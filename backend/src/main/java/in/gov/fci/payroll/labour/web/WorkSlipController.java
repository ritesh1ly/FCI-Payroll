package in.gov.fci.payroll.labour.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.labour.orchestration.WorkSlipService;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * I-02 Work-Slip Capture API (plan 20 phase 20c; SRS-2-4, SRS-2-15, SRS-2-29, SRS-3-9; D20-9;
 * API-20-1):
 *
 * <pre>
 * GET  /v1/labour/gangs/{id}/work-slips?month=YYYY-MM  the gang's month         I-02 VIEW
 * POST /v1/labour/gangs/{id}/work-slips                 a new work-slip (201)    I-02 CREATE
 * POST /v1/labour/work-slips/{id}/amendments            a replacing slip (201)   I-02 EDIT
 * POST /v1/labour/work-slips/{id}/cancellations         the slip withdrawn       I-02 EDIT
 * POST /v1/labour/gangs/{id}/work-slip-batches          the month's batch (202)  I-02 SUBMIT
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class WorkSlipController {

    private static final String MONTH = "^\\d{4}-(0[1-9]|1[0-2])$";
    private static final String TIME = "^([01]\\d|2[0-3]):[0-5]\\d$";

    /** One row of work. */
    public record RowBody(
            @NotNull Long operationId,
            Long bagTypeId,
            @Min(1) @Max(99_999) Integer bags,
            @Min(1) @Max(9999) Integer stackHeight,
            @NotNull @Pattern(regexp = TIME) String start,
            @NotNull @Pattern(regexp = TIME) String end,
            @Pattern(regexp = "^(RAKE_DELAYED|RAKE_NOT_PLACED|OTHER)$") String pbnwReason,
            @Size(max = 500) @PrintableAscii String pbnwText,
            @Size(max = 60) @PrintableAscii String rakeReference) {

        WorkSlipService.RowEntry entry() {
            return new WorkSlipService.RowEntry(
                    operationId,
                    bagTypeId,
                    bags,
                    stackHeight,
                    start,
                    end,
                    pbnwReason,
                    pbnwText,
                    rakeReference);
        }
    }

    /** A work-slip: the date, the rows and the participating members. */
    public record SlipBody(
            @NotNull LocalDate date,
            boolean continueWork,
            @NotNull @Size(min = 1, max = 20) List<@Valid RowBody> rows,
            @NotNull @Size(min = 1, max = 500) List<@NotNull Long> participants) {

        WorkSlipService.Entry entry() {
            return new WorkSlipService.Entry(
                    date, continueWork, rows.stream().map(RowBody::entry).toList(), participants);
        }
    }

    /** An amendment: the replacing slip and the reason. */
    public record AmendmentBody(
            @NotNull @Valid SlipBody slip,
            @NotBlank @Size(max = 1000) @PrintableAscii String reason) {}

    /** A cancellation reason. */
    public record ReasonBody(@NotBlank @Size(max = 1000) @PrintableAscii String reason) {}

    /** A month batch. */
    public record BatchBody(@NotNull @Pattern(regexp = MONTH) String month) {}

    private final CurrentAccess contexts;
    private final WorkSlipService slips;

    public WorkSlipController(CurrentAccess contexts, WorkSlipService slips) {
        this.contexts = contexts;
        this.slips = slips;
    }

    @GetMapping("/v1/labour/gangs/{id}/work-slips")
    @PreAuthorize("@authz.gate('I-02','VIEW')")
    public ResponseEntity<WorkSlipService.Month> month(
            @PathVariable @Min(1) long id, @RequestParam @Pattern(regexp = MONTH) String month) {
        return noStore(slips.month(contexts.current(), id, YearMonth.parse(month)));
    }

    @PostMapping("/v1/labour/gangs/{id}/work-slips")
    @PreAuthorize("@authz.gate('I-02','CREATE')")
    public ResponseEntity<WorkSlipService.Slip> save(
            @PathVariable @Min(1) long id, @Valid @RequestBody SlipBody body) {
        return created(slips.save(contexts.current(), id, body.entry()));
    }

    @PostMapping("/v1/labour/work-slips/{id}/amendments")
    @PreAuthorize("@authz.gate('I-02','EDIT')")
    public ResponseEntity<WorkSlipService.Slip> amend(
            @PathVariable @Min(1) long id, @Valid @RequestBody AmendmentBody body) {
        return created(slips.amend(contexts.current(), id, body.slip().entry(), body.reason()));
    }

    @PostMapping("/v1/labour/work-slips/{id}/cancellations")
    @PreAuthorize("@authz.gate('I-02','EDIT')")
    public ResponseEntity<WorkSlipService.Slip> cancel(
            @PathVariable @Min(1) long id, @Valid @RequestBody ReasonBody body) {
        return noStore(slips.cancel(contexts.current(), id, body.reason()));
    }

    /** SRS-2-34 (DPS): a booking-slip goes to the Depot Manager of the depot. */
    @PostMapping("/v1/labour/work-slips/{id}/submissions")
    @PreAuthorize("@authz.gate('I-02','SUBMIT')")
    public ResponseEntity<SubmittedApproval> submitSlip(@PathVariable @Min(1) long id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(slips.submitSlip(contexts.current(), id));
    }

    @PostMapping("/v1/labour/gangs/{id}/work-slip-batches")
    @PreAuthorize("@authz.gate('I-02','SUBMIT')")
    public ResponseEntity<SubmittedApproval> batch(
            @PathVariable @Min(1) long id, @Valid @RequestBody BatchBody body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(slips.submit(contexts.current(), id, YearMonth.parse(body.month())));
    }

    private static ResponseEntity<WorkSlipService.Slip> created(WorkSlipService.Slip slip) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(slip);
    }
}
