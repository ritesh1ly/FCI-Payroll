package in.gov.fci.payroll.labour.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.labour.business.LabourRules.GangRole;
import in.gov.fci.payroll.labour.orchestration.GangService;
import in.gov.fci.payroll.labour.orchestration.LabourAttachmentService;
import in.gov.fci.payroll.labour.orchestration.LabourCorrectionService;
import in.gov.fci.payroll.labour.orchestration.LabourLifecycleService;
import in.gov.fci.payroll.labour.orchestration.LabourMasterService;
import in.gov.fci.payroll.labour.orchestration.TransferService;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
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
 * M-02 Labour Master and M-09 Gang / Shed Structure (plan 20 phase 20a; SRS-2-1, SRS-2-30,
 * SRS-2-31, SRS-2-46, SRS-2-48). The SRS names the screens, not the API (PENDING API-20-1). No
 * DELETE anywhere (SRS-G-37).
 *
 * <pre>
 * GET  /v1/labour/options                       depots and labour designations     M-02 VIEW
 * GET  /v1/labour/members                       the labour of the offices, paged   M-02 VIEW
 * GET  /v1/labour/members/{id}                  one member for the amendment form  M-02 VIEW
 * POST /v1/labour/members                       a new labour member (202)          M-02 CREATE
 * POST /v1/labour/members/{id}/amendments       an amendment (202)                 M-02 EDIT
 * POST /v1/labour/members/{id}/transfers         a transfer to a gang (202)         M-02 EDIT
 * POST /v1/labour/members/{id}/attachments       an attachment to a gang (202)      M-09 EDIT
 * POST /v1/labour/members/{id}/separations       a separation (202)                 M-02 EDIT
 * POST /v1/labour/members/{id}/light-duty-orders a Permanent Light Duty order (202) M-02 EDIT
 * POST /v1/labour/members/{id}/corrections      a name / DOB correction (202)      M-02 EDIT
 * POST /v1/labour/gangs/{id}/members             a labour joins the gang (202)      M-09 EDIT
 * POST /v1/labour/gangs/{id}/leaders             a new Sardar or Mandal (202)       M-09 EDIT
 * GET  /v1/labour/gangs                         the gangs of the depots, paged     M-09 VIEW
 *      (?depot, payrollType, gangType, status, text: searched on the server)
 * GET  /v1/labour/gangs/{id}/members            a gang's members (?date: on a day) M-09 VIEW
 * POST /v1/labour/gangs                         a new handling gang (202)          M-09 CREATE
 * POST /v1/labour/gangs/{id}/sanctioned-strengths  a new strength from a date (202) M-09 EDIT
 * POST /v1/labour/gangs/{id}/end-dates          the gang ends at a date (202)      M-09 EDIT
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class LabourController {

    private static final String TYPE = "^(DEPT_LABOUR|DPS_LABOUR|NWNP_LABOUR)$";

    /** M-02 fields. */
    public record MemberBody(
            @NotNull @Pattern(regexp = TYPE) String payrollType,
            @NotBlank @Size(max = 100) @PrintableAscii String firstName,
            @Size(max = 100) @PrintableAscii String lastName,
            @Size(max = 150) @PrintableAscii String fatherName,
            @Pattern(regexp = "^(M|F|O)?$") String gender,
            @NotNull LocalDate birthDate,
            @NotNull LocalDate joiningDate,
            @NotNull Long designationId,
            @NotNull Long depotLocationId,
            @Size(max = 10) @PrintableAscii String panNumber,
            @NotBlank @Size(max = 11) @PrintableAscii String ifsc,
            @NotBlank @Size(max = 20) @PrintableAscii String bankAccount,
            @Size(max = 10) @PrintableAscii String mobile,
            boolean pfApplicable,
            @Size(max = 20) @PrintableAscii String cpfCode,
            @Size(max = 20) @PrintableAscii String uanNumber,
            Boolean casualLabour,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {

        LabourMasterService.Request request() {
            return new LabourMasterService.Request(
                    payrollType,
                    firstName,
                    lastName,
                    fatherName,
                    gender,
                    birthDate,
                    joiningDate,
                    designationId,
                    depotLocationId,
                    panNumber,
                    ifsc,
                    bankAccount,
                    mobile,
                    pfApplicable,
                    cpfCode,
                    uanNumber,
                    Boolean.TRUE.equals(casualLabour),
                    reason);
        }
    }

    /**
     * M-02 fields of an amendment: the payroll type, name, date of birth, designation and depot are
     * kept (SRS-2-59 correction, designation change, transfer). SRS-2-30 "keep the old value in
     * force": every field is optional; one left out keeps its current value, an optional one sent
     * empty is cleared.
     */
    public record AmendBody(
            @Size(max = 150) @PrintableAscii String fatherName,
            @Pattern(regexp = "^(M|F|O)?$") String gender,
            LocalDate joiningDate,
            @Size(max = 10) @PrintableAscii String panNumber,
            @Size(max = 11) @PrintableAscii String ifsc,
            @Size(max = 20) @PrintableAscii String bankAccount,
            @Size(max = 10) @PrintableAscii String mobile,
            Boolean pfApplicable,
            @Size(max = 20) @PrintableAscii String cpfCode,
            @Size(max = 20) @PrintableAscii String uanNumber,
            Boolean casualLabour,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {

        LabourMasterService.Request request() {
            return new LabourMasterService.Request(
                    null,
                    null,
                    null,
                    fatherName,
                    gender,
                    null,
                    joiningDate,
                    null,
                    null,
                    panNumber,
                    ifsc,
                    bankAccount,
                    mobile,
                    pfApplicable,
                    cpfCode,
                    uanNumber,
                    casualLabour,
                    reason);
        }
    }

    /**
     * A transfer to another gang from a date, with its order (SRS l.4429). The order fields are
     * optional in the body so an older client is answered with the service's ORDER refusal.
     */
    public record TransferBody(
            @NotNull Long toGangId,
            @NotNull LocalDate transferDate,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Size(max = 100) @PrintableAscii String orderReference,
            LocalDate orderDate,
            @Size(max = 150) @PrintableAscii String issuingAuthority) {}

    /** An attachment to another gang for a date range (no end: a permanent move). */
    public record AttachmentBody(
            @NotNull Long toGangId,
            @NotNull LocalDate from,
            LocalDate to,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Size(max = 100) @PrintableAscii String orderReference,
            LocalDate orderDate,
            @Size(max = 150) @PrintableAscii String issuingAuthority) {}

    /**
     * A separation of a labour member, with its order (SRS l.4429). The order fields are optional
     * in the body so an older client is answered with the service's ORDER refusal, not a 400.
     */
    public record SeparationBody(
            @NotNull @Pattern(regexp = "^(RETIREMENT|RESIGNATION|DEATH|TERMINATION)$") String type,
            @NotNull LocalDate date,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Size(max = 100) @PrintableAscii String orderReference,
            LocalDate orderDate,
            @Size(max = 150) @PrintableAscii String issuingAuthority) {}

    /** A Permanent Light Duty order. */
    public record LightDutyBody(
            @NotNull Long toDesignationId,
            @NotNull LocalDate effectiveDate,
            @NotNull @Pattern(regexp = "^(ANCILLARY_PAY|PROTECTED_PAY)$") String payOption,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2)
                    BigDecimal protectedBasic,
            @NotBlank @Size(max = 100) @PrintableAscii String orderReference,
            @NotNull LocalDate orderDate,
            @NotBlank @Size(max = 150) @PrintableAscii String issuingAuthority,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    /** A labour joining a gang. */
    public record JoinBody(
            @NotNull Long employeeId,
            @NotNull LocalDate from,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    /** A new Sardar or Mandal. */
    public record LeaderBody(
            @NotNull GangRole role,
            @NotNull Long employeeId,
            @NotNull LocalDate from,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    /** One member of a new gang. */
    public record MemberRole(@NotNull Long employeeId, @NotNull GangRole role) {}

    /** M-09 fields of a new gang. */
    public record GangBody(
            @NotNull Long depotLocationId,
            @NotNull @Pattern(regexp = TYPE) String payrollType,
            @NotBlank @Size(max = 150) @PrintableAscii String gangName,
            @Min(1) @Max(9999) Integer sanctionedStrength,
            @NotNull LocalDate effectiveFrom,
            @NotNull @Size(min = 2, max = 500) List<@Valid MemberRole> members,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    /** A gang's end date. */
    public record EndBody(
            @NotNull LocalDate endDate, @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    /** A new sanctioned strength. */
    public record StrengthBody(
            @NotNull @Min(1) @Max(9999) Integer strength,
            @NotNull LocalDate effectiveFrom,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final LabourMasterService master;
    private final GangService gangs;
    private final TransferService transfers;
    private final LabourAttachmentService attachments;
    private final LabourLifecycleService lifecycle;
    private final LabourCorrectionService corrections;
    private final Paging paging;

    public LabourController(
            CurrentAccess contexts,
            LabourMasterService master,
            GangService gangs,
            TransferService transfers,
            LabourAttachmentService attachments,
            LabourLifecycleService lifecycle,
            LabourCorrectionService corrections,
            Paging paging) {
        this.contexts = contexts;
        this.master = master;
        this.gangs = gangs;
        this.transfers = transfers;
        this.attachments = attachments;
        this.lifecycle = lifecycle;
        this.corrections = corrections;
        this.paging = paging;
    }

    @GetMapping("/v1/labour/options")
    @PreAuthorize("@authz.gate('M-02','VIEW')")
    public ResponseEntity<LabourMasterService.Options> options() {
        return noStore(master.options(contexts.current()));
    }

    @GetMapping("/v1/labour/members")
    @PreAuthorize("@authz.gate('M-02','VIEW')")
    public ResponseEntity<Paged<LabourMasterService.Row>> members(
            @RequestParam(required = false) @Pattern(regexp = TYPE) String payrollType,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String text,
            @RequestParam(required = false) @Size(max = 150) @PrintableAscii String gang,
            @RequestParam(required = false) @Positive Long depot,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate asOf,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                master.page(
                        contexts.current(),
                        new LabourMasterService.Search(payrollType, text, gang, depot, asOf),
                        paging.resolve(page, size)));
    }

    /** SRS-2-30: one labour member as the M-02 amendment form opens it (PAN, account masked). */
    @GetMapping("/v1/labour/members/{id}")
    @PreAuthorize("@authz.gate('M-02','VIEW')")
    public ResponseEntity<LabourMasterService.Detail> member(@PathVariable @Min(1) long id) {
        return noStore(master.detail(contexts.current(), id));
    }

    @PostMapping("/v1/labour/members")
    @PreAuthorize("@authz.gate('M-02','CREATE')")
    public ResponseEntity<SubmittedApproval> create(@Valid @RequestBody MemberBody body) {
        return accepted(master.create(contexts.current(), body.request()));
    }

    @PostMapping("/v1/labour/members/{id}/amendments")
    @PreAuthorize("@authz.gate('M-02','EDIT')")
    public ResponseEntity<SubmittedApproval> amend(
            @PathVariable @Min(1) long id, @Valid @RequestBody AmendBody body) {
        return accepted(master.amend(contexts.current(), id, body.request()));
    }

    @PostMapping("/v1/labour/members/{id}/transfers")
    @PreAuthorize("@authz.gate('M-02','EDIT')")
    public ResponseEntity<SubmittedApproval> transfer(
            @PathVariable @Min(1) long id, @Valid @RequestBody TransferBody body) {
        return accepted(
                transfers.propose(
                        contexts.current(),
                        id,
                        new TransferService.Request(
                                body.toGangId(),
                                body.transferDate(),
                                body.reason(),
                                body.orderReference(),
                                body.orderDate(),
                                body.issuingAuthority())));
    }

    @PostMapping("/v1/labour/members/{id}/attachments")
    @PreAuthorize("@authz.gate('M-09','EDIT')")
    public ResponseEntity<SubmittedApproval> attach(
            @PathVariable @Min(1) long id, @Valid @RequestBody AttachmentBody b) {
        return accepted(
                attachments.propose(
                        contexts.current(),
                        id,
                        new LabourAttachmentService.Request(
                                b.toGangId(),
                                b.from(),
                                b.to(),
                                b.reason(),
                                b.orderReference(),
                                b.orderDate(),
                                b.issuingAuthority())));
    }

    @PostMapping("/v1/labour/members/{id}/separations")
    @PreAuthorize("@authz.gate('M-02','EDIT')")
    public ResponseEntity<SubmittedApproval> separate(
            @PathVariable @Min(1) long id, @Valid @RequestBody SeparationBody b) {
        return accepted(
                lifecycle.separate(
                        contexts.current(),
                        id,
                        new LabourLifecycleService.Separation(
                                b.type(),
                                b.date(),
                                b.reason(),
                                b.orderReference(),
                                b.orderDate(),
                                b.issuingAuthority())));
    }

    @PostMapping("/v1/labour/members/{id}/light-duty-orders")
    @PreAuthorize("@authz.gate('M-02','EDIT')")
    public ResponseEntity<SubmittedApproval> lightDuty(
            @PathVariable @Min(1) long id, @Valid @RequestBody LightDutyBody b) {
        return accepted(
                lifecycle.lightDuty(
                        contexts.current(),
                        id,
                        new LabourLifecycleService.LightDuty(
                                b.toDesignationId(),
                                b.effectiveDate(),
                                b.payOption(),
                                b.protectedBasic(),
                                b.orderReference(),
                                b.orderDate(),
                                b.issuingAuthority(),
                                b.reason())));
    }

    /** SRS-2-59: a name or date-of-birth correction with the service-book page or order. */
    @PostMapping(
            value = "/v1/labour/members/{id}/corrections",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('M-02','EDIT')")
    public ResponseEntity<SubmittedApproval> correct(
            @PathVariable @Min(1) long id,
            @RequestParam @Pattern(regexp = "^(NAME|DOB)$") String type,
            @RequestParam(required = false) @Size(max = 150) @PrintableAscii String firstName,
            @RequestParam(required = false) @Size(max = 150) @PrintableAscii String lastName,
            @RequestParam(required = false) LocalDate birthDate,
            @RequestParam @Size(max = 40) @PrintableAscii String reasonCode,
            @RequestParam(required = false) @Size(max = 1000) @PrintableAscii String reasonText,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String orderReference,
            @RequestParam LocalDate orderDate,
            @RequestParam @Size(max = 150) @PrintableAscii String issuingAuthority,
            @RequestParam LocalDate effectiveDate,
            @RequestPart MultipartFile document)
            throws IOException {
        return accepted(
                corrections.correct(
                        contexts.current(),
                        id,
                        new LabourCorrectionService.Correction(
                                type,
                                firstName,
                                lastName,
                                birthDate,
                                reasonCode,
                                reasonText,
                                orderReference,
                                orderDate,
                                issuingAuthority,
                                effectiveDate),
                        new LabourCorrectionService.Document(
                                document.getOriginalFilename(), document.getBytes())));
    }

    @PostMapping("/v1/labour/gangs/{id}/members")
    @PreAuthorize("@authz.gate('M-09','EDIT')")
    public ResponseEntity<SubmittedApproval> join(
            @PathVariable @Min(1) long id, @Valid @RequestBody JoinBody b) {
        return accepted(
                gangs.addMember(contexts.current(), id, b.employeeId(), b.from(), b.reason()));
    }

    @PostMapping("/v1/labour/gangs/{id}/leaders")
    @PreAuthorize("@authz.gate('M-09','EDIT')")
    public ResponseEntity<SubmittedApproval> leader(
            @PathVariable @Min(1) long id, @Valid @RequestBody LeaderBody b) {
        return accepted(
                gangs.changeLeader(
                        contexts.current(), id, b.role(), b.employeeId(), b.from(), b.reason()));
    }

    @GetMapping("/v1/labour/gangs")
    @PreAuthorize("@authz.gate('M-09','VIEW')")
    public ResponseEntity<Paged<GangService.Row>> gangs(
            @RequestParam(required = false) Long depot,
            @RequestParam(required = false) @Pattern(regexp = TYPE) String payrollType,
            @RequestParam(required = false)
                    @Pattern(regexp = "^(HANDLING|ANCILLARY_POOL|LEGACY_DUMMY_ANCILLARY)$")
                    String gangType,
            @RequestParam(required = false) @Pattern(regexp = "^(ACTIVE|END_DATED)$") String status,
            @RequestParam(required = false) @Size(max = 150) @PrintableAscii String text,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                gangs.page(
                        contexts.current(),
                        new GangService.Search(depot, payrollType, gangType, status, text),
                        paging.resolve(page, size)));
    }

    /** M-02 transfer: receiving gangs of the payroll type in any office (SRS-2-6; OD-88). */
    @GetMapping("/v1/labour/transfer-targets")
    @PreAuthorize("@authz.gate('M-02','EDIT')")
    public ResponseEntity<Paged<GangService.Row>> transferTargets(
            @RequestParam @Pattern(regexp = TYPE) String payrollType,
            @RequestParam(required = false) @Size(max = 150) @PrintableAscii String text,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                gangs.transferTargets(
                        contexts.current(), payrollType, text, paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/gangs/{id}/members")
    @PreAuthorize("@authz.gate('M-09','VIEW')")
    public ResponseEntity<List<GangService.MemberRow>> gangMembers(
            @PathVariable @Min(1) long id, @RequestParam(required = false) LocalDate date) {
        return noStore(gangs.members(contexts.current(), id, date));
    }

    @PostMapping("/v1/labour/gangs")
    @PreAuthorize("@authz.gate('M-09','CREATE')")
    public ResponseEntity<SubmittedApproval> createGang(@Valid @RequestBody GangBody b) {
        return accepted(
                gangs.create(
                        contexts.current(),
                        new GangService.NewGang(
                                b.depotLocationId(),
                                b.payrollType(),
                                b.gangName(),
                                b.sanctionedStrength(),
                                b.effectiveFrom(),
                                b.members().stream()
                                        .map(m -> new GangService.Member(m.employeeId(), m.role()))
                                        .toList(),
                                b.reason())));
    }

    @PostMapping("/v1/labour/gangs/{id}/sanctioned-strengths")
    @PreAuthorize("@authz.gate('M-09','EDIT')")
    public ResponseEntity<SubmittedApproval> strength(
            @PathVariable @Min(1) long id, @Valid @RequestBody StrengthBody b) {
        return accepted(
                gangs.strength(
                        contexts.current(), id, b.strength(), b.effectiveFrom(), b.reason()));
    }

    @PostMapping("/v1/labour/gangs/{id}/end-dates")
    @PreAuthorize("@authz.gate('M-09','EDIT')")
    public ResponseEntity<SubmittedApproval> endDate(
            @PathVariable @Min(1) long id, @Valid @RequestBody EndBody b) {
        return accepted(gangs.endDate(contexts.current(), id, b.endDate(), b.reason()));
    }

    private static ResponseEntity<SubmittedApproval> accepted(SubmittedApproval item) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(item);
    }
}
