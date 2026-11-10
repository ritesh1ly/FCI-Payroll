package in.gov.fci.payroll.increment.orchestration;

import in.gov.fci.payroll.employee.api.IncrementDue;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** SRS-13-1 (US-GAP-51): the next increment due date of {@code employee_increment_due}. */
@Component
public class IncrementDueSource implements IncrementDue {

    private final JdbcTemplate jdbc;

    public IncrementDueSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LocalDate> nextDue(long employeeId) {
        return jdbc
                .queryForList(
                        "SELECT next_due_date FROM employee_increment_due WHERE employee_id = ?",
                        Date.class,
                        employeeId)
                .stream()
                .findFirst()
                .map(Date::toLocalDate);
    }
}
