package in.gov.fci.payroll.leave.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The Payroll Checker's decision on an M-20 version (plan 19 phase 19b; SRS-LV-14): an approved
 * version is confirmed in the decision's transaction and the version it replaces (same parameter
 * and leave type, approved, open, starting earlier) ends the day before, both audited; a returned
 * or rejected version stays unconfirmed and is never read ({@link LeaveParameters}).
 */
@Component
public class LeaveParameterEffect {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Circulars circulars;

    /** The circular a proposal names, in its submission payload ({@code {"circularId":n}}). */
    private static final Pattern CIRCULAR = Pattern.compile("\"circularId\":(\\d+)");

    public LeaveParameterEffect(JdbcTemplate jdbc, AuditService audit, Circulars circulars) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.circulars = circulars;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(LeaveParameterService.ENTITY) || !decision.approved()) {
            return;
        }
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT leave_pay_parameter_id, parameter_code, leave_type_code,"
                                + " effective_from, numeric_value, text_value FROM"
                                + " leave_pay_parameter WHERE approval_id = ?",
                        decision.approvalId());
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> v = rows.getFirst();
        String code = (String) v.get("PARAMETER_CODE");
        String type = (String) v.get("LEAVE_TYPE_CODE");
        LocalDate from = LeaveParameterService.toLocalDate(v.get("EFFECTIVE_FROM"));
        jdbc.update(
                "UPDATE leave_pay_parameter SET is_confirmed = 'Y' WHERE leave_pay_parameter_id ="
                        + " ?",
                v.get("LEAVE_PAY_PARAMETER_ID"));
        int closed =
                jdbc.update(
                        "UPDATE leave_pay_parameter SET effective_to = ? WHERE parameter_code = ?"
                                + " AND ((? IS NULL AND leave_type_code IS NULL) OR leave_type_code"
                                + " = ?) AND is_confirmed = 'Y' AND effective_to IS NULL AND"
                                + " effective_from < ?",
                        Date.valueOf(from.minusDays(1)),
                        code,
                        type,
                        type,
                        Date.valueOf(from));
        // SRS M-20 / SRS-G-38: the circular the version was proposed on is linked once approved.
        Matcher circular = CIRCULAR.matcher(decision.payload() == null ? "" : decision.payload());
        if (circular.find()) {
            circulars.link(
                    decision.checkerLoginId(),
                    Long.valueOf(circular.group(1)),
                    Circulars.LinkType.RULE_VERSION,
                    LeaveParameterService.ENTITY,
                    String.valueOf(v.get("LEAVE_PAY_PARAMETER_ID")),
                    LeaveParameterService.SCREEN);
        }
        Object value = v.get("TEXT_VALUE") != null ? v.get("TEXT_VALUE") : v.get("NUMERIC_VALUE");
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                LeaveParameterService.ENTITY,
                                decision.entityKey(),
                                code,
                                null,
                                value == null ? null : value.toString(),
                                closed > 0
                                        ? "Version approved; the previous one ends "
                                                + from.minusDays(1)
                                        : "Version approved")
                        .effective(from)
                        .approval(decision.approvalId())
                        .module(LeaveParameterService.SCREEN));
    }
}
