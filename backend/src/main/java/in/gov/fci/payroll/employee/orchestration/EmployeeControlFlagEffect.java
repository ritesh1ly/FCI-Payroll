package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.business.ControlFlagRules;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import java.time.LocalDate;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Payroll Checker's approval of an M-01 Hold / Stop-Payment change (FRS §7.8.1 Scenario 4 step
 * 2 "Flag becomes effective from the stated date"; SRS-1-7): the flag is written in the decision's
 * transaction and audited with its old and new value, the effective date, the reason and the
 * approval (SRS-G-2). The payment effect follows from the next Actual run (PAYMENT_HELD on X-05
 * while set, cleared once computed without it). A rejected or returned change writes nothing.
 */
@Component
public class EmployeeControlFlagEffect {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;

    public EmployeeControlFlagEffect(JdbcTemplate jdbc, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(EmployeeControlFlagService.ENTITY) || !decision.approved()) {
            return;
        }
        JsonNode p;
        try {
            p = json.readTree(decision.payload());
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "Unreadable control flag approval " + decision.approvalId(), ex);
        }
        String code = p.path("flagCode").asString();
        String column = ControlFlagRules.COLUMNS.get(code);
        if (column == null) {
            throw new IllegalStateException("Unknown control flag " + code);
        }
        long employeeId = p.path("employeeId").asLong();
        String value = p.path("value").asString();
        // One constant statement per flag: no column name is ever built from data.
        boolean hold = "HOLD".equals(code);
        String before =
                jdbc.queryForObject(
                        hold
                                ? "SELECT hold_flag FROM employee WHERE employee_id = ?"
                                : "SELECT stop_payment_flag FROM employee WHERE employee_id = ?",
                        String.class,
                        employeeId);
        // Oracle review F5: every JDBC write of employee bumps row_version, so a JPA save of the
        // same member (its @Version check) fails instead of writing stale values back.
        jdbc.update(
                hold
                        ? "UPDATE employee SET hold_flag = ?, row_version = row_version + 1 WHERE"
                                + " employee_id = ?"
                        : "UPDATE employee SET stop_payment_flag = ?, row_version = row_version"
                                + " + 1 WHERE employee_id = ?",
                value,
                employeeId);
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                "employee",
                                p.path("eis").asString(),
                                column.toLowerCase(java.util.Locale.ROOT),
                                before,
                                value,
                                p.path("reason").asString())
                        .effective(LocalDate.parse(p.path("effectiveFrom").asString()))
                        .approval(decision.approvalId())
                        .module(EmployeeFlagService.SCREEN));
    }
}
