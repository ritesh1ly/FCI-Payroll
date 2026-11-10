package in.gov.fci.payroll.allowance.orchestration;

import in.gov.fci.payroll.allowance.business.AllowanceErrorCode;
import in.gov.fci.payroll.allowance.business.ClaimRules;
import in.gov.fci.payroll.allowance.business.model.ReimbursementClaim;
import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Employee claims received from HRMS through I-21 (plan 10 phase 10c, D10-8; SRS-11-1, SRS-11-4,
 * SRS-11-5, SRS-11-6, SRS-11-9, SRS-11-11): the I-21 value is the HRMS claim record (JSON: {@code
 * claimType, periodFrom, periodTo, amount, taxable, schemeType}). Checked at intake, so a task is
 * never opened on a claim that could not be paid; on approval the claim is written APPROVED with
 * its entitlement and payable; a rejected task writes nothing (the case returns to HRMS with the
 * reason, I-03 use case). A claim type without a pay code is held and raised on X-05 (D10-5).
 */
@Component
public class EmployeeClaimEffects implements HrmsTaskEffects {

    static final String FIELD = "REIMBURSEMENT_CLAIM";
    static final String PAY_CODE_MISSING = "CLAIM_PAY_CODE_MISSING";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,29}$");
    private static final Pattern SCHEME = Pattern.compile("^[\\x20-\\x7E]{0,40}$");

    private final AllowanceRepositories.Claims claims;
    private final ClaimCatalogue catalogue;
    private final ExceptionWorklist worklist;
    private final AuditService audit;
    private final ObjectMapper json;

    public EmployeeClaimEffects(
            AllowanceRepositories.Claims claims,
            ClaimCatalogue catalogue,
            ExceptionWorklist worklist,
            AuditService audit,
            ObjectMapper json) {
        this.claims = claims;
        this.catalogue = catalogue;
        this.worklist = worklist;
        this.audit = audit;
        this.json = json;
    }

    /** The HRMS claim record, checked. */
    record Record(
            String claimType,
            ClaimRules.Period period,
            BigDecimal amount,
            Boolean taxable,
            String schemeType) {}

    @Override
    public Set<String> fields() {
        return Set.of(FIELD);
    }

    @Override
    public void check(String fieldCode, long employeeId, String newValue) {
        Record r = record(newValue);
        adjudication(employeeId, r);
    }

    @Override
    public void apply(Task task, String checker, long approvalId, Instant decidedAt) {
        Record r = record(task.newValue());
        Adjudicated a = adjudication(task.employeeId(), r);
        ReimbursementClaim claim =
                claims.save(
                        ReimbursementClaim.fromHrms(
                                task.employeeId(),
                                r.claimType(),
                                r.period(),
                                r.amount(),
                                a.norm().id(),
                                a.result(),
                                task.hrmsRequestId(),
                                task.taskId(),
                                r.taxable(),
                                r.schemeType(),
                                approvalId));
        audit.record(
                checker,
                AuditService.Entry.change(
                                LabourClaimService.ENTITY,
                                String.valueOf(claim.getId()),
                                "claim_status",
                                null,
                                claim.getStatus(),
                                "Employee claim from HRMS approved on I-21 (task "
                                        + task.taskNo()
                                        + ")")
                        .approval(approvalId)
                        .module(LabourClaimService.SCREEN));
        LocalDate today = LocalDate.ofInstant(decidedAt, IST);
        Integer payCode =
                catalogue
                        .type(r.claimType(), today)
                        .map(ClaimCatalogue.ClaimType::payCode)
                        .orElse(null);
        if (!catalogue.payCodeActive(payCode, today)) {
            // D10-5: held, never paid under a guessed code; the member's salary still runs.
            worklist.raise(
                    checker,
                    new ExceptionWorklist.NewEntry(
                            null,
                            task.employeeId(),
                            LabourClaimService.SCREEN,
                            PAY_CODE_MISSING,
                            "WARNING",
                            null,
                            task.taskId(),
                            "reference_value",
                            "numeric_attribute",
                            "RULE",
                            "M-16",
                            "Claim "
                                    + claim.getId()
                                    + " ("
                                    + r.claimType()
                                    + ") held: its pay code is"
                                    + " not configured"));
        }
    }

    private record Adjudicated(ClaimCatalogue.Norm norm, ClaimRules.Adjudication result) {}

    /** The intake / approval checks (D10-8) and the adjudication of the claim. */
    private Adjudicated adjudication(long employeeId, Record r) {
        ClaimCatalogue.MemberFacts member = catalogue.member(employeeId);
        if (member.labour()) {
            throw invalid("a labour member's claim is entered on I-03, not received from HRMS");
        }
        boolean employeeType =
                catalogue.types("EMPLOYEE", r.period().from()).stream()
                        .anyMatch(t -> t.code().equals(r.claimType()));
        if (!employeeType) {
            throw invalid(
                    "the claim type " + r.claimType() + " is not an employee claim type (M-15)");
        }
        if (medical(r.claimType())
                && ClaimRules.retired(
                        member.employmentStatus(), member.retirementDate(), r.period())) {
            throw new FciException(
                    AllowanceErrorCode.RETIRED, new Object[] {member.eis()}, List.of(), null);
        }
        catalogue.requireNoOverlap(employeeId, r.claimType(), r.period(), null);
        ClaimCatalogue.Norm norm = catalogue.norm(r.claimType(), "EMPLOYEE", r.period().from());
        return new Adjudicated(
                norm,
                catalogue.adjudicate(
                        norm, employeeId, r.claimType(), r.period(), r.amount(), null));
    }

    /** D10-8: a medical claim type is one whose code starts with MEDICAL (SRS-11-4, SRS-11-5). */
    static boolean medical(String claimType) {
        return claimType.startsWith("MEDICAL");
    }

    private Record record(String value) {
        JsonNode v;
        try {
            v = json.readTree(value == null ? "" : value);
        } catch (JacksonException ex) {
            throw invalid("the HRMS claim record is not readable");
        }
        if (v == null || !v.isObject()) {
            throw invalid("the HRMS claim record is not readable");
        }
        String type = v.path("claimType").asString("");
        if (!CODE.matcher(type).matches()) {
            throw invalid("the claim type is a code of capital letters");
        }
        ClaimRules.Period period;
        BigDecimal amount;
        try {
            period =
                    new ClaimRules.Period(
                            LocalDate.parse(v.path("periodFrom").asString("")),
                            LocalDate.parse(v.path("periodTo").asString("")));
            amount = new BigDecimal(v.path("amount").asString(""));
        } catch (DateTimeParseException | NumberFormatException ex) {
            throw invalid("the claim period and amount are required (YYYY-MM-DD, a number)");
        }
        ClaimRules.defect(period, amount)
                .ifPresent(
                        why -> {
                            throw invalid(why);
                        });
        JsonNode taxable = v.path("taxable");
        String scheme = v.path("schemeType").asString("").strip();
        if (!SCHEME.matcher(scheme).matches()) {
            throw invalid("the scheme type is printable, at most 40 characters");
        }
        return new Record(
                type,
                period,
                amount,
                taxable.isBoolean() ? taxable.asBoolean() : null,
                scheme.isEmpty() ? null : scheme);
    }

    private static FciException invalid(String why) {
        return new FciException(AllowanceErrorCode.CLAIM, new Object[] {why}, List.of(), null);
    }
}
