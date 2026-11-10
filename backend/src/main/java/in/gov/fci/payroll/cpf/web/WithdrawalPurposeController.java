package in.gov.fci.payroll.cpf.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.cpf.orchestration.WithdrawalPurposeService;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.workflow.api.Otp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CPF withdrawal purposes and checklists on M-19 (SRS-6-5, SRS-6-14, SRS-6-39; plan 07 D-5). The
 * Rule Approver decides in the Approvals Inbox (W-01).
 *
 * <pre>
 * GET  /v1/cpf/withdrawal-purposes    every version and the pending proposals   M-19 VIEW
 * POST /v1/cpf/withdrawal-purposes    a new version with its checklist          M-19 CREATE -> M19_CREATE
 * </pre>
 *
 * No DELETE: a purpose ends when a later version starts (R-DEL). Tracker API-07-11: the SRS gives
 * the purpose list and checklist, not this contract.
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/cpf/withdrawal-purposes")
public class WithdrawalPurposeController {

    public record DocumentRequest(
            @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String documentType,
            boolean mandatory,
            @Pattern(regexp = "^(UNIT_CPF_ADMIN|ZONAL_HQ_CPF_DIVISION|COMPETENT_AUTHORITY)$")
                    String tier) {}

    public record PurposeRequest(
            @NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9_]{1,30}$") String code,
            @NotBlank @Size(max = 200) @PrintableAscii String name,
            @NotBlank @Pattern(regexp = "^(ADVANCE|PART_FINAL|FINAL)$") String category,
            @Size(max = 100) @Pattern(regexp = "^[A-Z0-9_.]{0,100}$") String limitRuleKey,
            @Min(1) @Max(999) Integer maxOccurrences,
            boolean noCasteDispute,
            boolean propertyDetails,
            @NotNull LocalDate effectiveFrom,
            boolean confirmed,
            @Size(max = 30) List<@Valid DocumentRequest> documents,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    private final CurrentAccess contexts;
    private final WithdrawalPurposeService service;

    public WithdrawalPurposeController(CurrentAccess contexts, WithdrawalPurposeService service) {
        this.contexts = contexts;
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('M-19','VIEW')")
    public ResponseEntity<WithdrawalPurposeService.Purposes> list() {
        return noStore(service.list(contexts.current()));
    }

    @PostMapping
    @PreAuthorize("@authz.gate('M-19','CREATE')")
    public ResponseEntity<WithdrawalPurposeService.Pending> propose(
            @Valid @RequestBody PurposeRequest r) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(
                        service.propose(
                                contexts.current(),
                                new WithdrawalPurposeService.Proposal(
                                        r.code(),
                                        r.name(),
                                        r.category(),
                                        r.limitRuleKey() == null || r.limitRuleKey().isBlank()
                                                ? null
                                                : r.limitRuleKey(),
                                        r.maxOccurrences(),
                                        r.noCasteDispute(),
                                        r.propertyDetails(),
                                        r.effectiveFrom(),
                                        r.confirmed(),
                                        r.documents() == null
                                                ? List.of()
                                                : r.documents().stream()
                                                        .map(
                                                                d ->
                                                                        new WithdrawalPurposeService
                                                                                .DocumentView(
                                                                                d.documentType(),
                                                                                d.mandatory(),
                                                                                d.tier()))
                                                        .toList(),
                                        null,
                                        null),
                                r.reason(),
                                Otp.proof(r.otp())));
    }
}
