package in.gov.fci.payroll.leave.orchestration;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E-01 leave balance (plan 14 phase 14e; SRS-LV-32, SRS-G-45): the latest balance HRMS sent for
 * each of the member's leave types, for the member's information only (HRMS data, read-only; never
 * computed in Payroll).
 */
@Service
@Loggable
public class LeaveBalanceService {

    /** One leave type's latest balance from HRMS. */
    public record Balance(
            String leaveType, String leaveTypeName, BigDecimal days, LocalDate asOf) {}

    private final JdbcTemplate jdbc;
    private final Authorization policy;

    public LeaveBalanceService(JdbcTemplate jdbc, Authorization policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    /** E-01: the member's own balances (SRS-17-9: never another member's). */
    @Transactional(readOnly = true)
    public List<Balance> mine(AccessContext context) {
        policy.require(context, "E-01", "SELF");
        if (context.employeeId() == null) {
            throw new FciException(
                    ApiErrorCode.PERMISSION_DENIED, new Object[] {"E-01", "SELF"}, List.of(), null);
        }
        return jdbc.query(
                "SELECT s.leave_type_code, t.leave_type_name, s.balance_days, s.as_of_date FROM"
                        + " leave_balance_snapshot s JOIN leave_type_reference t ON"
                        + " t.leave_type_code = s.leave_type_code WHERE s.employee_id = ? AND"
                        + " s.as_of_date = (SELECT MAX(x.as_of_date) FROM leave_balance_snapshot x"
                        + " WHERE x.employee_id = s.employee_id AND x.leave_type_code ="
                        + " s.leave_type_code) ORDER BY t.leave_type_name",
                (rs, i) ->
                        new Balance(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getBigDecimal(3),
                                rs.getDate(4).toLocalDate()),
                context.employeeId());
    }
}
