package in.gov.fci.payroll.leave.orchestration;

import in.gov.fci.payroll.leave.business.LeaveViewRules;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-only leave screens (plan 19 phase 19a; SRS M-13 and I-11 field tables, SRS-LV-13,
 * SRS-LV-14, SRS-LV-26). M-13 lists the leave types received from HRMS with the pay effect M-20
 * holds for each today; I-11 lists the leave events received for the offices of the session (every
 * office at Headquarters), each with its status: Pending (the I-21 task waits), Applied, Back-dated
 * (settled in the next open cycle) or Rejected; the events held as unmatched are on X-05 and
 * counted here. The Administrator has no I-11 permission at all (SRS-LV-14; RBAC seed V046_2).
 */
@Service
public class LeaveViewService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** One leave type of M-13. */
    public record TypeRow(
            String code,
            String name,
            boolean encashable,
            boolean halfDay,
            String payEffect,
            LocalDateTime lastReceived) {}

    /** The M-13 tiles. */
    public record TypeCounts(
            long received, long encashable, long payReducing, LocalDateTime lastSync) {}

    /** One leave event of I-11. */
    public record EventRow(
            long applicationId,
            String sanctionRef,
            String eis,
            String name,
            String office,
            String leaveType,
            String leaveTypeName,
            LocalDate from,
            LocalDate to,
            BigDecimal days,
            String eventType,
            LocalDateTime received,
            String payEffect,
            String status,
            String appliedIn,
            String rule,
            BigDecimal effectAmount) {}

    /** The I-11 tiles. */
    public record EventCounts(long received, long applied, long backDated, long held) {}

    /** The I-11 filters; each may be null. */
    public record Filter(String status, String eis, String leaveType) {}

    private static final String STATUS =
            "CASE WHEN x.leave_txn_id IS NOT NULL AND x.is_back_dated = 'Y' THEN 'BACK_DATED'"
                    + " WHEN x.leave_txn_id IS NOT NULL THEN 'APPLIED' WHEN t.task_status IN"
                    + " ('REJECTED_RETURNED_TO_HRMS', 'CANCELLED') THEN 'REJECTED' ELSE 'PENDING'"
                    + " END";

    /**
     * The event, its I-21 task (whose read-only value begins with the application id, written by
     * {@link LeaveIntakeService}) and its transaction once applied.
     */
    private static final String FROM =
            " FROM leave_application a JOIN employee e ON e.employee_id = a.employee_id LEFT JOIN"
                + " org_location l ON l.location_id = e.location_id LEFT JOIN leave_type_reference"
                + " r ON r.leave_type_code = a.leave_type_code LEFT JOIN hrms_change_task t ON"
                + " t.employee_id = a.employee_id AND t.pay_effect_field_id IN (SELECT"
                + " f.pay_effect_field_id FROM hrms_pay_effect_field f WHERE f.field_code ="
                + " 'LEAVE_EVENT') AND t.new_value LIKE '{\"applicationId\":' || a.application_id"
                + " || ',%' LEFT JOIN leave_transaction x ON x.application_id = a.application_id";

    /** What the "Applied in" month and the rule are worked out from (US-GAP-59). */
    private static final String APPLIED =
            "e.location_id, x.is_back_dated, x.period_date, COALESCE(t.applied_at,"
                    + " t.checker_decided_at) AS applied_at, (SELECT SUM(d.arrear_amount) FROM"
                    + " arrear_detail d JOIN arrear_event ev ON ev.arrear_event_id ="
                    + " d.arrear_event_id WHERE ev.source_module = 'LEAVE' AND ev.source_entity ="
                    + " 'leave_transaction' AND ev.source_key = t.task_no AND d.arrear_status <>"
                    + " 'REVERSED') AS effect_amount, ";

    private static final String WHERE =
            " WHERE (:all = 'Y' OR e.location_id IN (:locations)) AND (:eis IS NULL OR e.emp_num ="
                    + " :eis) AND (:type IS NULL OR a.leave_type_code = :type) AND (:status IS NULL"
                    + " OR "
                    + STATUS
                    + " = :status)";

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final LeaveParameters parameters;
    private final OrgDirectory org;
    private final Clock clock;

    public LeaveViewService(
            NamedParameterJdbcTemplate named,
            Authorization policy,
            LeaveParameters parameters,
            OrgDirectory org,
            Clock clock) {
        this.named = named;
        this.policy = policy;
        this.parameters = parameters;
        this.org = org;
        this.clock = clock;
    }

    /** M-13: the leave types received from HRMS, with today's pay effect, one page. */
    @Transactional(readOnly = true)
    public Paged<TypeRow> types(AccessContext context, PageSpec page) {
        policy.require(context, "M-13", "VIEW");
        LocalDate today = LocalDate.now(clock.withZone(IST));
        MapSqlParameterSource p = new MapSqlParameterSource();
        Long total =
                named.queryForObject("SELECT COUNT(*) FROM leave_type_reference", p, Long.class);
        p.addValue("offset", (long) page.page() * page.size()).addValue("size", page.size());
        List<TypeRow> rows =
                named.query(
                        "SELECT leave_type_code, leave_type_name, is_encashable,"
                                + " supports_half_day, last_received_at FROM leave_type_reference"
                                + " ORDER BY leave_type_code OFFSET :offset ROWS FETCH NEXT :size"
                                + " ROWS ONLY",
                        p,
                        (rs, i) ->
                                new TypeRow(
                                        rs.getString(1),
                                        rs.getString(2),
                                        "Y".equals(rs.getString(3)),
                                        "Y".equals(rs.getString(4)),
                                        parameters.payEffect(rs.getString(1), today).orElse(null),
                                        time(rs.getTimestamp(5))));
        return new Paged<>(rows, page.page(), page.size(), RowCounts.of(total));
    }

    /** M-13 tiles: types received, encashable, pay-reducing (configured so today), last sync. */
    @Transactional(readOnly = true)
    public TypeCounts typeCounts(AccessContext context) {
        policy.require(context, "M-13", "VIEW");
        LocalDate today = LocalDate.now(clock.withZone(IST));
        List<String> codes =
                named.queryForList(
                        "SELECT leave_type_code FROM leave_type_reference",
                        new MapSqlParameterSource(),
                        String.class);
        long reducing =
                codes.stream()
                        .map(c -> parameters.payEffect(c, today).orElse(""))
                        .filter(Set.of("FULL_DEDUCTION", "HALF_PAY")::contains)
                        .count();
        return named.queryForObject(
                "SELECT COUNT(*), SUM(CASE WHEN is_encashable = 'Y' THEN 1 ELSE 0 END),"
                        + " MAX(last_received_at) FROM leave_type_reference",
                new MapSqlParameterSource(),
                (rs, i) ->
                        new TypeCounts(
                                rs.getLong(1), rs.getLong(2), reducing, time(rs.getTimestamp(3))));
    }

    /** I-11: the leave events of the session's offices, newest first, one page. */
    @Transactional(readOnly = true)
    public Paged<EventRow> events(AccessContext context, Filter filter, PageSpec page) {
        MapSqlParameterSource p = scoped(context, "VIEW", filter);
        Long total = named.queryForObject("SELECT COUNT(*)" + FROM + WHERE, p, Long.class);
        p.addValue("offset", (long) page.page() * page.size()).addValue("size", page.size());
        List<Raw> rows =
                named.query(
                        "SELECT a.application_id, a.hrms_sanction_ref, e.emp_num, e.first_name,"
                            + " e.last_name, l.location_code, l.location_name, a.leave_type_code,"
                            + " r.leave_type_name, a.applied_from, a.applied_to, a.days_applied,"
                            + " a.decision, t.received_at, x.wage_effect_code, "
                                + APPLIED
                                + STATUS
                                + " AS shown_status"
                                + FROM
                                + WHERE
                                + " ORDER BY a.application_id DESC OFFSET :offset ROWS FETCH NEXT"
                                + " :size ROWS ONLY",
                        p,
                        (rs, i) -> event(rs));
        return new Paged<>(
                appliedIn(rows, new LockTimes()), page.page(), page.size(), RowCounts.of(total));
    }

    /**
     * I-11 tiles: events received this month, applied (open period), back-dated, and the events
     * held as unmatched on X-05 (open entries).
     */
    @Transactional(readOnly = true)
    public EventCounts eventCounts(AccessContext context) {
        MapSqlParameterSource p = scoped(context, "VIEW", new Filter(null, null, null));
        LocalDate today = LocalDate.now(clock.withZone(IST));
        p.addValue("monthStart", Timestamp.valueOf(today.withDayOfMonth(1).atStartOfDay()));
        return named.queryForObject(
                "SELECT SUM(CASE WHEN t.received_at >= :monthStart THEN 1 ELSE 0 END), SUM(CASE"
                    + " WHEN x.leave_txn_id IS NOT NULL AND x.is_back_dated = 'N' THEN 1 ELSE 0"
                    + " END), SUM(CASE WHEN x.is_back_dated = 'Y' THEN 1 ELSE 0 END), (SELECT"
                    + " COUNT(*) FROM payroll_exception h WHERE h.exception_code ="
                    + " 'LEAVE_EVENT_INVALID' AND h.exception_status IN ('OPEN', 'IN_PROGRESS',"
                    + " 'ROUTED_UPSTREAM') AND (:all = 'Y' OR EXISTS (SELECT 1 FROM employee he"
                    + " WHERE he.employee_id = h.employee_id AND he.location_id IN (:locations))))"
                        + FROM
                        + WHERE,
                p,
                (rs, i) ->
                        new EventCounts(
                                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
    }

    // ---------------------------------------------------------------- exports (SRS-G-13)

    /** M-13 Export: the rows the file will carry (checked against the limit first). */
    @Transactional(readOnly = true)
    public long typesSize(AccessContext context) {
        policy.require(context, "M-13", "EXPORT");
        return RowCounts.of(
                named.queryForObject(
                        "SELECT COUNT(*) FROM leave_type_reference",
                        new MapSqlParameterSource(),
                        Long.class));
    }

    /**
     * M-13 Export (SRS M-13; the EXPORT grant of V046_2): every leave type with the columns the
     * screen shows, in code order, in one read-only snapshot checked against its count.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void exportTypes(AccessContext context, ExportSink sink) throws IOException {
        long expected = typesSize(context);
        LocalDate today = LocalDate.now(clock.withZone(IST));
        sink.header(
                "hrms_code", "leave_type", "encashable", "half_day", "pay_effect", "last_received");
        write(
                "SELECT leave_type_code, leave_type_name, is_encashable, supports_half_day,"
                        + " last_received_at FROM leave_type_reference ORDER BY leave_type_code",
                new MapSqlParameterSource(),
                rs ->
                        sink.row(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                parameters.payEffect(rs.getString(1), today).orElse(null),
                                time(rs.getTimestamp(5))));
        ExportSink.requireComplete(expected, sink);
    }

    /** I-11 Export: the rows the file will carry for the filters (checked first). */
    @Transactional(readOnly = true)
    public long eventsSize(AccessContext context, Filter filter) {
        return RowCounts.of(
                named.queryForObject(
                        "SELECT COUNT(*)" + FROM + WHERE,
                        scoped(context, "EXPORT", filter),
                        Long.class));
    }

    /**
     * I-11 Export (SRS I-11 Export, L4189): the events of the session's offices for the filters,
     * newest first as on screen, with the screen's columns, in one read-only snapshot checked
     * against its count.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void exportEvents(AccessContext context, Filter filter, ExportSink sink)
            throws IOException {
        long expected = eventsSize(context, filter);
        LockTimes locks = new LockTimes();
        sink.header(
                "hrms_sanction_ref",
                "eis",
                "member",
                "office",
                "leave_type",
                "from",
                "to",
                "days",
                "event",
                "received",
                "pay_effect",
                "rule",
                "applied_in",
                "status");
        write(
                "SELECT a.application_id, a.hrms_sanction_ref, e.emp_num, e.first_name,"
                        + " e.last_name, l.location_code, l.location_name, a.leave_type_code,"
                        + " r.leave_type_name, a.applied_from, a.applied_to, a.days_applied,"
                        + " a.decision, t.received_at, x.wage_effect_code, "
                        + APPLIED
                        + STATUS
                        + " AS shown_status"
                        + FROM
                        + WHERE
                        + " ORDER BY a.application_id DESC",
                scoped(context, "EXPORT", filter),
                rs -> {
                    EventRow e = appliedIn(List.of(event(rs)), locks).get(0);
                    sink.row(
                            e.sanctionRef(),
                            e.eis(),
                            e.name(),
                            e.office(),
                            e.leaveType(),
                            e.from(),
                            e.to(),
                            e.days(),
                            e.eventType(),
                            e.received(),
                            e.payEffect(),
                            e.rule(),
                            e.appliedIn(),
                            e.status());
                });
        ExportSink.requireComplete(expected, sink);
    }

    /** One export row from the result set. */
    @FunctionalInterface
    private interface RowWriter {
        void write(ResultSet rs) throws SQLException, IOException;
    }

    private void write(String sql, MapSqlParameterSource params, RowWriter writer)
            throws IOException {
        try {
            named.query(
                    sql,
                    params,
                    rs -> {
                        try {
                            writer.write(rs);
                        } catch (IOException failed) {
                            throw new UncheckedIOException(failed);
                        }
                    });
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The offices the session sees (every one at Headquarters), checked for the action. */
    private MapSqlParameterSource scoped(AccessContext context, String action, Filter filter) {
        policy.require(context, "I-11", action);
        return OfficeScope.of(context, policy.isAtHeadquarters(context))
                .addValue("eis", blank(filter.eis()), Types.VARCHAR)
                .addValue("type", blank(filter.leaveType()), Types.VARCHAR)
                .addValue("status", blank(filter.status()), Types.VARCHAR);
    }

    /** An event as read, with what its "Applied in" month is worked out from. */
    private record Raw(EventRow row, Long locationId, boolean backDated, Instant appliedAt) {}

    private static Raw event(ResultSet rs) throws SQLException {
        String first = rs.getString("first_name");
        String last = rs.getString("last_name");
        String name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).strip();
        String code = rs.getString("location_code");
        String office = code == null ? null : code + " - " + rs.getString("location_name");
        long location = rs.getLong("location_id");
        Long locationId = rs.wasNull() ? null : location;
        String effect = rs.getString("wage_effect_code");
        boolean backDated = "Y".equals(rs.getString("is_back_dated"));
        Date periodDate = rs.getDate("period_date");
        Timestamp appliedAt = rs.getTimestamp("applied_at");
        boolean applied = effect != null;
        EventRow row =
                new EventRow(
                        rs.getLong("application_id"),
                        rs.getString("hrms_sanction_ref"),
                        rs.getString("emp_num"),
                        name,
                        office,
                        rs.getString("leave_type_code"),
                        rs.getString("leave_type_name"),
                        date(rs.getDate("applied_from")),
                        date(rs.getDate("applied_to")),
                        rs.getBigDecimal("days_applied"),
                        "CANCELLED".equals(rs.getString("decision")) ? "CANCELLATION" : "ADDITION",
                        time(rs.getTimestamp("received_at")),
                        effect,
                        rs.getString("shown_status"),
                        !applied || backDated || periodDate == null
                                ? null
                                : YearMonth.from(periodDate.toLocalDate()).toString(),
                        applied ? LeaveViewRules.ruleOf(effect, backDated) : null,
                        rs.getBigDecimal("effect_amount"));
        return new Raw(
                row,
                locationId,
                applied && backDated,
                appliedAt == null ? null : appliedAt.toInstant());
    }

    /**
     * SRS I-11 "Applied in" (US-GAP-59): the cycle in which the effect applies. An event of an open
     * period applies in its own month; a back-dated one (BR-LEAVE-006, BR-LEAVE-010) in the open
     * cycle when it was approved: the approval's month, or the month after when that month's cycle
     * of the member's unit was already locked then (as {@link LeaveHrmsEffects} settled it).
     */
    private List<EventRow> appliedIn(List<Raw> rows, LockTimes locks) {
        locks.load(rows);
        return rows.stream()
                .map(
                        r -> {
                            if (!r.backDated() || r.appliedAt() == null) {
                                return r.row();
                            }
                            YearMonth decided = YearMonth.from(r.appliedAt().atZone(IST));
                            Instant locked = locks.lockedAt(unitOf(r.locationId()), decided);
                            EventRow e = r.row();
                            return new EventRow(
                                    e.applicationId(),
                                    e.sanctionRef(),
                                    e.eis(),
                                    e.name(),
                                    e.office(),
                                    e.leaveType(),
                                    e.leaveTypeName(),
                                    e.from(),
                                    e.to(),
                                    e.days(),
                                    e.eventType(),
                                    e.received(),
                                    e.payEffect(),
                                    e.status(),
                                    LeaveViewRules.appliedIn(decided, locked, r.appliedAt())
                                            .toString(),
                                    e.rule(),
                                    e.effectAmount());
                        })
                .toList();
    }

    private Long unitOf(Long locationId) {
        return locationId == null
                ? null
                : org.cycleUnitOf(locationId).map(OrgLocation::getId).orElse(null);
    }

    /**
     * When each (cycle unit, month) a back-dated event was approved in was locked: read in one
     * query per page, or per export for the pairs not yet asked.
     */
    private final class LockTimes {

        private final Map<String, Instant> known = new HashMap<>();
        private final Set<String> asked = new HashSet<>();

        void load(List<Raw> rows) {
            Set<Long> units = new HashSet<>();
            Set<String> months = new HashSet<>();
            for (Raw r : rows) {
                Long unit = r.backDated() && r.appliedAt() != null ? unitOf(r.locationId()) : null;
                if (unit != null) {
                    YearMonth m = YearMonth.from(r.appliedAt().atZone(IST));
                    if (asked.add(key(unit, m))) {
                        units.add(unit);
                        months.add(yymm(m));
                    }
                }
            }
            if (units.isEmpty()) {
                return;
            }
            named.query(
                    "SELECT org_unit_id, payroll_yymm, MIN(locked_at) AS locked_at FROM"
                            + " payroll_cycle_instance WHERE cycle_status = 'LOCKED' AND"
                            + " locked_at IS NOT NULL AND org_unit_id IN (:units) AND payroll_yymm"
                            + " IN (:months) GROUP BY org_unit_id, payroll_yymm",
                    new MapSqlParameterSource("units", units).addValue("months", months),
                    (ResultSet rs) -> {
                        known.put(
                                rs.getLong(1) + "|" + rs.getString(2),
                                rs.getTimestamp(3).toInstant());
                    });
        }

        Instant lockedAt(Long unit, YearMonth month) {
            return unit == null ? null : known.get(key(unit, month));
        }

        private static String key(long unit, YearMonth month) {
            return unit + "|" + yymm(month);
        }

        private static String yymm(YearMonth m) {
            return String.format("%04d%02d", m.getYear(), m.getMonthValue());
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static LocalDate date(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private static LocalDateTime time(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
