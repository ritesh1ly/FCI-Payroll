package in.gov.fci.payroll.cpf.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.cpf.orchestration.CpfStatementService;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E-01 CPF balance and statement (plan 14 phase 14e; SRS-17-16, SRS-17-17). The SRS names the card,
 * not the API (PENDING API-14-1).
 *
 * <pre>
 * GET /v1/cpf/statements/mine?finYear=   the member's ledger of the year   E-01 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class CpfStatementController {

    private final CurrentAccess contexts;
    private final CpfStatementService statements;

    public CpfStatementController(CurrentAccess contexts, CpfStatementService statements) {
        this.contexts = contexts;
        this.statements = statements;
    }

    @GetMapping("/v1/cpf/statements/mine")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<CpfStatementService.Statement> mine(
            @RequestParam(required = false) @Pattern(regexp = "^\\d{4}-\\d{2}$") String finYear) {
        return noStore(statements.mine(contexts.current(), finYear));
    }
}
