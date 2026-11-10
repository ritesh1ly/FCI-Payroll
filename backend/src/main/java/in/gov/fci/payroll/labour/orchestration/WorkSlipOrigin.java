package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.employee.api.OriginWorkSlips;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The work-slips of a member for the M-11 transfer origin check (UC M-11, SRS l.1321; OD-51): the
 * slips the member works on, dated on or after the transfer date and not cancelled or rejected. The
 * labour module owns {@code work_slip}; the employee module asks through {@link OriginWorkSlips}.
 */
@Component
class WorkSlipOrigin implements OriginWorkSlips {

    private final JdbcTemplate jdbc;

    WorkSlipOrigin(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalDate> firstFrom(long employeeId, LocalDate from) {
        // work_slip_date is the partition key of both tables: the range and the join on it prune.
        List<Date> rows =
                jdbc.queryForList(
                        "SELECT MIN(p.work_slip_date) FROM work_slip_participant p JOIN work_slip s"
                                + " ON s.work_slip_id = p.work_slip_id AND s.work_slip_date ="
                                + " p.work_slip_date WHERE p.employee_id = ? AND p.work_slip_date"
                                + " >= ? AND s.slip_status NOT IN ('CANCELLED', 'REJECTED')",
                        Date.class,
                        employeeId,
                        Date.valueOf(from));
        return rows.isEmpty() || rows.get(0) == null
                ? Optional.empty()
                : Optional.of(rows.get(0).toLocalDate());
    }
}
