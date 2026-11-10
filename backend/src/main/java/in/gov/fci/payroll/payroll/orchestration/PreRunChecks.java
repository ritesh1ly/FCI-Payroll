package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.payroll.batch.computation.MemberReader;
import java.sql.Date;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The initiation checks P1-P5 of a cycle instance (plan 05 D-22; SRS-15-23, SRS §6.7.2; DDL {@code
 * pre_payroll_check} with {@code check_stage = 'PRE_INITIATION'}): evaluated on the members the run
 * would read ({@link MemberReader}) before the instance exists, so nothing is stored; Initialise
 * stays refused until every active check is clear.
 *
 * <ul>
 *   <li>P1: no member whose retirement date is before the first day of the period;
 *   <li>P2: no member whose FPS number ({@code employee.eps_number}) another member holds;
 *   <li>an inactive check (P3, P4, P5 until their source exists) is listed "not available" and does
 *       not block; an active check without an evaluator fails (never passed silently).
 * </ul>
 *
 * The failing records are named by Employee Number, at most {@link #NAMED} per check.
 */
@Component
public class PreRunChecks {

    /** How many failing Employee Numbers a check names. */
    static final int NAMED = 20;

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String NOT_AVAILABLE = "NOT_AVAILABLE";

    /** One check as C-02 shows it. */
    public record Check(
            String code,
            String name,
            String status,
            long failures,
            List<String> members,
            String correctionScreen) {

        public Check {
            members = List.copyOf(members);
        }

        boolean blocks() {
            return FAIL.equals(status);
        }
    }

    private record Outcome(long failures, List<String> members) {}

    /** P1, after the run's eligibility. */
    private static final String P1 =
            " AND e.retirement_date IS NOT NULL AND e.retirement_date < :start ORDER BY e.emp_num";

    /** P2, after the run's eligibility. */
    private static final String P2 =
            " AND e.eps_number IS NOT NULL AND EXISTS (SELECT 1 FROM employee o WHERE"
                    + " o.eps_number = e.eps_number AND o.employee_id <> e.employee_id AND"
                    + " o.record_status <> 'CANCELLED') ORDER BY e.emp_num";

    private final NamedParameterJdbcTemplate jdbc;
    private final SimulationEngine population;

    public PreRunChecks(NamedParameterJdbcTemplate jdbc, SimulationEngine population) {
        this.jdbc = jdbc;
        this.population = population;
    }

    /** Every configured initiation check for the unit and period, in check-code order. */
    @Transactional(readOnly = true)
    public List<Check> evaluate(long unitId, YearMonth period) {
        Date start = Date.valueOf(period.atDay(1));
        List<Check> out = new ArrayList<>();
        for (Map<String, Object> c :
                jdbc.queryForList(
                        "SELECT check_code, check_name, is_active, evaluation_rule_key,"
                                + " correction_screen FROM pre_payroll_check WHERE check_stage ="
                                + " 'PRE_INITIATION' AND effective_from <= :start AND"
                                + " (effective_to IS NULL OR effective_to >= :start) ORDER BY"
                                + " check_code",
                        new MapSqlParameterSource("start", start))) {
            String code = (String) c.get("CHECK_CODE");
            String name = (String) c.get("CHECK_NAME");
            String screen = (String) c.get("CORRECTION_SCREEN");
            if (!"Y".equals(c.get("IS_ACTIVE"))) {
                out.add(new Check(code, name, NOT_AVAILABLE, 0, List.of(), screen));
                continue;
            }
            Outcome o = run((String) c.get("EVALUATION_RULE_KEY"), unitId, period);
            out.add(
                    new Check(
                            code,
                            name,
                            o == null ? FAIL : o.failures() == 0 ? PASS : FAIL,
                            o == null ? 1 : o.failures(),
                            o == null ? List.of() : o.members(),
                            screen));
        }
        return out;
    }

    /** The codes of the checks that keep Initialise refused. */
    public static List<String> blocking(List<Check> checks) {
        return checks.stream().filter(Check::blocks).map(Check::code).toList();
    }

    /** The evaluators built; null for an active key without one (it then fails). */
    private Outcome run(String ruleKey, long unitId, YearMonth period) {
        String condition =
                switch (ruleKey == null ? "" : ruleKey) {
                    case "P1_RETIREMENT" -> P1;
                    case "P2_DUPLICATE_FPS" -> P2;
                    default -> null;
                };
        if (condition == null) {
            return null;
        }
        List<String> members = new ArrayList<>();
        List<Long> locations = population.locations(unitId);
        for (int i = 0; i < locations.size(); i += 1000) {
            MapSqlParameterSource params =
                    new MapSqlParameterSource()
                            .addValue(
                                    "locations",
                                    locations.subList(i, Math.min(locations.size(), i + 1000)))
                            .addValue("yymm", MemberReader.yymm(period.atDay(1)))
                            .addValue("start", Date.valueOf(period.atDay(1)));
            members.addAll(
                    jdbc.queryForList(
                            "SELECT e.emp_num FROM employee e " + MemberReader.where() + condition,
                            params,
                            String.class));
        }
        return new Outcome(members.size(), members.subList(0, Math.min(NAMED, members.size())));
    }
}
