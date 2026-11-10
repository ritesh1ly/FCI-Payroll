package in.gov.fci.payroll.labour.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.labour.business.LabourRecordRules;
import in.gov.fci.payroll.labour.orchestration.LabourRecordService;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * M-02 labour records API (plan 22 phase 22c; NWNP Reinstate Details and Nominee &amp; Dependent
 * Details; API-22-2):
 *
 * <pre>
 * POST /v1/labour/members/{id}/reinstatements   a reinstatement with its order (202)   M-02 EDIT
 * GET  /v1/labour/members/{id}/nominations      the nominees and dependents in force    M-02 VIEW
 * POST /v1/labour/members/{id}/nominations      new nominees and dependents (202)       M-02 EDIT
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class LabourRecordController {

    /** A nominee or dependent. */
    public record PersonBody(
            @NotBlank @Size(max = 150) @PrintableAscii String name,
            @NotBlank @Size(max = 30) @PrintableAscii String relationship,
            LocalDate birthDate,
            @DecimalMin(value = "0", inclusive = false)
                    @DecimalMax("100")
                    @Digits(integer = 3, fraction = 2)
                    BigDecimal share,
            Boolean nominee) {}

    /** The nominees and dependents of a member. */
    public record NominationBody(
            @NotNull @Size(max = 20) List<@Valid @NotNull PersonBody> nominees,
            @NotNull @Size(max = 30) List<@Valid @NotNull PersonBody> dependents,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final LabourRecordService records;

    public LabourRecordController(CurrentAccess contexts, LabourRecordService records) {
        this.contexts = contexts;
        this.records = records;
    }

    @PostMapping(
            value = "/v1/labour/members/{id}/reinstatements",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('M-02','EDIT')")
    public ResponseEntity<SubmittedApproval> reinstate(
            @PathVariable @Min(1) long id,
            @RequestParam LocalDate date,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String orderReference,
            @RequestParam LocalDate orderDate,
            @RequestParam @Size(max = 150) @PrintableAscii String issuingAuthority,
            @RequestParam @Size(max = 500) @PrintableAscii String reason,
            @RequestPart MultipartFile document)
            throws IOException {
        return accepted(
                records.reinstate(
                        contexts.current(),
                        id,
                        new LabourRecordService.Reinstatement(
                                date, orderReference, orderDate, issuingAuthority, reason),
                        new LabourRecordService.Document(
                                document.getOriginalFilename(), document.getBytes())));
    }

    @GetMapping("/v1/labour/members/{id}/nominations")
    @PreAuthorize("@authz.gate('M-02','VIEW')")
    public ResponseEntity<LabourRecordService.Nominations> nominations(
            @PathVariable @Min(1) long id) {
        return noStore(records.nominations(contexts.current(), id));
    }

    @PostMapping("/v1/labour/members/{id}/nominations")
    @PreAuthorize("@authz.gate('M-02','EDIT')")
    public ResponseEntity<SubmittedApproval> nominate(
            @PathVariable @Min(1) long id, @Valid @RequestBody NominationBody b) {
        return accepted(
                records.nominate(
                        contexts.current(),
                        id,
                        new LabourRecordService.Nomination(
                                b.nominees().stream().map(p -> person(p, true)).toList(),
                                b.dependents().stream().map(p -> person(p, false)).toList(),
                                b.reason())));
    }

    private static LabourRecordRules.Person person(PersonBody p, boolean nominee) {
        return new LabourRecordRules.Person(
                p.name(),
                p.relationship(),
                p.birthDate(),
                nominee ? p.share() : null,
                nominee || Boolean.TRUE.equals(p.nominee()));
    }

    private static ResponseEntity<SubmittedApproval> accepted(SubmittedApproval item) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(item);
    }
}
