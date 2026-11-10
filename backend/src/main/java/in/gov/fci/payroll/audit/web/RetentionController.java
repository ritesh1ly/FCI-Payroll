package in.gov.fci.payroll.audit.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.audit.orchestration.RetentionService;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.logging.Sensitive;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A-07 audit retention periods (SRS-20-5): list, and propose a period for checker approval. */
@RestController
@Profile("online")
@RequestMapping("/v1/admin/retention-classes")
public class RetentionController {

    /** The OTP presented on the repeat call after a 428. */
    public record OtpBody(Long challengeId, @Size(max = 6) @Sensitive String code) {
        @Override
        public String toString() {
            return "OtpBody{challengeId=" + challengeId + "}";
        }
    }

    public record RetentionChangeRequest(
            Integer months,
            boolean indefinite,
            @NotBlank @Size(max = 200) @PrintableAscii String basis,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid OtpBody otp) {}

    private final CurrentAccess contexts;
    private final RetentionService retention;
    private final Paging paging;

    public RetentionController(CurrentAccess contexts, RetentionService retention, Paging paging) {
        this.paging = paging;
        this.contexts = contexts;
        this.retention = retention;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('A-07','VIEW')")
    public ResponseEntity<Paged<RetentionService.RetentionView>> retentionClasses(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(Paged.slice(retention.list(contexts.current()), paging.resolve(page, size)));
    }

    @PutMapping("/{code}")
    @PreAuthorize("@authz.gate('A-07','EDIT')")
    public ResponseEntity<RetentionService.RetentionView> proposeRetention(
            @PathVariable @Pattern(regexp = "^[A-Z_]{3,40}$") String code,
            @Valid @RequestBody RetentionChangeRequest request) {
        RetentionService.RetentionView view =
                retention.propose(
                        contexts.current(),
                        new RetentionService.ChangeRequest(
                                code,
                                request.months(),
                                request.indefinite(),
                                request.basis(),
                                request.reason()),
                        request.otp() == null
                                ? null
                                : new OtpProof(request.otp().challengeId(), request.otp().code()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(view);
    }
}
