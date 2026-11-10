package in.gov.fci.payroll.loans.api;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The loans and advances clearance of a Full &amp; Final Settlement (plan 11 phase 11b; SRS-10-27,
 * SRS-10-7, BR-FNF-006): every account of the member with a balance outstanding, principal and
 * interest, for full recovery at settlement (no carry-forward). OPEN-QUESTION (plan 11 D11-8): the
 * loan type master holds no loan / advance category; an interest-bearing type is reported as a loan
 * and an interest-free one as an advance (festival and similar advances are interest-free).
 */
@Component
public class LoanClearance {

    /**
     * One outstanding account.
     *
     * @param kind LOAN or ADVANCE (DDL {@code fs_recovery_detail.recovery_type})
     * @param reference the loan number, else the loan type and sanction date
     */
    public record Outstanding(
            long loanId, String kind, String typeCode, String reference, BigDecimal amount) {}

    private final JdbcTemplate jdbc;

    public LoanClearance(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The member's accounts still owing (status ACTIVE, STOPPED or RESCHEDULED, a balance due). */
    @Transactional(readOnly = true)
    public List<Outstanding> outstanding(long employeeId) {
        return jdbc.query(
                "SELECT h.loan_id, t.loan_type_code, t.is_interest_bearing, h.loan_number,"
                    + " h.sanction_date, COALESCE(h.principal_outstanding, 0) +"
                    + " COALESCE(h.interest_outstanding, 0) AS owed FROM loan_header h JOIN"
                    + " loan_type_master t ON t.loan_type_id = h.loan_type_id WHERE h.employee_id ="
                    + " ? AND h.loan_status IN ('ACTIVE', 'STOPPED', 'RESCHEDULED') AND"
                    + " COALESCE(h.principal_outstanding, 0) + COALESCE(h.interest_outstanding, 0)"
                    + " > 0 ORDER BY h.loan_id",
                (rs, i) ->
                        new Outstanding(
                                rs.getLong("loan_id"),
                                "Y".equals(rs.getString("is_interest_bearing"))
                                        ? "LOAN"
                                        : "ADVANCE",
                                rs.getString("loan_type_code"),
                                rs.getString("loan_number") != null
                                        ? rs.getString("loan_number")
                                        : rs.getString("loan_type_code")
                                                + " of "
                                                + rs.getDate("sanction_date").toLocalDate(),
                                rs.getBigDecimal("owed")),
                employeeId);
    }
}
