package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.loans.api.LoanSettlement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SRS-10-7, UC-06 postcondition (SRS l.2917), BR-FNF-006 (W11-08): posting a settlement closes each
 * itemised open loan in the database - SETTLED_IN_FNF, nothing outstanding, row_version bumped, an
 * FNF_SETTLEMENT loan event with the amount and the approval, an audit entry - in the posting's
 * transaction. No loans closes nothing; a closed loan is left alone; a second posting of the same
 * loans (a concurrent or repeated release) changes nothing more. The member is the demo record
 * 119903 (seed V077_30), whose loans no other suite reads.
 */
class LoanSettlementIT extends ApiTestSupport {

    @Autowired private LoanSettlement settlement;
    @Autowired private PlatformTransactionManager transactions;

    private long loan(String number, String status) {
        jdbc.update(
                "INSERT INTO loan_header (loan_number, employee_id, loan_type_id,"
                    + " sanctioned_amount, sanction_date, instalment_amount, loan_status,"
                    + " principal_outstanding, interest_outstanding) SELECT ?, e.employee_id,"
                    + " (SELECT MIN(loan_type_id) FROM loan_type_master), 50000, DATE '2025-04-01',"
                    + " 2000, ?, 12000, 300 FROM employee e WHERE e.emp_num = '119903' AND NOT"
                    + " EXISTS (SELECT 1 FROM loan_header x WHERE x.loan_number = ?)",
                number,
                status,
                number);
        return jdbc.queryForObject(
                "SELECT loan_id FROM loan_header WHERE loan_number = ?", Long.class, number);
    }

    private int settle(List<LoanSettlement.Recovered> loans, String reference) {
        Integer n =
                new TransactionTemplate(transactions)
                        .execute(
                                t ->
                                        settlement.settle(
                                                loans,
                                                LocalDate.of(2026, 10, 31),
                                                reference,
                                                "ddo.ropb@it",
                                                jdbc.queryForObject(
                                                        "SELECT MIN(approval_id) FROM"
                                                                + " approval_queue",
                                                        Long.class)));
        return n == null ? 0 : n;
    }

    @Test
    void SRS_10_7_postingClosesTheItemisedLoansOnTheLedgerWithItsEventAndAudit() {
        String run = Long.toString(System.nanoTime(), 36).toUpperCase();
        long open = loan("IT-FNF-" + run + "-A", "ACTIVE");
        long closed = loan("IT-FNF-" + run + "-C", "CLOSED");
        Map<String, Object> before =
                jdbc.queryForMap("SELECT row_version FROM loan_header WHERE loan_id = ?", open);

        assertThat(settle(List.of(), "FNF-IT-NONE")).as("no loans: nothing closed").isZero();
        int n =
                settle(
                        List.of(
                                new LoanSettlement.Recovered(open, new BigDecimal("12300")),
                                new LoanSettlement.Recovered(closed, BigDecimal.TEN)),
                        "FNF-IT-" + run);
        assertThat(n).as("the closed loan is left alone").isOne();

        Map<String, Object> after =
                jdbc.queryForMap(
                        "SELECT loan_status, principal_outstanding, interest_outstanding,"
                                + " row_version FROM loan_header WHERE loan_id = ?",
                        open);
        assertThat(after.get("LOAN_STATUS")).isEqualTo("SETTLED_IN_FNF");
        assertThat((BigDecimal) after.get("PRINCIPAL_OUTSTANDING")).isZero();
        assertThat((BigDecimal) after.get("INTEREST_OUTSTANDING")).isZero();
        assertThat(((Number) after.get("ROW_VERSION")).longValue())
                .isEqualTo(((Number) before.get("ROW_VERSION")).longValue() + 1);
        Map<String, Object> event =
                jdbc.queryForMap(
                        "SELECT event_type, settlement_amount, reason, approval_id FROM loan_event"
                                + " WHERE loan_id = ?",
                        open);
        assertThat(event.get("EVENT_TYPE")).isEqualTo("FNF_SETTLEMENT");
        assertThat((BigDecimal) event.get("SETTLEMENT_AMOUNT")).isEqualByComparingTo("12300");
        assertThat((String) event.get("REASON")).contains("FNF-IT-" + run);
        assertThat(event.get("APPROVAL_ID")).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'loan_header' AND entity_key = ? AND new_value ="
                                        + " 'SETTLED_IN_FNF'",
                                Integer.class,
                                "IT-FNF-" + run + "-A"))
                .isOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT loan_status FROM loan_header WHERE loan_id = ?",
                                String.class,
                                closed))
                .isEqualTo("CLOSED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM loan_event WHERE loan_id = ?",
                                Integer.class,
                                closed))
                .isZero();

        // A second (concurrent or repeated) posting of the same loans changes nothing more.
        assertThat(
                        settle(
                                List.of(
                                        new LoanSettlement.Recovered(
                                                open, new BigDecimal("12300"))),
                                "FNF-IT-" + run))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM loan_event WHERE loan_id = ?",
                                Integer.class,
                                open))
                .isOne();
    }

    @Test
    void SRS_10_7_theLedgerIsClosedOnlyInsideThePostingsTransaction() {
        assertThatThrownBy(
                        () ->
                                settlement.settle(
                                        List.of(), LocalDate.of(2026, 10, 31), "FNF-X", "x", 1L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
