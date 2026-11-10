package in.gov.fci.payroll.payroll.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.payroll.business.CycleCorrectionRules.ReturnScope;
import in.gov.fci.payroll.payroll.orchestration.CycleCorrections;
import in.gov.fci.payroll.payroll.orchestration.CycleService;
import in.gov.fci.payroll.payroll.orchestration.CycleUndo;
import in.gov.fci.payroll.payroll.orchestration.PreRunChecks;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.workflow.api.Otp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.YearMonth;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-02 payroll cycle of the session's cycle unit (target DDL v4.0). Fixed gates per endpoint; the
 * service checks the permission again at the unit, the cycle state and the separation of duties.
 * The computation itself is a C-13 job (PAYROLL_COMPUTATION).
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/cycles/{period}")
public class CycleController {

    private static final String PERIOD = "^\\d{4}-(0[1-9]|1[0-2])$";

    public record OtpRequest(@Valid Otp otp) {}

    public record LockRequest(@Size(max = 500) @PrintableAscii String reason, @Valid Otp otp) {}

    /** SRS-15-28: why the lease must be force-released (the X-01 authority decides with OTP). */
    public record LeaseReleaseRequest(@NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    /**
     * SRS-15-30: the Checker's return. {@code orgUnitCode} names an office beneath the unit
     * (ORG_UNIT), {@code members} the members' EIS numbers (MEMBERS).
     */
    public record ReturnBody(
            @NotNull ReturnScope scope,
            @Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9-]{1,20}$") String orgUnitCode,
            @Size(max = CycleCorrections.MAX_MEMBERS)
                    List<@NotBlank @Pattern(regexp = "^[A-Za-z0-9]{1,20}$") String> members,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    /**
     * SRS-15-3: the transaction to undo (its kind is the register's {@code transaction_entity}, a
     * table name; the key fits the approval item's key with it) and why.
     */
    public record UndoBody(
            @NotBlank @Size(max = 50) @Pattern(regexp = "^[a-z][a-z0-9_]{1,49}$")
                    String transactionEntity,
            @NotBlank @Size(max = 49) @Pattern(regexp = "^[A-Za-z0-9|:_-]{1,49}$")
                    String transactionKey,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    /** The approval item an undo request opened. */
    public record UndoRequested(long approvalId) {}

    /** SRS-15-30 / SRS-15-31: why (a withdrawal, an un-freeze request). */
    public record ReasonBody(@NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final CycleService cycles;
    private final CycleCorrections corrections;
    private final CycleUndo undo;
    private final Paging paging;

    public CycleController(
            CurrentAccess contexts,
            CycleService cycles,
            CycleCorrections corrections,
            CycleUndo undo,
            Paging paging) {
        this.paging = paging;
        this.contexts = contexts;
        this.cycles = cycles;
        this.corrections = corrections;
        this.undo = undo;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('C-02','VIEW')")
    public ResponseEntity<CycleService.CycleView> cycle(
            @PathVariable @Pattern(regexp = PERIOD) String period) {
        return noStore(cycles.cycle(contexts.current(), YearMonth.parse(period)));
    }

    @GetMapping("/results/{resultId}")
    @PreAuthorize("@authz.gate('C-02','VIEW')")
    public ResponseEntity<Paged<CycleService.Line>> lines(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @PathVariable @Min(1) long resultId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        cycles.lines(contexts.current(), YearMonth.parse(period), resultId),
                        paging.resolve(page, size)));
    }

    /** SRS-15-23: the pre-run checks P1-P5 that keep Initialise refused until clear. */
    @GetMapping("/pre-run-checks")
    @PreAuthorize("@authz.gate('C-02','VIEW')")
    public ResponseEntity<List<PreRunChecks.Check>> preRunChecks(
            @PathVariable @Pattern(regexp = PERIOD) String period) {
        return noStore(cycles.preRunChecks(contexts.current(), YearMonth.parse(period)));
    }

    @PostMapping("/initiate")
    @PreAuthorize("@authz.gate('C-02','CREATE')")
    public ResponseEntity<CycleService.CycleView> initiate(
            @PathVariable @Pattern(regexp = PERIOD) String period) {
        return noStore(cycles.initiate(contexts.current(), YearMonth.parse(period)));
    }

    @PostMapping("/freeze")
    @PreAuthorize("@authz.gate('C-02','SUBMIT')")
    public ResponseEntity<CycleService.CycleView> freeze(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @Valid @RequestBody(required = false) OtpRequest body) {
        return noStore(cycles.freeze(contexts.current(), YearMonth.parse(period), otp(body)));
    }

    @PostMapping("/verify")
    @PreAuthorize("@authz.gate('C-02','APPROVE')")
    public ResponseEntity<CycleService.CycleView> verify(
            @PathVariable @Pattern(regexp = PERIOD) String period) {
        return noStore(cycles.verify(contexts.current(), YearMonth.parse(period)));
    }

    /**
     * SRS-15-28: asks for the force-release of the lease held on the cycle (202: the release
     * happens when the authority configured in X-01 approves it, with OTP).
     */
    @PostMapping("/lease-releases")
    @PreAuthorize("@authz.gate('C-02','LEASE_RELEASE')")
    public ResponseEntity<CycleService.CycleView> requestLeaseRelease(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @Valid @RequestBody LeaseReleaseRequest body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(
                        cycles.requestLeaseRelease(
                                contexts.current(), YearMonth.parse(period), body.reason()));
    }

    /**
     * SRS-15-30: the Payroll Checker returns the computed or verified cycle with a reason and a
     * scope; the cycle is FROZEN again and needs a fresh computation.
     */
    @PostMapping("/returns")
    @PreAuthorize("@authz.gate('C-02','RETURN')")
    public ResponseEntity<CycleService.CycleView> returnCycle(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @Valid @RequestBody ReturnBody body) {
        YearMonth month = YearMonth.parse(period);
        corrections.returnCycle(
                contexts.current(),
                month,
                new CycleCorrections.ReturnRequest(
                        body.scope(), body.orgUnitCode(), body.members(), body.reason()));
        return noStore(cycles.cycle(contexts.current(), month));
    }

    /** SRS-15-30: withdraws a return raised in error, while nothing was done on it. */
    @PostMapping("/returns/{returnId}/withdraw")
    @PreAuthorize("@authz.gate('C-02','RETURN')")
    public ResponseEntity<CycleService.CycleView> withdrawReturn(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @PathVariable @Min(1) long returnId,
            @Valid @RequestBody ReasonBody body) {
        YearMonth month = YearMonth.parse(period);
        corrections.withdrawReturn(contexts.current(), month, returnId, body.reason());
        return noStore(cycles.cycle(contexts.current(), month));
    }

    /**
     * SRS-15-31: the Payroll Maker asks for the un-freeze before lock (202: it happens when route
     * C02_UNFREEZE approves it, the last level with OTP).
     */
    @PostMapping("/unfreeze-requests")
    @PreAuthorize("@authz.gate('C-02','UNFREEZE')")
    public ResponseEntity<CycleService.CycleView> requestUnfreeze(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @Valid @RequestBody ReasonBody body) {
        YearMonth month = YearMonth.parse(period);
        corrections.requestUnfreeze(contexts.current(), month, body.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(cycles.cycle(contexts.current(), month));
    }

    /**
     * SRS-15-3 / SRS-15-10: the Payroll Maker requests the controlled undo of a transaction of the
     * cycle before EBS posting (202: route C02_UNDO decides, the Administrator with OTP).
     */
    @PostMapping("/undo-requests")
    @PreAuthorize("@authz.gate('C-02','UNDO')")
    public ResponseEntity<UndoRequested> requestUndo(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @Valid @RequestBody UndoBody body) {
        long id =
                undo.requestUndo(
                        contexts.current(),
                        YearMonth.parse(period),
                        body.transactionEntity(),
                        body.transactionKey(),
                        body.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(new UndoRequested(id));
    }

    @PostMapping("/lock")
    @PreAuthorize("@authz.gate('C-02','LOCK')")
    public ResponseEntity<CycleService.CycleView> lock(
            @PathVariable @Pattern(regexp = PERIOD) String period,
            @Valid @RequestBody(required = false) LockRequest body) {
        return noStore(
                cycles.requestLock(
                        contexts.current(),
                        YearMonth.parse(period),
                        lockReason(body),
                        lockOtp(body)));
    }

    /** The OTP of a freeze, whose body is optional. */
    static OtpProof otp(OtpRequest body) {
        return Otp.proof(body == null ? null : body.otp());
    }

    /** The OTP of a lock request, whose body is optional. */
    static OtpProof lockOtp(LockRequest body) {
        return Otp.proof(body == null ? null : body.otp());
    }

    /** The reason of a lock request: optional, "Cycle lock" when none is given. */
    static String lockReason(LockRequest body) {
        return body == null || body.reason() == null || body.reason().isBlank()
                ? "Cycle lock"
                : body.reason();
    }
}
