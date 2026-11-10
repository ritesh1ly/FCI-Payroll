package in.gov.fci.payroll.labour.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.labour.orchestration.AttendanceService;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
 * I-01 Labour Attendance Capture API (plan 20 phase 20b; SRS-2-2, SRS-2-29, SRS-2-35, SRS-2-38;
 * D20-8; API-20-1):
 *
 * <pre>
 * GET  /v1/labour/gangs/{id}/attendance?month=YYYY-MM  the gang's month grid       I-01 VIEW
 * POST /v1/labour/gangs/{id}/attendance-days            a day's marks (200)          I-01 CREATE
 * POST /v1/labour/gangs/{id}/attendance-batches         the month's batch (202)      I-01 SUBMIT
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class AttendanceController {

    private static final String MONTH = "^\\d{4}-(0[1-9]|1[0-2])$";

    /** One member's mark. */
    public record MarkBody(
            @NotNull Long employeeId,
            @NotNull @Size(min = 1, max = 5) @PrintableAscii String code) {}

    /** A gang's day: every member of the gang that day. */
    public record DayBody(
            @NotNull LocalDate date, @NotNull @Size(max = 500) List<@Valid MarkBody> marks) {}

    /** A month batch. */
    public record BatchBody(@NotNull @Pattern(regexp = MONTH) String month) {}

    private final CurrentAccess contexts;
    private final AttendanceService attendance;

    public AttendanceController(CurrentAccess contexts, AttendanceService attendance) {
        this.contexts = contexts;
        this.attendance = attendance;
    }

    @GetMapping("/v1/labour/gangs/{id}/attendance")
    @PreAuthorize("@authz.gate('I-01','VIEW')")
    public ResponseEntity<AttendanceService.Grid> grid(
            @PathVariable @Min(1) long id, @RequestParam @Pattern(regexp = MONTH) String month) {
        return noStore(attendance.grid(contexts.current(), id, YearMonth.parse(month)));
    }

    @PostMapping("/v1/labour/gangs/{id}/attendance-days")
    @PreAuthorize("@authz.gate('I-01','CREATE')")
    public ResponseEntity<AttendanceService.Saved> day(
            @PathVariable @Min(1) long id, @Valid @RequestBody DayBody body) {
        return noStore(
                attendance.saveDay(
                        contexts.current(),
                        id,
                        body.date(),
                        body.marks().stream()
                                .map(m -> new AttendanceService.Mark(m.employeeId(), m.code()))
                                .toList()));
    }

    @PostMapping("/v1/labour/gangs/{id}/attendance-batches")
    @PreAuthorize("@authz.gate('I-01','SUBMIT')")
    public ResponseEntity<SubmittedApproval> batch(
            @PathVariable @Min(1) long id, @Valid @RequestBody BatchBody body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(attendance.submit(contexts.current(), id, YearMonth.parse(body.month())));
    }
}
