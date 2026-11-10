package in.gov.fci.payroll.jobs.web;

import in.gov.fci.payroll.jobs.business.model.ControlCommand;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.orchestration.BatchHaltService;
import in.gov.fci.payroll.jobs.orchestration.JobCatalog;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-13 Batch Jobs (online profile only). Submission and control are hand-offs through the database:
 * this controller records a request (AWAITING_APPROVAL, maker-checker) or a control command, and a
 * batch instance acts on it once it is PENDING. Approval and rejection use the standard workflow
 * endpoint ({@code POST /v1/approvals/{approvalId}/decision}, W-01). Every endpoint is gated on
 * C-13 (VIEW / CREATE / EDIT) and the service checks the data scope of the job's unit. POSTs carry
 * an Idempotency-Key (IdempotencyFilter) and are also idempotent on content: one open request per
 * job and parameters.
 */
@RestController
@Profile("online")
@RequestMapping("/v1/jobs")
public class JobController {

    public record SubmitRequest(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,29}$") String jobType,
            @NotNull @Size(max = 10)
                    Map<@Pattern(regexp = "^[A-Za-z]{2,30}$") String, @Size(max = 40) String>
                            params,
            @Min(1) @Max(9) Integer priority,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    public record ControlRequest(@NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    public record JobTypes(
            List<JobCatalog.JobDef> types,
            String currentPeriod,
            List<JobRequestService.LocationOption> locations) {}

    private final JobRequestService jobs;
    private final BatchHaltService halt;
    private final JobCatalog catalog;
    private final CurrentAccess contexts;
    private final Paging paging;

    public JobController(
            JobRequestService jobs,
            BatchHaltService halt,
            JobCatalog catalog,
            CurrentAccess contexts,
            Paging paging) {
        this.paging = paging;
        this.jobs = jobs;
        this.halt = halt;
        this.catalog = catalog;
        this.contexts = contexts;
    }

    /**
     * What the submit form offers: job types with their parameters, the current period, the units.
     */
    @GetMapping("/types")
    @PreAuthorize("@authz.gate('C-13','CREATE')")
    public ResponseEntity<JobTypes> types() {
        return noStore(
                new JobTypes(
                        catalog.submittable(),
                        catalog.currentPeriod().toString(),
                        jobs.submitLocations(contexts.current())));
    }

    /** 202: the job awaits a Payroll Checker's approval before any batch node may run it. */
    @PostMapping
    @PreAuthorize("@authz.gate('C-13','CREATE')")
    public ResponseEntity<JobRequestService.JobView> submit(
            @Valid @RequestBody SubmitRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(
                        jobs.submit(
                                contexts.current(),
                                request.jobType(),
                                request.params(),
                                request.priority(),
                                request.reason()));
    }

    /** History: server-side paging, filtering and sorting. */
    @GetMapping
    @PreAuthorize("@authz.gate('C-13','VIEW')")
    public ResponseEntity<JobRequestService.PageView<JobRequestService.JobView>> list(
            @RequestParam(required = false) JobStatus status,
            @RequestParam(required = false) JobType jobType,
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(required = false) @Size(max = 60) String requestedBy,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(defaultValue = "requestedAt") @Pattern(regexp = "^[A-Za-z]{2,20}$")
                    String sort,
            @RequestParam(defaultValue = "desc") @Pattern(regexp = "^(asc|desc)$")
                    String direction) {
        PageSpec spec = paging.resolve(page, size);
        return noStore(
                jobs.page(
                        contexts.current(),
                        new JobRequestService.JobQuery(
                                status,
                                jobType,
                                locationId,
                                from,
                                to,
                                requestedBy,
                                spec.page(),
                                spec.size(),
                                sort,
                                "asc".equals(direction))));
    }

    /** Detail: the request, every attempt with its steps and counts, commands, audit trail. */
    @GetMapping("/{id}")
    @PreAuthorize("@authz.gate('C-13','VIEW')")
    public ResponseEntity<JobRequestService.JobDetail> detail(@PathVariable Long id) {
        return noStore(jobs.detail(contexts.current(), id));
    }

    /** The failure log (skipped records), newest first. */
    @GetMapping("/{id}/skipped")
    @PreAuthorize("@authz.gate('C-13','VIEW')")
    public ResponseEntity<JobRequestService.PageView<JobJournal.SkippedItem>> skipped(
            @PathVariable Long id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageSpec spec = paging.resolve(page, size);
        return noStore(jobs.skipped(contexts.current(), id, spec.page(), spec.size()));
    }

    @PostMapping("/{id}/stop")
    @PreAuthorize("@authz.gate('C-13','EDIT')")
    public ResponseEntity<JobRequestService.ControlView> stop(
            @PathVariable Long id, @Valid @RequestBody ControlRequest request) {
        return control(id, ControlCommand.STOP, request);
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize("@authz.gate('C-13','EDIT')")
    public ResponseEntity<JobRequestService.ControlView> pause(
            @PathVariable Long id, @Valid @RequestBody ControlRequest request) {
        return control(id, ControlCommand.PAUSE, request);
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("@authz.gate('C-13','EDIT')")
    public ResponseEntity<JobRequestService.ControlView> resume(
            @PathVariable Long id, @Valid @RequestBody ControlRequest request) {
        return control(id, ControlCommand.RESUME, request);
    }

    @PostMapping("/{id}/restart")
    @PreAuthorize("@authz.gate('C-13','EDIT')")
    public ResponseEntity<JobRequestService.ControlView> restart(
            @PathVariable Long id, @Valid @RequestBody ControlRequest request) {
        return control(id, ControlCommand.RESTART, request);
    }

    /** Plan 05j: asks for the job to be abandoned for good; a Payroll Checker decides (W-01). */
    @PostMapping("/{id}/abandon")
    @PreAuthorize("@authz.gate('C-13','EDIT')")
    public ResponseEntity<JobRequestService.JobView> abandon(
            @PathVariable Long id, @Valid @RequestBody ControlRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(jobs.requestAbandon(contexts.current(), id, request.reason()));
    }

    /** Plan 05j: whether batch processing is halted (every C-13 viewer sees the banner). */
    @GetMapping("/halt")
    @PreAuthorize("@authz.gate('C-13','VIEW')")
    public ResponseEntity<BatchHaltService.HaltView> haltState() {
        return noStore(halt.state(contexts.current()));
    }

    /** Plan 05j: halts all batch processing at once (System Administrator at HQ). */
    @PostMapping("/halt")
    @PreAuthorize("@authz.gate('C-13','HALT')")
    public ResponseEntity<BatchHaltService.HaltView> engageHalt(
            @Valid @RequestBody ControlRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(halt.halt(contexts.current(), request.reason()));
    }

    /** Plan 05j: asks for the halt to be released; a Payroll Checker at HQ decides (W-01). */
    @PostMapping("/halt/release")
    @PreAuthorize("@authz.gate('C-13','HALT')")
    public ResponseEntity<BatchHaltService.HaltView> requestRelease(
            @Valid @RequestBody ControlRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(halt.requestRelease(contexts.current(), request.reason()));
    }

    private ResponseEntity<JobRequestService.ControlView> control(
            Long id, ControlCommand command, ControlRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(jobs.requestControl(contexts.current(), id, command, request.reason()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
