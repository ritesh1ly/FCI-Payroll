package in.gov.fci.payroll.employee.web;

import in.gov.fci.payroll.employee.orchestration.OnboardingService;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-01 onboarding (plan 16 D16-35; SRS-1-11, SRS-1-12): Establishment / HR submits a record in
 * Payroll Setup Pending, with the CDA Staff Code of a CDA member, to the Payroll Checker.
 *
 * <pre>
 * POST /v1/employees/{eis}/onboarding-submissions   the completed record (202)   M-01 SUBMIT
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class OnboardingController {

    /** What the Maker adds: the CDA Staff Code (CDA members only) and the remark. */
    public record SubmissionBody(
            @Size(max = 20) @PrintableAscii String cdaStaffCode,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    public record Submitted(long approvalId, String status) {}

    private final CurrentAccess contexts;
    private final OnboardingService onboarding;

    public OnboardingController(CurrentAccess contexts, OnboardingService onboarding) {
        this.contexts = contexts;
        this.onboarding = onboarding;
    }

    @PostMapping("/v1/employees/{eis}/onboarding-submissions")
    @PreAuthorize("@authz.gate('M-01','SUBMIT')")
    public ResponseEntity<Submitted> submit(
            @PathVariable @Size(max = 20) String eis, @Valid @RequestBody SubmissionBody body) {
        SubmittedApproval item =
                onboarding.submit(contexts.current(), eis, body.cdaStaffCode(), body.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(new Submitted(item.id(), item.status().name()));
    }
}
