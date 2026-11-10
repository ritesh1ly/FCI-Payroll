package in.gov.fci.payroll.loans.orchestration;

import in.gov.fci.payroll.loans.api.LoanInstalmentChanged;
import in.gov.fci.payroll.loans.business.LoanRules.Terms;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Payroll Checker's approval of an I-04 foreclosure or reschedule (plan 09 phase 09e; SRS-12-4,
 * SRS-12-10, SRS-12-16, SRS-12-21, SRS-12-22): it takes effect in the decision's transaction
 * ({@link LoanChanges}), with the OTP challenge of a foreclosure on its event, and the member is
 * told of the new instalment ({@link LoanInstalmentChanged}). A loan no longer open (closed
 * meanwhile) refuses the decision.
 */
@Component
public class LoanChangeEffect {

    private final JdbcTemplate jdbc;
    private final LoanChanges changes;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;

    public LoanChangeEffect(
            JdbcTemplate jdbc,
            LoanChanges changes,
            ObjectMapper json,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.changes = changes;
        this.json = json;
        this.events = events;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(LoanChangeService.ENTITY) || !decision.approved()) {
            return;
        }
        JsonNode p;
        try {
            p = json.readTree(decision.payload());
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unreadable loan change " + decision.approvalId(), ex);
        }
        Map<String, Object> loan = changes.openLoan(p.path("loanId").asLong());
        if (loan == null) {
            throw new IllegalStateException(
                    "Loan "
                            + p.path("loanId").asLong()
                            + " is no longer open; the change cannot be applied");
        }
        String maker =
                jdbc.queryForObject(
                        "SELECT submitted_by FROM approval_queue WHERE approval_id = ?",
                        String.class,
                        decision.approvalId());
        long employeeId = ((Number) loan.get("EMPLOYEE_ID")).longValue();
        String number = (String) loan.get("LOAN_NUMBER");
        if ("FORECLOSURE".equals(p.path("kind").asString())) {
            changes.foreclose(
                    loan,
                    "I-04 order",
                    null,
                    p.path("attachmentId").asLong(),
                    decision.otpChallengeId(),
                    decision.approvalId(),
                    maker,
                    decision.checkerLoginId(),
                    decision.decidedAt());
            events.publishEvent(
                    new LoanInstalmentChanged(
                            employeeId, number, "FORECLOSURE", BigDecimal.ZERO, null));
        } else {
            Terms t =
                    LoanChanges.remaining(
                            loan,
                            p.path("principalInstalments").asInt(),
                            LoanSanctionEffect.decimal(p, "instalment"),
                            p.path("interestInstalments").asInt(),
                            LoanSanctionEffect.decimal(p, "interestInstalment"),
                            p.path("startYymm").asString());
            changes.reschedule(
                    loan,
                    t,
                    employeeId,
                    "I-04 signed request",
                    null,
                    p.path("attachmentId").asLong(),
                    decision.approvalId(),
                    maker,
                    decision.checkerLoginId(),
                    decision.decidedAt());
            events.publishEvent(
                    new LoanInstalmentChanged(
                            employeeId, number, "RESCHEDULE", t.instalment(), t.startYymm()));
        }
    }
}
