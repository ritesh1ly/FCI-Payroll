package in.gov.fci.payroll.workflow.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.workflow.orchestration.OtpCatalogueService;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * X-08 Sign-in, OTP &amp; Password (OD-121; SRS-G-21): the eighteen OTP-gated actions with their
 * screens and the roles that enter the OTP, read from the approval routes.
 *
 * <pre>
 * GET /v1/otp/gated-actions   the go-live list of OTP actions   X-08 VIEW
 * </pre>
 */
@RestController
@Profile("online")
public class OtpCatalogueController {

    private final CurrentAccess contexts;
    private final OtpCatalogueService catalogue;

    public OtpCatalogueController(CurrentAccess contexts, OtpCatalogueService catalogue) {
        this.contexts = contexts;
        this.catalogue = catalogue;
    }

    @GetMapping("/v1/otp/gated-actions")
    @PreAuthorize("@authz.gate('X-08','VIEW')")
    public ResponseEntity<List<OtpCatalogueService.GatedAction>> gatedActions() {
        return noStore(catalogue.catalogue(contexts.current()));
    }
}
