package in.gov.fci.payroll.loans.orchestration;

import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import in.gov.fci.payroll.loans.business.LoanRules;
import in.gov.fci.payroll.loans.business.LoanRules.Terms;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Employee loans and advances received from HRMS (plan 09 phase 09c; SRS-12-17, SRS-12-19,
 * SRS-12-4, SRS-12-16, SRS-12-21; D09-5, D09-13, D09-14): a sanction, foreclosure or reschedule
 * arrives as an I-21 task (fields LOAN_SANCTION, LOAN_FORECLOSURE, LOAN_RESCHEDULE) whose value is
 * the HRMS loan record; it reaches the loan ledger only when the task is approved. The value is
 * checked at intake so a task never opens on a record that could not be applied.
 *
 * <ul>
 *   <li>LOAN_SANCTION {@code {loanType, sanctioned, principalInstalments, instalment,
 *       firstInstalment?, interestAmount?, interestInstalments?, interestInstalment?, startYymm}}:
 *       the loan with its ORIGINAL schedule (source HRMS);
 *   <li>LOAN_FORECLOSURE {@code {loanReference}}: the outstanding principal and interest cleared in
 *       one event, approved with the LOAN_FORECLOSURE OTP (route I21_SUBMIT_LOAN_FORECLOSURE); the
 *       payment receipt is HRMS's (D09-14);
 *   <li>LOAN_RESCHEDULE {@code {loanReference, principalInstalments, instalment,
 *       interestInstalments?, interestInstalment?, startYymm}}: a new schedule on the balance
 *       remaining at approval.
 * </ul>
 *
 * <p>{@code loanReference} is the HRMS request id of the sanction. OPEN-QUESTION (PENDING
 * API-09-2): the HRMS loan record's layout is not specified; this one is designed here.
 * OPEN-QUESTION (PENDING 09-backdated): a schedule starting in a month already locked moves to the
 * next open month with the same instalments (D09-13); arrears of a back-dated change are module
 * 13's.
 *
 * <p>US-GAP-199 (use case precondition l.2684, "The requester is an active, payable member"): a
 * sanction for a member who is not in service (separated, or any other status not payable) is
 * refused at intake (NOT_PAYABLE), as on I-04; a foreclosure or reschedule of an open loan is not.
 */
@Component
public class LoanHrmsEffects implements HrmsTaskEffects {

    static final String SANCTION = "LOAN_SANCTION";
    static final String FORECLOSURE = "LOAN_FORECLOSURE";
    static final String RESCHEDULE = "LOAN_RESCHEDULE";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final LoanWriter writer;
    private final LoanChanges changes;
    private final ObjectMapper json;
    private final EmployeeDirectory employees;

    /** SRS-12-10: where the member notice goes (none in a slice test without the event bus). */
    private org.springframework.context.ApplicationEventPublisher events = event -> {};

