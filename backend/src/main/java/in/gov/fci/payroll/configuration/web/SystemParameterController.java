package in.gov.fci.payroll.configuration.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.configuration.orchestration.SystemParameterService;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.workflow.api.Otp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/** A-07 System Parameters: list, and propose a change for checker approval. */
@RestController
@Profile("online")
@RequestMapping("/v1/admin/parameters")
public class SystemParameterController {

    public record ParameterChangeRequest(
            @NotNull Long value,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    private final CurrentAccess contexts;
    private final SystemParameterService systemParameters;
    private final Paging paging;

    public SystemParameterController(
            CurrentAccess contexts, SystemParameterService systemParameters, Paging paging) {
        this.paging = paging;
        this.contexts = contexts;
        this.systemParameters = systemParameters;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('A-07','VIEW')")
    public ResponseEntity<Paged<SystemParameterService.ParameterView>> parameters(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(systemParameters.list(contexts.current()), paging.resolve(page, size)));
    }

    @PutMapping("/{code}")
    @PreAuthorize("@authz.gate('A-07','EDIT')")
    public ResponseEntity<SystemParameterService.ParameterView> proposeParameter(
            @PathVariable @Pattern(regexp = "^[A-Z_]{3,40}$") String code,
            @Valid @RequestBody ParameterChangeRequest request) {
        SystemParameterService.ParameterView view =
                systemParameters.propose(
                        contexts.current(),
                        new SystemParameterService.ChangeRequest(
                                code, request.value(), request.reason()),
                        Otp.proof(request.otp()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(view);
    }
}
