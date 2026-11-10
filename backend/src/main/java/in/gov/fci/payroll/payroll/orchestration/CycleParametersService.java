package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.configuration.api.NetPayFloors;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-10 Cycle Parameters panel (plan 05 phase 05f, D-23; SRS M-10 field table "Cycle Parameters"): a
 * read-only summary of the cycle's parameters, each kept on its own screen and changed there under
 * its own maker-checker: the lock and undo authorities (X-01 routes C02_LOCK, C02_UNDO), the MDP
 * heads (M-16 list MDP_PERMITTED_COMPONENT), the back-date window (A-07 MAX_RETRO_PERIOD), the
 * net-pay floor (M-14) and the pre-payroll check rules (name, payrolls, owner, severity:
 * SRS-15-39). Nothing is edited here, so no setting has two places to change it.
 */
@Service
@Loggable
public class CycleParametersService {

    /** One level of an X-01 route in force today. */
    public record Authority(
            String action, int level, String levelName, String role, String office, boolean otp) {}

    /** One MDP head of the permitted list. */
    public record Head(String payCode, String name) {}

    /** One configured pre-payroll or pre-run check. */
    public record CheckRule(
            String code,
            String name,
            String stage,
            String appliesTo,
            String owner,
            String severity,
            boolean active,
            String correctionScreen) {}

    /** The net-pay floor of the Employee / Staff payroll in force today, or null when not set. */
    public record Floor(String basis, BigDecimal value, boolean confirmed) {}

    public record Parameters(
            List<Authority> authorities,
            List<Head> mdpHeads,
            Long backDateWindowMonths,
            Floor staffNetPayFloor,
            List<CheckRule> checks) {}

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final SystemParameters settings;
    private final NetPayFloors floors;

    public CycleParametersService(
            JdbcTemplate jdbc,
            Authorization policy,
            SystemParameters settings,
            NetPayFloors floors) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.settings = settings;
        this.floors = floors;
    }

    @Transactional(readOnly = true)
    public Parameters parameters(AccessContext context) {
        policy.require(context, "M-10", "VIEW");
        LocalDate today = settings.today();
        Date day = Date.valueOf(today);
        List<Authority> authorities =
                jdbc.query(
                        "SELECT a.action_code, l.level_no, l.level_name, l.role_code,"
                            + " l.office_level, l.otp_at_level FROM approval_workflow_action a JOIN"
                            + " approval_workflow_level l ON l.workflow_action_id ="
                            + " a.workflow_action_id WHERE a.action_code IN ('C02_LOCK',"
                            + " 'C02_UNDO') AND a.action_status = 'ACTIVE' AND a.effective_from <="
                            + " ? AND (a.effective_to IS NULL OR a.effective_to >= ?) ORDER BY"
                            + " a.action_code, l.level_no",
                        (rs, i) ->
                                new Authority(
                                        rs.getString(1),
                                        rs.getInt(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        "Y".equals(rs.getString(6))),
                        day,
                        day);
        List<Head> heads =
                jdbc.query(
                        "SELECT v.value_code, v.value_label_en FROM reference_value v JOIN"
                                + " reference_list l ON l.list_id = v.list_id WHERE l.list_code ="
                                + " 'MDP_PERMITTED_COMPONENT' AND v.effective_from <= ? AND"
                                + " (v.effective_to IS NULL OR v.effective_to >= ?) ORDER BY"
                                + " v.sort_order, v.value_code",
                        (rs, i) -> new Head(rs.getString(1), rs.getString(2)),
                        day,
                        day);
        List<CheckRule> checks =
                jdbc.query(
                        "SELECT check_code, check_name, check_stage, applies_to, owner_role_code,"
                                + " severity, is_active, correction_screen FROM pre_payroll_check"
                                + " WHERE effective_from <= ? AND (effective_to IS NULL OR"
                                + " effective_to >= ?) ORDER BY check_stage DESC, check_code",
                        (rs, i) ->
                                new CheckRule(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getString(6),
                                        "Y".equals(rs.getString(7)),
                                        rs.getString(8)),
                        day,
                        day);
        Floor floor =
                floors.inForce("STAFF", today)
                        .map(f -> new Floor(f.basis(), f.value(), f.confirmed()))
                        .orElse(null);
        Long window =
                settings.configured(SystemParameters.Code.MAX_RETRO_PERIOD).isPresent()
                        ? settings.configured(SystemParameters.Code.MAX_RETRO_PERIOD).getAsLong()
                        : null;
        return new Parameters(authorities, heads, window, floor, checks);
    }
}
