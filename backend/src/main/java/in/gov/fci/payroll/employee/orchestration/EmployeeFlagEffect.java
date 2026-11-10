package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Payroll Checker's approval of an M-01 allowance flag (plan 16 phase 16d-2; SRS-1-11,
 * BR-EMPM-004): the version is written in the decision's transaction, with the Maker, the reason
 * and the approval, and audited. A rejected or returned proposal writes nothing. SRS-1-26 /
 * SRS-21-11 (DR-10): a version effective before the current month is settled for the locked months
 * by the one retrospective mechanism (an arrear event; the locked months are never changed).
 */
@Component
public class EmployeeFlagEffect {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;
    private final RetroEvents retro;
    private final Clock clock;

    public EmployeeFlagEffect(
            JdbcTemplate jdbc,
            AuditService audit,
            ObjectMapper json,
            RetroEvents retro,
            Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.retro = retro;
        this.clock = clock;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(EmployeeFlagService.ENTITY) || !decision.approved()) {
            return;
        }
        JsonNode p;
        try {
            p = json.readTree(decision.payload());
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "Unreadable flag approval " + decision.approvalId(), ex);
        }
        String maker =
                jdbc.queryForObject(
                        "SELECT submitted_by FROM approval_queue WHERE approval_id = ?",
                        String.class,
                        decision.approvalId());
        LocalDate from = LocalDate.parse(p.path("effectiveFrom").asString());
        // US-GAP-39: the end date the Maker gave, if any (an earlier payload has none).
        String until = p.path("effectiveTo").asString("");
        jdbc.update(
                "INSERT INTO employee_eligibility_flag (employee_id, flag_code, flag_value,"
                        + " effective_from, effective_to, reason, approval_id, changed_by,"
                        + " changed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.path("employeeId").asLong(),
                p.path("flagCode").asString(),
                p.path("value").asString(),
                java.sql.Date.valueOf(from),
                until.isEmpty() ? null : java.sql.Date.valueOf(LocalDate.parse(until)),
                p.path("reason").asString(),
                decision.approvalId(),
                maker,
                Timestamp.from(decision.decidedAt()));
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                EmployeeFlagService.ENTITY,
                                decision.entityKey(),
                                "flag_value",
                                null,
                                p.path("value").asString(),
                                p.path("reason").asString())
                        .effective(from)
                        .approval(decision.approvalId())
                        .module(EmployeeFlagService.SCREEN));
        LocalDate month =
                LocalDate.now(clock.withZone(ZoneId.of("Asia/Kolkata"))).withDayOfMonth(1);
        if (from.isBefore(month)) {
            retro.raise(
                    new RetroEvents.Raise(
                            p.path("employeeId").asLong(),
                            from,
                            "OTHER",
                            "EMPLOYEE_FLAG",
                            EmployeeFlagService.ENTITY,
                            decision.entityKey(),
                            "Back-dated allowance flag "
                                    + p.path("flagCode").asString()
                                    + " = "
                                    + p.path("value").asString()
                                    + " from "
                                    + from,
                            decision.checkerLoginId()));
        }
    }
}
