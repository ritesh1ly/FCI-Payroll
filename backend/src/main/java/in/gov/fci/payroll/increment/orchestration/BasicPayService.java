package in.gov.fci.payroll.increment.orchestration;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.increment.business.IncrementRules;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one writer of Basic Pay (plan 17 phase 17a; SRS-13-26, SRS-13-4, SRS-20-6; D17-1, D17-6).
 * Every approved Basic event - an increment, a fixation, a revision, a correction, a value from
 * HRMS - becomes a row of {@code employee_basic_history}, never an edit: the row covering the
 * effective date is end-dated the day before (or, on the same date, the new row takes the next
 * {@code event_sequence}), and {@code employee.current_basic} follows when the event takes effect
 * in or before the current month (the run pays a whole month's Basic; a future-dated event waits in
 * history, PENDING 17-future-basic). An effective date before the current month raises the
 * retrospective event of the member, so the locked months are settled as an arrear or recovery
 * (SRS-13-4), never reopened. Audited with the old and new Basic.
 */
@Service
public class BasicPayService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * One Basic event.
     *
     * @param eventType INCREMENT, STAGNATION_INCREMENT, PROMOTION, FIXATION, PAY_REVISION,
     *     CORRECTION, HRMS_CHANGE, MIGRATION_OPENING, PENALTY_REDUCTION, PENALTY_RESTORATION
     * @param sourceCode ENT / CALC / HRMS (R-SRC)
     * @param retroModule the arrear event's source module (HRMS_CHANGE, INCREMENT, WAGE_REVISION,
     *     PENALTY)
     * @param retroEntity and {@code retroKey}: the source record, one open arrear event each
     */
    public record Change(
            long employeeId,
            BigDecimal basic,
            LocalDate effectiveFrom,
            String eventType,
            String sourceCode,
            String reason,
            String orderReference,
            String hrmsRequestId,
            Long hrmsTaskId,
            Long approvalId,
            String by,
            String retroModule,
            String retroEntity,
            String retroKey) {}

    /** The history row written, and the Basic before it. */
    public record Applied(
            long historyId, BigDecimal oldBasic, boolean current, Long arrearEventId) {}

    private final NamedParameterJdbcTemplate named;
    private final AuditService audit;
    private final RetroEvents retro;
    private final Clock clock;

    public BasicPayService(
            NamedParameterJdbcTemplate named, AuditService audit, RetroEvents retro, Clock clock) {
        this.named = named;
        this.audit = audit;
        this.retro = retro;
        this.clock = clock;
    }

    /** Records the event in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Applied apply(Change c) {
        MapSqlParameterSource key =
                new MapSqlParameterSource("employee", c.employeeId())
                        .addValue("from", Date.valueOf(c.effectiveFrom()));
        Map<String, Object> employee =
                named.queryForMap(
                        "SELECT current_basic, band_id FROM employee WHERE employee_id = :employee",
                        key);
        BigDecimal old = basicOn(c.employeeId(), c.effectiveFrom());
        if (old == null) {
            old = (BigDecimal) employee.get("CURRENT_BASIC");
        }
        // The row covering the date ends the day before; a same-date row stays (lower sequence).
        named.update(
                "UPDATE employee_basic_history SET effective_to = :before WHERE employee_id ="
                        + " :employee AND effective_from < :from AND (effective_to IS NULL OR"
                        + " effective_to >= :from)",
                key.addValue("before", Date.valueOf(c.effectiveFrom().minusDays(1))));
        Integer sequence =
                named.queryForObject(
                        "SELECT COALESCE(MAX(event_sequence), 0) + 1 FROM employee_basic_history"
                                + " WHERE employee_id = :employee AND effective_from = :from",
                        key,
                        Integer.class);
        LocalDate nextStart =
                named
                        .queryForList(
                                "SELECT MIN(effective_from) FROM employee_basic_history WHERE"
                                        + " employee_id = :employee AND effective_from > :from",
                                key,
                                Date.class)
                        .stream()
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .map(Date::toLocalDate)
                        .orElse(null);
        MapSqlParameterSource row =
                new MapSqlParameterSource("employee", c.employeeId())
                        .addValue("basic", c.basic())
                        .addValue("old", old, Types.NUMERIC)
                        .addValue("band", employee.get("BAND_ID"), Types.NUMERIC)
                        .addValue("type", c.eventType())
                        .addValue("from", Date.valueOf(c.effectiveFrom()))
                        .addValue("sequence", java.util.Objects.requireNonNullElse(sequence, 1))
                        .addValue(
                                "to",
                                nextStart == null ? null : Date.valueOf(nextStart.minusDays(1)),
                                Types.DATE)
                        .addValue("source", c.sourceCode())
                        .addValue(
                                "reason",
                                cut(
                                        IncrementRules.eventName(c.eventType(), c.effectiveFrom())
                                                + ": "
                                                + c.reason(),
                                        500))
                        .addValue("order", c.orderReference(), Types.VARCHAR)
                        .addValue("request", c.hrmsRequestId(), Types.VARCHAR)
                        .addValue("task", c.hrmsTaskId(), Types.NUMERIC)
                        .addValue("approval", c.approvalId(), Types.NUMERIC)
                        .addValue("by", c.by())
                        .addValue("at", Timestamp.from(clock.instant()));
        named.update(
                "INSERT INTO employee_basic_history (employee_id, basic_amount, old_basic_amount,"
                        + " band_id, event_type, effective_from, event_sequence, effective_to,"
                        + " source_code, reason, order_reference, hrms_request_id,"
                        + " hrms_change_task_id, approval_id, changed_by, changed_at) VALUES"
                        + " (:employee, :basic, :old, :band, :type, :from, :sequence, :to, :source,"
                        + " :reason, :order, :request, :task, :approval, :by, :at)",
                row);
        Long id =
                named.queryForObject(
                        "SELECT history_id FROM employee_basic_history WHERE employee_id ="
                                + " :employee AND effective_from = :from AND event_sequence ="
                                + " :sequence",
                        row,
                        Long.class);
        LocalDate today = LocalDate.now(clock.withZone(IST));
        boolean current = !c.effectiveFrom().isAfter(today.withDayOfMonth(today.lengthOfMonth()));
        // A later row that starts after this month is not in force yet: this event still sets the
        // Basic the run pays now.
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        if (current && (nextStart == null || nextStart.isAfter(monthEnd))) {
            // Oracle review F5: row_version bumped, so a transfer or status change saved through
            // JPA meanwhile fails its version check instead of writing the old Basic back.
            named.update(
                    "UPDATE employee SET current_basic = :basic, row_version = row_version + 1"
                            + " WHERE employee_id = :employee",
                    row);
        }
        Long arrear = null;
        if (c.effectiveFrom().isBefore(today.withDayOfMonth(1)) && c.retroKey() != null) {
            arrear =
                    retro.raise(
                            new RetroEvents.Raise(
                                    c.employeeId(),
                                    c.effectiveFrom(),
                                    eventCode(c.retroModule()),
                                    c.retroModule(),
                                    c.retroEntity(),
                                    c.retroKey(),
                                    cut(
                                            "Back-dated Basic "
                                                    + c.eventType()
                                                    + " from "
                                                    + c.effectiveFrom(),
                                            300),
                                    c.by()));
        }
        audit.record(
                c.by(),
                AuditService.Entry.change(
                                "employee",
                                String.valueOf(c.employeeId()),
                                "current_basic",
                                old == null ? null : old.toPlainString(),
                                c.basic().toPlainString(),
                                cut(
                                        IncrementRules.eventName(c.eventType(), c.effectiveFrom())
                                                + ": "
                                                + c.reason(),
                                        500))
                        .approval(c.approvalId())
                        .effective(c.effectiveFrom())
                        .module("I-05"));
        return new Applied(id == null ? -1 : id, old, current, arrear);
    }

    /** The Basic in force on the day (the highest sequence of the latest date on or before it). */
    public BigDecimal basicOn(long employeeId, LocalDate day) {
        List<BigDecimal> found =
                named.queryForList(
                        "SELECT basic_amount FROM employee_basic_history WHERE employee_id ="
                                + " :employee AND effective_from <= :day ORDER BY effective_from"
                                + " DESC, event_sequence DESC FETCH FIRST 1 ROWS ONLY",
                        new MapSqlParameterSource("employee", employeeId)
                                .addValue("day", Date.valueOf(day)),
                        BigDecimal.class);
        return found.isEmpty() ? null : found.getFirst();
    }

    /** The arrear event type of a source module (a penalty order keeps its own, SRS-14-6). */
    private static String eventCode(String module) {
        return switch (module) {
            case "WAGE_REVISION" -> "WAGE_REVISION";
            case "PENALTY" -> "PENALTY";
            default -> "PROMOTION";
        };
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
