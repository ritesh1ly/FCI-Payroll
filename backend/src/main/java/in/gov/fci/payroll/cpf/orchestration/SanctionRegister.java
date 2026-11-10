package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.platform.persistence.RowCounts;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the CPF Advance / Withdrawal Register ({@code pf_sanction}, SRS-6-55) says about a member,
 * as the other CPF transactions ask it: a sanction counts once the Competent Authority has
 * sanctioned it (SANCTIONED, PAYMENT_BLOCKED or PAID). Read with SQL until the sanction chain
 * (phase 07f) maps the table; then its repository answers the same questions.
 */
@Component
public class SanctionRegister {

    private static final String SANCTIONED =
            "workflow_stage IN ('SANCTIONED', 'PAYMENT_BLOCKED', 'PAID')";

    private final JdbcTemplate jdbc;

    public SanctionRegister(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** SRS-6-44: the member's CPF final settlement is sanctioned. */
    @Transactional(readOnly = true)
    public boolean finallySettled(Long employeeId) {
        return count(
                        "SELECT COUNT(*) FROM pf_sanction WHERE employee_id = ? AND sanction_type ="
                                + " 'FINAL' AND "
                                + SANCTIONED,
                        employeeId)
                > 0;
    }

    /** SRS-6-44: a withdrawal of the member was sanctioned on or after {@code day}. */
    @Transactional(readOnly = true)
    public boolean sanctionedSince(Long employeeId, LocalDate day) {
        return count(
                        "SELECT COUNT(*) FROM pf_sanction WHERE employee_id = ? AND sanction_date"
                                + " >= ? AND "
                                + SANCTIONED,
                        employeeId,
                        day)
                > 0;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return (int) RowCounts.of(n);
    }
}
