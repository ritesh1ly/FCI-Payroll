package in.gov.fci.payroll.configuration.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * M-14 net-pay floor in force for a payroll type on a day (SRS-16-13, SRS-15-13), for the engines:
 * the approved version whose dates cover the day. A floor that is missing or not confirmed against
 * the current system is reported as such; the caller never assumes one (R-RULE: "a seeded guess is
 * worse than an empty value").
 */
@Component
public class NetPayFloors {

    /**
     * @param basis PERCENT_OF_GROSS, ABSOLUTE_AMOUNT or NONE ({@code
     *     net_pay_floor_rule.floor_basis})
     * @param value the amount or percentage (null for NONE)
     */
    public record Floor(String basis, BigDecimal value, boolean confirmed) {

        /**
         * The floor as an amount for a gross (a percentage floor is rounded to the paisa), zero for
         * NONE: the one formula of the cycle, the arrear recovery and the penalty check.
         *
         * <p>OPEN-QUESTION: PENDING 16-netfloor: SRS-1-40 states one-third of Basic Pay; the {@code
         * floor_basis} CHECK has no such value and altering the table waits for the owner.
         */
        public BigDecimal amount(BigDecimal gross) {
            return switch (basis) {
                case "ABSOLUTE_AMOUNT" -> value;
                case "PERCENT_OF_GROSS" ->
                        gross.multiply(value)
                                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                default -> BigDecimal.ZERO;
            };
        }

        /** Whether a net pay is below this floor for its gross. NONE: never below. */
        public boolean breachedBy(BigDecimal net, BigDecimal gross) {
            return !"NONE".equals(basis) && net.compareTo(amount(gross)) < 0;
        }
    }

    private final JdbcTemplate jdbc;

    public NetPayFloors(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The approved floor of {@code payrollType} in force on {@code day}, if any. */
    public Optional<Floor> inForce(String payrollType, LocalDate day) {
        Date d = Date.valueOf(day);
        List<Floor> rows =
                jdbc.query(
                        "SELECT f.floor_basis, f.floor_value, f.is_confirmed FROM"
                            + " net_pay_floor_rule f JOIN approval_queue q ON q.approval_id ="
                            + " f.approval_id WHERE q.status = 'APPROVED' AND f.payroll_type = ?"
                            + " AND f.effective_from <= ? AND (f.effective_to IS NULL OR"
                            + " f.effective_to >= ?) ORDER BY f.effective_from DESC",
                        (rs, i) ->
                                new Floor(
                                        rs.getString(1),
                                        rs.getBigDecimal(2),
                                        "Y".equals(rs.getString(3))),
                        payrollType,
                        d,
                        d);
        return rows.stream().findFirst();
    }
}