    public LoanHrmsEffects(
            JdbcTemplate jdbc,
            LoanWriter writer,
            LoanChanges changes,
            ObjectMapper json,
            EmployeeDirectory employees) {
        this.jdbc = jdbc;
        this.writer = writer;
        this.changes = changes;
        this.json = json;
        this.employees = employees;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public Set<String> fields() {
        return Set.of(SANCTION, FORECLOSURE, RESCHEDULE);
    }

    @Override
    public Optional<String> route(String fieldCode) {
        return FORECLOSURE.equals(fieldCode)
                ? Optional.of("SUBMIT_LOAN_FORECLOSURE")
                : Optional.empty();
    }

    @Override
    public void check(String fieldCode, long employeeId, String newValue) {
        JsonNode v = read(newValue);
        switch (fieldCode) {
            case SANCTION -> {
                String status =
                        employees
                                .member(employeeId)
                                .map(EmployeeDirectory.Member::employmentStatus)
                                .orElse(null);
                if (status == null || !LoanSanctionService.PAYABLE.contains(status)) {
                    throw invalid("NOT_PAYABLE");
                }
                Map<String, Object> type = type(v.path("loanType").asString());
                Terms t = sanction(v);
                LoanRules.defect(t)
                        .or(
                                () ->
                                        LoanRules.interestDefect(
                                                "Y".equals(type.get("IS_INTEREST_BEARING")), t))
                        .ifPresent(
                                d -> {
                                    throw invalid(d);
                                });
            }
            case FORECLOSURE -> loan(employeeId, v);
            case RESCHEDULE -> {
                Map<String, Object> loan = loan(employeeId, v);
                LoanRules.defect(reschedule(v, loan))
                        .ifPresent(
                                d -> {
                                    throw invalid(d);
                                });
            }
            default -> throw invalid("FIELD");
        }
    }

    @Override
    public void apply(Task task, String checker, long approvalId, Instant decidedAt) {
        JsonNode v = read(task.newValue());
        String maker =
                jdbc.queryForObject(
                        "SELECT submitted_by FROM approval_queue WHERE approval_id = ?",
                        String.class,
                        approvalId);
        LocalDate today = LocalDate.ofInstant(decidedAt, IST);
        switch (task.fieldCode()) {
            case SANCTION -> {
                Map<String, Object> type = type(v.path("loanType").asString());
                Terms t = sanction(v);
                writer.create(
                        task.employeeId(),
                        ((Number) type.get("LOAN_TYPE_ID")).longValue(),
                        (String) type.get("LOAN_TYPE_CODE"),
                        t,
                        changes.open(task.employeeId(), LoanRules.schedule(t), today),
                        today,
                        LoanWriter.Source.HRMS,
                        task.hrmsRequestId(),
                        task.taskId(),
                        approvalId,
                        maker,
                        checker,
                        decidedAt);
            }
            case FORECLOSURE -> {
                Map<String, Object> loan = loan(task.employeeId(), v);
                changes.foreclose(
                        loan,
                        "HRMS " + task.taskNo(),
                        task.hrmsRequestId(),
                        null,
                        null,
                        approvalId,
                        maker,
                        checker,
                        decidedAt);
                // SRS-12-10 (US-GAP-30): the member is told, as for an I-04 foreclosure.
                events.publishEvent(
                        new in.gov.fci.payroll.loans.api.LoanInstalmentChanged(
                                task.employeeId(),
                                (String) loan.get("LOAN_NUMBER"),
                                "FORECLOSURE",
                                BigDecimal.ZERO,
                                null));
            }
            case RESCHEDULE -> {
                Map<String, Object> loan = loan(task.employeeId(), v);
                Terms terms = reschedule(v, loan);
                changes.reschedule(
                        loan,
                        terms,
                        task.employeeId(),
                        "HRMS " + task.taskNo(),
                        task.hrmsRequestId(),
                        null,
                        approvalId,
                        maker,
                        checker,
                        decidedAt);
                events.publishEvent(
                        new in.gov.fci.payroll.loans.api.LoanInstalmentChanged(
                                task.employeeId(),
                                (String) loan.get("LOAN_NUMBER"),
                                "RESCHEDULE",
                                terms.instalment(),
                                terms.startYymm()));
            }
            default -> throw invalid("FIELD");
        }
    }

    // ---------------------------------------------------------------- the record

    private Terms sanction(JsonNode v) {
        return new Terms(
                decimal(v, "sanctioned"),
                v.path("principalInstalments").asInt(),
                decimal(v, "instalment"),
                decimal(v, "firstInstalment"),
                decimal(v, "interestAmount"),
                v.path("interestInstalments").asInt(),
                decimal(v, "interestInstalment"),
                v.path("startYymm").asString());
    }

    /** SRS-12-4: the new schedule on the balance remaining (principal and interest). */
    private static Terms reschedule(JsonNode v, Map<String, Object> loan) {
        return LoanChanges.remaining(
                loan,
                v.path("principalInstalments").asInt(),
                decimal(v, "instalment"),
                v.path("interestInstalments").asInt(),
                decimal(v, "interestInstalment"),
                v.path("startYymm").asString());
    }

    private Map<String, Object> type(String code) {
        return jdbc
                .queryForList(
                        "SELECT loan_type_id, loan_type_code, is_interest_bearing FROM"
                            + " loan_type_master WHERE loan_type_code = ? AND payroll_type_scope ="
                            + " 'STAFF' AND effective_from <= ? AND (effective_to IS NULL OR"
                            + " effective_to >= ?)",
                        code,
                        java.sql.Date.valueOf(java.time.LocalDate.now(IST)),
                        java.sql.Date.valueOf(java.time.LocalDate.now(IST)))
                .stream()
                .findFirst()
                .orElseThrow(() -> invalid("TYPE"));
    }

    /** The member's open loan the HRMS record refers to (by the sanction's HRMS request id). */
    private Map<String, Object> loan(long employeeId, JsonNode v) {
        String ref = v.path("loanReference").asString();
        return jdbc
                .queryForList(
                        "SELECT loan_id, loan_number, loan_status, principal_outstanding,"
                                + " interest_outstanding FROM loan_header WHERE employee_id = ? AND"
                                + " hrms_request_id = ? AND loan_status IN "
                                + LoanSanctionService.OPEN_STATUSES,
                        employeeId,
                        ref)
                .stream()
                .findFirst()
                .orElseThrow(() -> invalid("UNKNOWN_LOAN"));
    }

    private JsonNode read(String value) {
        try {
            JsonNode v = json.readTree(value == null ? "" : value);
            if (v == null || !v.isObject()) {
                throw invalid("NOT_A_RECORD");
            }
            return v;
        } catch (JacksonException notJson) {
            throw invalid("NOT_A_RECORD");
        }
    }

    private static BigDecimal decimal(JsonNode v, String field) {
        JsonNode n = v.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.decimalValue();
    }

    private static FciException invalid(String defect) {
        return new FciException(ApiErrorCode.LOAN_SANCTION, new Object[] {defect}, List.of(), null);
    }
}
