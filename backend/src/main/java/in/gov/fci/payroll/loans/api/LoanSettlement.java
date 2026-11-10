package in.gov.fci.payroll.loans.api;

import in.gov.fci.payroll.audit.api.AuditService;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The loan-ledger side of a posted Full &amp; Final Settlement (SRS-10-7, UC-06 postcondition SRS
 * l.2917, BR-FNF-006, BR-LOAN-006): "No loan or advance balance stays outstanding on the loan
 * ledger after separation. Each is closed as recovered at settlement, against the recorded source."
 * Each loan or advance the settlement itemised is set to {@code SETTLED_IN_FNF} with nothing
 * outstanding, an {@code FNF_SETTLEMENT} loan event records the amount recovered, and the change is
 * audited with the settlement's approval - in the posting's own transaction. A loan that is no
 * longer open (closed, foreclosed or already settled) is left as it is.
 */
@Component
public class LoanSettlement {

    /** One itemised account: the loan and the amount the settlement recovered for it. */
    public record Recovered(long loanId, BigDecimal amount) {}

    static final String ENTITY = "loan_header";
    static final String SCREEN = "I-04";
    private static final List<String> OPEN = List.of("ACTIVE", "STOPPED", "RESCHEDULED");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public LoanSettlement(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * Closes each open account of {@code recovered} as settled at separation.
     *
     * @param caseReference the settlement case, named in the event reason and the audit
     * @return the number of accounts closed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int settle(
            List<Recovered> recovered,
            LocalDate postedOn,
            String caseReference,
            String actor,
            long approvalId) {
        int closed = 0;
        for (Recovered r : recovered) {
            List<Map<String, Object>> found =
                    jdbc.queryForList(
                            "SELECT loan_status, loan_number FROM loan_header WHERE loan_id = ?",
                            r.loanId());
            if (found.isEmpty() || !OPEN.contains((String) found.getFirst().get("LOAN_STATUS"))) {
                continue;
            }
            String before = (String) found.getFirst().get("LOAN_STATUS");
            String number = (String) found.getFirst().get("LOAN_NUMBER");
            String reason = "Recovered at settlement " + caseReference;
            jdbc.update(
                    "UPDATE loan_header SET loan_status = 'SETTLED_IN_FNF', principal_outstanding"
                            + " = 0, interest_outstanding = 0, row_version = row_version + 1 WHERE"
                            + " loan_id = ?",
                    r.loanId());
            jdbc.update(
                    "INSERT INTO loan_event (loan_id, event_type, event_date, settlement_amount,"
                            + " reason, approval_id) VALUES (?, 'FNF_SETTLEMENT', ?, ?, ?, ?)",
                    r.loanId(),
                    Date.valueOf(postedOn),
                    r.amount(),
                    reason,
                    approvalId);
            audit.record(
                    actor,
                    AuditService.Entry.change(
                                    ENTITY,
                                    number == null ? String.valueOf(r.loanId()) : number,
                                    "loan_status",
                                    before,
                                    "SETTLED_IN_FNF",
                                    reason)
                            .approval(approvalId)
                            .module(SCREEN));
            closed++;
        }
        return closed;
    }
}
