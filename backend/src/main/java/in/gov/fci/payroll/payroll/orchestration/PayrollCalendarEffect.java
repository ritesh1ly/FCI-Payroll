package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M-10 approvals (plan 05 phase 05f), in the decision's transaction: an approved calendar proposal
 * writes each of its periods (a new month inserted, an existing one updated, both with the approval
 * id), an approved run order writes the new sequence; every changed field is audited with its old
 * and new value (SRS-20-2). D-12 is checked again: a period a cycle was initiated for since the
 * proposal fails the decision, which stays to be decided.
 */
@Component
public class PayrollCalendarEffect {

    /** The calendar columns a proposal sets, with the payload field each one comes from. */
    static final String[][] COLUMNS = {
        {"period_start_date", "start"},
        {"period_end_date", "end"},
        {"input_cutoff_date", "freezeTarget"},
        {"compute_by_date", "computeBy"},
        {"verify_by_date", "verifyBy"},
        {"lock_target_date", "lockTarget"},
        {"disbursement_date", "disbursement"},
        {"supplementary_from", "supplementaryFrom"},
        {"supplementary_to", "supplementaryTo"},
        {"mdp_window_from", "mdpFrom"},
        {"mdp_window_to", "mdpTo"}
    };

    private final JdbcTemplate jdbc;
    private final PayrollCalendarService calendar;
    private final AuditService audit;
    private final ObjectMapper json;
    private final PayrollPeriods periods;

    public PayrollCalendarEffect(
            JdbcTemplate jdbc,
            PayrollCalendarService calendar,
            AuditService audit,
            ObjectMapper json,
            PayrollPeriods periods) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.audit = audit;
        this.json = json;
        this.periods = periods;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.approved()) {
            return;
        }
        if (decision.concerns(PayrollCalendarService.CALENDAR_ENTITY)) {
            // The cached calendar is reloaded on every instance once this transaction commits.
            periods.changed(decision.checkerLoginId());
            applyCalendar(decision);
        } else if (decision.concerns(PayrollCalendarService.RUN_ORDER_ENTITY)) {
            applyOrder(decision);
        }
    }

    private void applyCalendar(ApprovalDecided decision) {
        JsonNode proposal = json.readTree(decision.payload());
        String finYear = proposal.path("finYear").asString();
        String reason = proposal.path("reason").asString();
        for (JsonNode p : proposal.path("periods")) {
            YearMonth month = YearMonth.parse(p.path("month").asString());
            calendar.requireNotInUse(month);
            String yymm = CycleKeys.yymm(month);
            List<Map<String, Object>> found =
                    jdbc.queryForList(
                            "SELECT * FROM payroll_calendar WHERE payroll_yymm = ?", yymm);
            List<Object> values = new ArrayList<>();
            for (String[] c : COLUMNS) {
                values.add(sqlDate(p.path(c[1])));
            }
            if (found.isEmpty()) {
                List<Object> args = new ArrayList<>(List.of(finYear, yymm));
                args.addAll(values);
                args.add(decision.approvalId());
                jdbc.update(
                        "INSERT INTO payroll_calendar (fin_year, payroll_yymm, period_start_date,"
                            + " period_end_date, input_cutoff_date, compute_by_date,"
                            + " verify_by_date, lock_target_date, disbursement_date,"
                            + " supplementary_from, supplementary_to, mdp_window_from,"
                            + " mdp_window_to, approval_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                            + " ?, ?, ?, ?)",
                        args.toArray());
            } else {
                List<Object> args = new ArrayList<>(values);
                args.add(decision.approvalId());
                args.add(yymm);
                jdbc.update(
                        "UPDATE payroll_calendar SET period_start_date = ?, period_end_date = ?,"
                            + " input_cutoff_date = ?, compute_by_date = ?, verify_by_date = ?,"
                            + " lock_target_date = ?, disbursement_date = ?, supplementary_from ="
                            + " ?, supplementary_to = ?, mdp_window_from = ?, mdp_window_to = ?,"
                            + " approval_id = ? WHERE payroll_yymm = ?",
                        args.toArray());
            }
            Map<String, Object> before = found.isEmpty() ? Map.of() : found.get(0);
            for (int i = 0; i < COLUMNS.length; i++) {
                String old = text(before.get(COLUMNS[i][0].toUpperCase(java.util.Locale.ROOT)));
                String now = values.get(i) == null ? null : values.get(i).toString();
                if (!Objects.equals(old, now)) {
                    audit.record(
                            decision.checkerLoginId(),
                            AuditService.Entry.change(
                                            PayrollCalendarService.CALENDAR_ENTITY,
                                            yymm,
                                            COLUMNS[i][0],
                                            old,
                                            now,
                                            reason)
                                    .approval(decision.approvalId())
                                    .module("M-10"));
                }
            }
        }
    }

    private void applyOrder(ApprovalDecided decision) {
        JsonNode proposal = json.readTree(decision.payload());
        String reason = proposal.path("reason").asString();
        int sequence = 0;
        for (JsonNode code : proposal.path("order")) {
            sequence += 10;
            Integer old =
                    jdbc.queryForObject(
                            "SELECT sequence_no FROM cycle_run_order WHERE engine_step_code = ?",
                            Integer.class,
                            code.asString());
            if (old != null && old == sequence) {
                continue;
            }
            jdbc.update(
                    "UPDATE cycle_run_order SET sequence_no = ? WHERE engine_step_code = ?",
                    sequence,
                    code.asString());
            audit.record(
                    decision.checkerLoginId(),
                    AuditService.Entry.change(
                                    PayrollCalendarService.RUN_ORDER_ENTITY,
                                    code.asString(),
                                    "sequence_no",
                                    old == null ? null : String.valueOf(old),
                                    String.valueOf(sequence),
                                    reason)
                            .approval(decision.approvalId())
                            .module("M-10"));
        }
    }

    private static Date sqlDate(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode()
                ? null
                : Date.valueOf(LocalDate.parse(value.asString()));
    }

    /** A DATE column as text (YYYY-MM-DD), for the audit, whatever type JDBC hands over. */
    private static String text(Object value) {
        LocalDate date = PayrollCalendarService.date(value);
        return date == null ? null : date.toString();
    }
}
