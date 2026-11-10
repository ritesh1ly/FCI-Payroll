package in.gov.fci.payroll.configuration.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.configuration.orchestration.ReferenceListService;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.workflow.api.Otp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-16 Reference &amp; Combo List Administration: every list with its values and pending changes;
 * propose a new value (CREATE) or a change of one (EDIT). Static gates; the service checks HQ,
 * system lists and pending changes, and the checker decides in W-01.
 */
@RestController
@Profile("online")
@Validated
@RequestMapping("/v1/masters/reference-lists")
public class ReferenceListController {

    private static final String CODE = "^[A-Z0-9_]{1,40}$";

    public record NewValueRequest(
            @NotBlank @Pattern(regexp = CODE) String code,
            @NotBlank @Size(max = 200) @PrintableAscii String labelEn,
            @Size(max = 200) String labelHi,
            @Min(0) @Max(99_999) Integer sortOrder,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    public record ChangeRequest(
            @NotBlank @Size(max = 200) @PrintableAscii String labelEn,
            @Size(max = 200) String labelHi,
            @Min(0) @Max(99_999) Integer sortOrder,
            LocalDate effectiveTo,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    private final CurrentAccess contexts;
    private final ReferenceListService lists;

    public ReferenceListController(CurrentAccess contexts, ReferenceListService lists) {
        this.contexts = contexts;
        this.lists = lists;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('M-16','VIEW')")
    public ResponseEntity<List<ReferenceListService.ListRow>> lists() {
        return noStore(lists.lists(contexts.current()));
    }

    @PostMapping("/{list}/values")
    @PreAuthorize("@authz.gate('M-16','CREATE')")
    public ResponseEntity<ReferenceListService.PendingChange> proposeNew(
            @PathVariable @Pattern(regexp = CODE) String list,
            @Valid @RequestBody NewValueRequest r) {
        return accepted(
                lists.proposeNew(
                        contexts.current(),
                        list,
                        new ReferenceListService.Proposal(
                                r.code(),
                                r.labelEn(),
                                r.labelHi(),
                                r.sortOrder(),
                                r.effectiveFrom(),
                                r.effectiveTo(),
                                r.reason()),
                        Otp.proof(r.otp())));
    }

    @PostMapping("/{list}/values/{code}")
    @PreAuthorize("@authz.gate('M-16','EDIT')")
    public ResponseEntity<ReferenceListService.PendingChange> proposeChange(
            @PathVariable @Pattern(regexp = CODE) String list,
            @PathVariable @Pattern(regexp = CODE) String code,
            @Valid @RequestBody ChangeRequest r) {
        return accepted(
                lists.proposeChange(
                        contexts.current(),
                        list,
                        code,
                        new ReferenceListService.Proposal(
                                code,
                                r.labelEn(),
                                r.labelHi(),
                                r.sortOrder(),
                                null,
                                r.effectiveTo(),
                                r.reason()),
                        Otp.proof(r.otp())));
    }

    private static <T> ResponseEntity<T> accepted(T body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
