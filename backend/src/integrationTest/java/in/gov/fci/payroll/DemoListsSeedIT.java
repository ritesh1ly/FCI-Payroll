package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The demo rows of seed V077_28 (UI-G3-DEMO, UI audit "no data after fetch"): each list of modules
 * 07-10 and 12 has a row for its demo user, in a state the workflow reaches, with its approval item
 * where the workflow writes one (Maker and Checker different).
 */
class DemoListsSeedIT extends ApiTestSupport {

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    void SRS_6_55_SRS_12_23_SRS_14_15_eachListOfModules07To12HasItsDemoRowsInAReachableState() {
        // I-04 CPF: one request at tier 1 and one forwarded by the unit (its tier-1 row).
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM pf_sanction WHERE sanction_order_ref IN"
                                        + " ('DEMO-CPF-ADV-889001', 'DEMO-CPF-PF-889001')"))
                .isEqualTo(2);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM cpf_sanction_tier t JOIN pf_sanction s ON"
                                    + " s.sanction_id = t.sanction_id WHERE s.sanction_order_ref ="
                                    + " 'DEMO-CPF-PF-889001' AND s.workflow_stage ="
                                    + " 'ZONAL_HQ_VALIDATION' AND t.tier_no = 1 AND t.tier_action ="
                                    + " 'FORWARDED'"))
                .isOne();
        // I-04 Loans: the HRMS loan with its ORIGINAL schedule adding up to the sanction.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM loan_header h WHERE h.loan_number ="
                                    + " 'DEMO-LN-889001' AND h.sanctioned_amount = (SELECT"
                                    + " SUM(r.principal_amount) FROM loan_recovery_schedule r WHERE"
                                    + " r.loan_id = h.loan_id)"))
                .isOne();
        // I-06: the order and the payment, each approved by another user than the Maker.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM penalty_order o JOIN approval_queue q ON"
                                    + " q.approval_id = o.approval_id WHERE o.penalty_ref ="
                                    + " 'DEMO-PEN-0001' AND q.status = 'APPROVED' AND"
                                    + " q.submitted_by <> q.decided_by AND o.total_recovery_amount"
                                    + " = (SELECT SUM(s.scheduled_amount) FROM"
                                    + " penalty_recovery_schedule s WHERE s.order_id ="
                                    + " o.order_id)"))
                .isOne();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM over_and_above_payment p JOIN approval_queue"
                                    + " q ON q.approval_id = p.approval_id WHERE q.entity_key ="
                                    + " 'DEMO-OA-889001' AND p.payment_status = 'PAID' AND q.status"
                                    + " = 'APPROVED'"))
                .isOne();
        // I-16, E-02, M-19.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM cafeteria_election_line l JOIN"
                                    + " cafeteria_election c ON c.election_id = l.election_id WHERE"
                                    + " c.hrms_request_id = 'DEMO-HRMS-CAF-889001'"))
                .isEqualTo(2);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM vpf_change_request r JOIN approval_queue q ON"
                                        + " q.approval_id = r.approval_id WHERE q.entity_key ="
                                        + " 'DEMO-VPF-104882' AND r.request_status = 'REJECTED' AND"
                                        + " q.status = 'REJECTED'"))
                .isOne();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM pf_interest_rate r JOIN approval_queue q ON"
                                        + " q.approval_id = r.approval_id WHERE q.entity_key ="
                                        + " 'DEMO-RATES-PREVIOUS-YEAR' AND r.is_declared = 'Y'"))
                .isEqualTo(2);
        // E-02 opens on the requests waiting for the Maker: one of 889001, on its ledger.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM vpf_change_request r JOIN employee e ON"
                                    + " e.employee_id = r.employee_id WHERE e.emp_num = '889001'"
                                    + " AND r.request_status = 'SUBMITTED' AND r.pf_header_id IS"
                                    + " NOT NULL"))
                .isOne();
        // E-01 CPF statement: the closing balance carries the postings of the year.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM employee_pf_header h JOIN employee e ON"
                                    + " e.employee_id = h.employee_id WHERE e.emp_num = '870005'"
                                    + " AND EXISTS (SELECT 1 FROM employee_pf_detail d WHERE"
                                    + " d.pf_header_id = h.pf_header_id) AND"
                                    + " h.closing_balance_employee = h.opening_balance_employee +"
                                    + " (SELECT SUM(d.employee_contribution) FROM"
                                    + " employee_pf_detail d WHERE d.pf_header_id ="
                                    + " h.pf_header_id)"))
                .as("postings exist once the year has a month two months back (June to March)")
                .isEqualTo(
                        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).getMonthValue()
                                                >= 4
                                        && java.time.LocalDate.now(
                                                                java.time.ZoneId.of("Asia/Kolkata"))
                                                        .getMonthValue()
                                                < 6
                                ? 0
                                : 1);
    }
}
