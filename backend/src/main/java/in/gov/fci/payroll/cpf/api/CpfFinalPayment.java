package in.gov.fci.payroll.cpf.api;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member's CPF final payment as the CPF / PF &amp; VPF module holds it (plan 11 phase 11b;
 * SRS-10-3, SRS-10-17, BR-FNF-003, BR-FNF-010): the latest FINAL sanction not cancelled or
 * rejected, with its sanction number, amount and stage. Information only: a settlement never
 * computes, adjusts or nets it. None means the member has not applied, and the other retirement
 * benefits are withheld until the application is made (BR-CPF-019).
 */
@Component
public class CpfFinalPayment {

    /** The final payment's sanction (amount null until it is sanctioned). */
    public record Status(long sanctionId, String sanctionNumber, BigDecimal amount, String stage) {}

    private final JdbcTemplate jdbc;

    public CpfFinalPayment(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Optional<Status> of(long employeeId) {
        return jdbc
                .query(
                        "SELECT sanction_id, system_sanction_number, sanctioned_amount,"
                                + " workflow_stage FROM pf_sanction WHERE employee_id = ? AND"
                                + " sanction_type = 'FINAL' AND workflow_stage NOT IN ('CANCELLED',"
                                + " 'REJECTED') ORDER BY sanction_id DESC",
                        (rs, i) ->
                                new Status(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getBigDecimal(3),
                                        rs.getString(4)),
                        employeeId)
                .stream()
                .findFirst();
    }
}
