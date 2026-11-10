package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.payroll.api.MemberPaymentHeld;
import in.gov.fci.payroll.payroll.api.MembersPaymentReleased;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SRS-1-7 / SRS-1-41 (plan 16 phase 16d): a computed member whose payment is held is raised on the
 * X-05 worklist, in the result's transaction, once per cycle, member and reason (a re-run does not
 * duplicate it). WARNING: it holds the member's payment, not the cycle. A later Actual run that
 * computes the member without a hold (the hold ended) clears it, naming the run as the cause.
 */
@Component
public class PaymentHoldExceptions {

    static final String CODE = "PAYMENT_HELD";

    /** reason -> field, its source and where it is corrected ("Fix Where"). */
    private static final Map<String, String[]> WHERE =
            Map.of(
                    "SALARY_WITHHELD",
                            new String[] {"employee_lifecycle_event", "event_type", "ENT", "I-17"},
                    "HOLD_FLAG", new String[] {"employee", "hold_flag", "ENT", "M-01"},
                    "STOP_PAYMENT_FLAG",
                            new String[] {"employee", "stop_payment_flag", "ENT", "M-01"},
                    "ABSCONDING", new String[] {"employee", "absconding_flag", "HRMS", "I-21"});

    private final ExceptionWorklist worklist;
    private final NamedParameterJdbcTemplate jdbc;

    public PaymentHoldExceptions(ExceptionWorklist worklist, NamedParameterJdbcTemplate jdbc) {
        this.worklist = worklist;
        this.jdbc = jdbc;
    }

    /** The hold ended: the open PAYMENT_HELD of these members in the cycle is cleared. */
    @EventListener
    public void on(MembersPaymentReleased released) {
        List<Long> ids = released.employeeIds();
        for (int i = 0; i < ids.size(); i += 1000) {
            List<Map<String, Object>> open =
                    jdbc.queryForList(
                            "SELECT employee_id, field_name FROM payroll_exception WHERE"
                                    + " exception_code = :code AND cycle_instance_id = :cycle AND"
                                    + " exception_status IN ('OPEN', 'IN_PROGRESS',"
                                    + " 'ROUTED_UPSTREAM') AND employee_id IN (:ids)",
                            new MapSqlParameterSource()
                                    .addValue("code", CODE)
                                    .addValue("cycle", released.cycleInstanceId())
                                    .addValue(
                                            "ids", ids.subList(i, Math.min(ids.size(), i + 1000))));
            for (Map<String, Object> row : open) {
                worklist.clear(
                        "system.payroll",
                        CODE,
                        released.cycleInstanceId(),
                        ((Number) row.get("EMPLOYEE_ID")).longValue(),
                        (String) row.get("FIELD_NAME"),
                        "Payment no longer held: computed without a hold in a later Actual run");
            }
        }
    }

    @EventListener
    public void on(MemberPaymentHeld held) {
        String[] where =
                WHERE.getOrDefault(held.reason(), new String[] {"employee", null, "SYS", "M-01"});
        worklist.raise(
                "system.payroll",
                new ExceptionWorklist.NewEntry(
                        held.cycleInstanceId(),
                        held.employeeId(),
                        "C-02",
                        CODE,
                        "WARNING",
                        null,
                        null,
                        where[0],
                        where[1],
                        where[2],
                        where[3],
                        "Computed; payment held: " + held.reason()));
    }
}
