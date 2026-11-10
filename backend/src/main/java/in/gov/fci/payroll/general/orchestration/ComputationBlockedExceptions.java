package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.payroll.api.MemberComputationBlocked;
import in.gov.fci.payroll.payroll.api.MembersComputed;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * UC-01 alternate flow (SRS-G-9, SRS-15-14 gate G2): a member the Actual run could not compute is
 * raised on the X-05 worklist as BLOCKING and holding G2, once per cycle and member (a re-run does
 * not duplicate it); a later Actual run that computes the member clears it, naming the run.
 */
@Component
public class ComputationBlockedExceptions {

    static final String CODE = "COMPUTATION_BLOCKED";

    private final ExceptionWorklist worklist;
    private final NamedParameterJdbcTemplate jdbc;

    public ComputationBlockedExceptions(
            ExceptionWorklist worklist, NamedParameterJdbcTemplate jdbc) {
        this.worklist = worklist;
        this.jdbc = jdbc;
    }

    @EventListener
    public void on(MemberComputationBlocked blocked) {
        worklist.raise(
                "system.payroll",
                new ExceptionWorklist.NewEntry(
                        blocked.cycleInstanceId(),
                        blocked.employeeId(),
                        "C-02",
                        CODE,
                        "BLOCKING",
                        "G2",
                        null,
                        blocked.entityName(),
                        blocked.fieldName(),
                        blocked.fieldSource(),
                        blocked.correctionScreen(),
                        detail(blocked.detail())));
    }

    /** The failure as the worklist shows it, within payroll_exception.detail (2000). */
    static String detail(String failure) {
        String text = "Not computed: " + (failure == null ? "no reason given" : failure);
        return text.length() <= 2000 ? text : text.substring(0, 1997) + "...";
    }

    @EventListener
    public void on(MembersComputed computed) {
        List<Long> ids = computed.employeeIds();
        for (int i = 0; i < ids.size(); i += 1000) {
            List<Map<String, Object>> open =
                    jdbc.queryForList(
                            "SELECT employee_id, field_name FROM payroll_exception WHERE"
                                    + " exception_code = :code AND cycle_instance_id = :cycle AND"
                                    + " exception_status IN ('OPEN', 'IN_PROGRESS',"
                                    + " 'ROUTED_UPSTREAM') AND employee_id IN (:ids)",
                            new MapSqlParameterSource()
                                    .addValue("code", CODE)
                                    .addValue("cycle", computed.cycleInstanceId())
                                    .addValue(
                                            "ids", ids.subList(i, Math.min(ids.size(), i + 1000))));
            for (Map<String, Object> row : open) {
                worklist.clear(
                        "system.payroll",
                        CODE,
                        computed.cycleInstanceId(),
                        ((Number) row.get("EMPLOYEE_ID")).longValue(),
                        (String) row.get("FIELD_NAME"),
                        "Computed in Actual run " + computed.runId());
            }
        }
    }
}
