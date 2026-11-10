package in.gov.fci.payroll.loans.orchestration;

import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.loans.api.LoanRequestReturned;
import in.gov.fci.payroll.loans.business.LoanRules;
import in.gov.fci.payroll.loans.business.LoanRules.Terms;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Payroll Checker's approval of an I-04 loan sanction (plan 09 phase 09b; SRS-12-1, SRS-12-6,
 * SRS-12-17, SRS-12-20; D09-8): in the decision's transaction the loan is written, ACTIVE, with its
 * ORIGINAL schedule ({@link LoanWriter}). A returned or rejected request writes nothing: it stays
 * in its approval item with the reason (SRS-12-7). A returned one is told to the Payroll Makers of
 * the member's unit with the reason ({@link LoanRequestReturned}; US-GAP-198, OD-56).
 */
@Component
public class LoanSanctionEffect {

    private final JdbcTemplate jdbc;
    private final LoanWriter writer;
    private final ObjectMapper json;
    private final EmployeeDirectory employees;
    private final OrgDirectory org;
    private final ApplicationEventPublisher events;

    public LoanSanctionEffect(
            JdbcTemplate jdbc,
            LoanWriter writer,
            ObjectMapper json,
            EmployeeDirectory employees,
            OrgDirectory org,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.writer = writer;
        this.json = json;
        this.employees = employees;
        this.org = org;
        this.events = events;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(LoanSanctionService.ENTITY)) {
            return;
        }
        if (decision.outcome() == ApprovalStatus.RETURNED) {
            returned(decision);
            return;
        }
        if (!decision.approved()) {
            return;
        }
        JsonNode p = payload(decision);
        Terms t =
                new Terms(
                        decimal(p, "sanctioned"),
                        p.path("principalInstalments").asInt(),
                        decimal(p, "instalment"),
                        decimal(p, "firstInstalment"),
                        decimal(p, "interestAmount"),
                        p.path("interestInstalments").asInt(),
                        decimal(p, "interestInstalment"),
                        p.path("startYymm").asString());
        String maker =
                jdbc.queryForObject(
                        "SELECT submitted_by FROM approval_queue WHERE approval_id = ?",
                        String.class,
                        decision.approvalId());
        writer.create(
                p.path("employeeId").asLong(),
                p.path("loanTypeId").asLong(),
                p.path("loanType").asString(),
                t,
                LoanRules.schedule(t),
                LocalDate.ofInstant(decision.decidedAt(), ZoneId.of("Asia/Kolkata")),
                LoanWriter.Source.LABOUR_ENT,
                null,
                null,
                decision.approvalId(),
                maker,
                decision.checkerLoginId(),
                decision.decidedAt());
    }

    /**
     * SRS-12-7 (US-GAP-198): the Payroll Makers of the member's unit are notified of the return
     * with its reason; nothing is written to the ledger.
     */
    private void returned(ApprovalDecided decision) {
        JsonNode p = payload(decision);
        long employeeId = p.path("employeeId").asLong();
        employees
                .locationOf(employeeId)
                .flatMap(org::cycleUnitOf)
                .map(OrgLocation::getId)
                .ifPresent(
                        unit ->
                                events.publishEvent(
                                        new LoanRequestReturned(
                                                unit,
                                                decision.approvalId(),
                                                p.path("eis").asString(),
                                                p.path("loanType").asString(),
                                                decision.reason(),
                                                decision.checkerLoginId())));
    }

    private JsonNode payload(ApprovalDecided decision) {
        try {
            return json.readTree(decision.payload());
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "Unreadable loan sanction " + decision.approvalId(), ex);
        }
    }

    static BigDecimal decimal(JsonNode p, String field) {
        JsonNode v = p.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.decimalValue();
    }
}
