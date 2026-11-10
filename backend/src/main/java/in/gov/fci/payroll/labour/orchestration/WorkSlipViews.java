package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.labour.orchestration.AttendanceViews.AnnaCounts;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews.Filter;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews.GangOption;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of I-02 Work-Slip Capture as Wireframes v6.1 draws it (OD-121; SRS-2-4, SRS-2-27,
 * SRS-2-34, SRS-2-45; FRS V0.5 §8.4.3, §8.11.2, §8.11.3; DDL work_slip, work_slip_row,
 * labour_overtime): the Work-Slip Register (the DPS booking-slip register reads the same records,
 * SRS-2-34), overtime per person and the import queue, over the gangs of the payroll type in the
 * session's depots. Records from Anna Darpan are read as held in Payroll (PENDING API-23-4).
 */
@Service
@Loggable
public class WorkSlipViews {

    static final String SCREEN = "I-02";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private static final String SLIPS =
            " FROM work_slip s JOIN labour_gang g ON g.gang_id = s.gang_id JOIN org_location l ON"
                + " l.location_id = g.depot_location_id LEFT JOIN work_slip_row r ON r.work_slip_id"
                + " = s.work_slip_id LEFT JOIN operation_master o ON o.operation_id ="
                + " r.operation_id WHERE (:all = 'Y' OR l.location_id IN (:locations) OR"
                + " l.parent_location_id IN (:locations)) AND s.engine_code = :type AND"
                + " s.work_slip_date BETWEEN :first AND :last AND s.slip_status <> 'AMENDED' AND"
                + " (:gang IS NULL OR s.gang_id = :gang) AND (:day IS NULL OR s.work_slip_date ="
                + " :day) AND (:text IS NULL OR UPPER(s.work_slip_number) LIKE :text OR"
                + " UPPER(g.gang_code) LIKE :text OR UPPER(o.operation_name) LIKE :text)";

    /** The list's dates, gangs and the import queue's counts. */
    public record Overview(
            String month,
            long saved,
            long rejected,
            long pendingBooking,
            AnnaCounts anna,
            List<GangOption> gangs,
            List<LocalDate> dates) {}

    /** One row of work of a slip; a slip refused on import has none. */
    public record RegisterRow(
            long slipId,
            int rowOrder,
            String number,
            long gangId,
            String gangCode,
            LocalDate date,
            String bagType,
            String operationCode,
            String operation,
            Integer bags,
            String start,
            String end,
            String normType,
            boolean continueWork,
            long labours,
            String status,
            String source,
            String duplicateOf,
            String reason,
            String enteredBy,
            String decidedBy,
            Instant decidedAt) {}

    /**
     * One person's overtime (SRS-2-45), or a Present But No Work row (SRS-2-42), which has no
     * overtime and carries {@code pbnwReason}.
     */
    public record OvertimeRow(
            String key,
            String labourCode,
            String name,
            String gangCode,
            boolean pool,
            String operationCode,
            String operation,
            String workingAs,
            String start,
            String end,
            BigDecimal hours,
            Long bags,
            String minimumMet,
            boolean ancillary,
            String pbnwReason) {}

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final AttendanceViews attendance;

    public WorkSlipViews(
            NamedParameterJdbcTemplate named, Authorization policy, AttendanceViews attendance) {
        this.named = named;
        this.policy = policy;
        this.attendance = attendance;
    }

    /** I-02: the toolbar's lists and the import queue of the payroll type and month. */
    @Transactional(readOnly = true)
    public Overview overview(AccessContext context, Filter filter, YearMonth month) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = attendance.params(context, filter, month);
        Map<String, Object> counts =
                named.queryForMap(
                        "SELECT COALESCE(SUM(CASE WHEN s.slip_status NOT IN ('AMENDED',"
                                + " 'CANCELLED', 'REJECTED', 'DUPLICATE_HELD') THEN 1 ELSE 0 END),"
                                + " 0) AS saved, COALESCE(SUM(CASE WHEN s.slip_status = 'REJECTED'"
                                + " THEN 1 ELSE 0 END), 0) AS rejected, COALESCE(SUM(CASE WHEN"
                                + " s.engine_code = 'DPS_LABOUR' AND s.slip_status IN ('DRAFT',"
                                + " 'SUBMITTED') THEN 1 ELSE 0 END), 0) AS pending FROM work_slip s"
                                + " WHERE"
                                + " s.work_slip_date BETWEEN :first AND :last AND s.gang_id IN"
                                + " (SELECT g.gang_id"
                                + AttendanceViews.GANGS
                                + ")",
                        p);
        List<LocalDate> dates =
                named.query(
                        "SELECT DISTINCT s.work_slip_date FROM work_slip s WHERE s.work_slip_date"
                                + " BETWEEN :first AND :last AND s.gang_id IN (SELECT g.gang_id"
                                + AttendanceViews.GANGS
                                + ") ORDER BY s.work_slip_date DESC",
                        p,
                        (rs, i) -> rs.getDate(1).toLocalDate());
        return new Overview(
                month.toString(),
                ((Number) counts.get("SAVED")).longValue(),
                ((Number) counts.get("REJECTED")).longValue(),
                ((Number) counts.get("PENDING")).longValue(),
                attendance.anna(p),
                attendance.gangOptions(p),
                dates);
    }

    /** I-02 Work-Slip Register: the slips' rows of the month, newest first. */
    @Transactional(readOnly = true)
    public Paged<RegisterRow> register(
            AccessContext context, Filter filter, YearMonth month, LocalDate day, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = slipParams(context, filter, month, day);
        Long total = named.queryForObject("SELECT COUNT(*)" + SLIPS, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        return new Paged<>(
                rows(p, " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY"),
                spec.page(),
                spec.size(),
                RowCounts.of(total));
    }

    /** I-02 overtime per person and Present But No Work rows of the month (SRS-2-45, SRS-2-42). */
    @Transactional(readOnly = true)
    public Paged<OvertimeRow> overtime(
            AccessContext context, Filter filter, YearMonth month, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = attendance.params(context, filter, month);
        String scope =
                " JOIN labour_gang g ON g.gang_id = %s JOIN org_location l ON l.location_id ="
                    + " g.depot_location_id JOIN employee e ON e.employee_id = %s JOIN"
                    + " labour_profile lp ON lp.employee_id = e.employee_id LEFT JOIN"
                    + " labour_gang_membership m ON m.gang_id = g.gang_id AND m.employee_id ="
                    + " e.employee_id AND m.effective_from <= :last AND (m.effective_to IS NULL OR"
                    + " m.effective_to >= :first) WHERE (:all = 'Y' OR l.location_id IN"
                    + " (:locations) OR l.parent_location_id IN (:locations)) AND g.payroll_type ="
                    + " :type AND (:gang IS NULL OR g.gang_id = :gang) AND (:text IS NULL OR"
                    + " UPPER(lp.labour_number) LIKE :text OR UPPER(g.gang_code) LIKE :text)";
        String ids =
                "'OT-' || ot.overtime_id AS row_key, lp.labour_number, e.first_name, e.last_name,"
                    + " g.gang_code, g.gang_type, o.operation_code, o.operation_name, m.gang_role,"
                    + " ot.start_at, ot.end_at, ot.normal_rate_hours + ot.double_rate_hours AS"
                    + " hours, ot.bags_in_ot_hours, ot.min_bags_met, ot.is_ancillary,";
        String overtime =
                "SELECT "
                        + ids
                        + " CAST(NULL AS VARCHAR2(20)) AS pbnw_reason, ot.ot_date AS on_day FROM"
                        + " labour_overtime ot JOIN operation_master o ON o.operation_id ="
                        + " ot.operation_id"
                        + scope.formatted("ot.gang_id", "ot.employee_id")
                        + " AND ot.period_date = :first AND ot.ot_status <> 'CANCELLED'";
        String pbnw =
                "SELECT 'PB-' || r.slip_row_id || '-' || wp.employee_id AS row_key,"
                    + " lp.labour_number, e.first_name, e.last_name, g.gang_code, g.gang_type,"
                    + " o.operation_code, o.operation_name, m.gang_role, CAST(NULL AS TIMESTAMP) AS"
                    + " start_at, CAST(NULL AS TIMESTAMP) AS end_at, CAST(NULL AS NUMBER(6,2)) AS"
                    + " hours, CAST(NULL AS NUMBER(10)) AS bags_in_ot_hours, CAST(NULL AS CHAR(1))"
                    + " AS min_bags_met, 'N' AS is_ancillary, r.pbnw_reason, s.work_slip_date AS"
                    + " on_day FROM work_slip_row r JOIN work_slip s ON s.work_slip_id ="
                    + " r.work_slip_id JOIN work_slip_participant wp ON wp.work_slip_id ="
                    + " s.work_slip_id JOIN operation_master o ON o.operation_id = r.operation_id"
                        + scope.formatted("s.gang_id", "wp.employee_id")
                        + " AND o.is_present_but_no_work = 'Y' AND s.work_slip_date BETWEEN :first"
                        + " AND :last AND s.slip_status NOT IN ('AMENDED', 'CANCELLED')";
        String union = overtime + " UNION ALL " + pbnw;
        Long total = named.queryForObject("SELECT COUNT(*) FROM (" + union + ") u", p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<OvertimeRow> rows =
                named.query(
                        "SELECT * FROM ("
                                + union
                                + ") u ORDER BY u.on_day DESC, u.labour_number, u.row_key"
                                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                        p,
                        (rs, i) -> overtimeRow(rs));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** The rows the export carries, for the size check before streaming. */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context, Filter filter, YearMonth month, LocalDate day) {
        policy.require(context, SCREEN, "EXPORT");
        Long total =
                named.queryForObject(
                        "SELECT COUNT(*)" + SLIPS,
                        slipParams(context, filter, month, day),
                        Long.class);
        return total == null ? 0 : total;
    }

    /** The Work-Slip Register as an export (SRS-G-13). */
    @Transactional(readOnly = true)
    public void export(
            AccessContext context, Filter filter, YearMonth month, LocalDate day, ExportSink sink)
            throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        sink.header(
                "Work-slip",
                "Gang",
                "Date",
                "Bag type",
                "Operation",
                "No. of bags",
                "Start",
                "End",
                "Labours",
                "Status");
        for (RegisterRow r : rows(slipParams(context, filter, month, day), "")) {
            sink.row(
                    r.number(),
                    r.gangCode(),
                    r.date(),
                    r.bagType(),
                    r.operation(),
                    r.bags(),
                    r.start(),
                    r.end(),
                    r.labours(),
                    r.status());
        }
    }

    private MapSqlParameterSource slipParams(
            AccessContext context, Filter filter, YearMonth month, LocalDate day) {
        return attendance
                .params(context, filter, month)
                .addValue("day", day == null ? null : Date.valueOf(day), Types.DATE);
    }

    private List<RegisterRow> rows(MapSqlParameterSource p, String window) {
        return named.query(
                "SELECT s.work_slip_id, COALESCE(r.row_order, 1) AS row_order,"
                        + " s.work_slip_number, s.gang_id, g.gang_code, s.work_slip_date,"
                        + " b.bag_type_name, o.operation_code, o.operation_name, r.number_of_bags,"
                        + " r.start_time, r.end_time, r.norm_type, s.continue_work_flag, (SELECT"
                        + " COUNT(*) FROM work_slip_participant wp WHERE wp.work_slip_id ="
                        + " s.work_slip_id) AS labours, s.slip_status, s.source_code,"
                        + " s.rejection_reason, s.created_by, q.decided_by, q.decided_at, (SELECT"
                        + " d.work_slip_number FROM work_slip d WHERE d.work_slip_id ="
                        + " s.duplicate_of_slip_id) AS duplicate_of"
                        + SLIPS.replace(
                                " WHERE (:all",
                                " LEFT JOIN bag_type b ON b.bag_type_id = r.bag_type_id LEFT JOIN"
                                        + " approval_queue q ON q.approval_id = s.approval_id WHERE"
                                        + " (:all")
                        + " ORDER BY s.work_slip_date DESC, s.work_slip_number, row_order"
                        + window,
                p,
                (rs, i) -> registerRow(rs));
    }

    private static RegisterRow registerRow(ResultSet rs) throws SQLException {
        int bags = rs.getInt("number_of_bags");
        boolean noBags = rs.wasNull();
        Timestamp decided = rs.getTimestamp("decided_at");
        return new RegisterRow(
                rs.getLong("work_slip_id"),
                rs.getInt("row_order"),
                rs.getString("work_slip_number"),
                rs.getLong("gang_id"),
                rs.getString("gang_code"),
                rs.getDate("work_slip_date").toLocalDate(),
                rs.getString("bag_type_name"),
                rs.getString("operation_code"),
                rs.getString("operation_name"),
                noBags ? null : bags,
                rs.getString("start_time"),
                rs.getString("end_time"),
                rs.getString("norm_type"),
                "Y".equals(rs.getString("continue_work_flag")),
                rs.getLong("labours"),
                rs.getString("slip_status"),
                rs.getString("source_code"),
                rs.getString("duplicate_of"),
                rs.getString("rejection_reason"),
                rs.getString("created_by"),
                rs.getString("decided_by"),
                decided == null ? null : decided.toInstant());
    }

    private static OvertimeRow overtimeRow(ResultSet rs) throws SQLException {
        long bags = rs.getLong("bags_in_ot_hours");
        boolean noBags = rs.wasNull();
        return new OvertimeRow(
                rs.getString("row_key"),
                rs.getString("labour_number"),
                AttendanceViews.name(rs.getString("first_name"), rs.getString("last_name")),
                rs.getString("gang_code"),
                "ANCILLARY_POOL".equals(rs.getString("gang_type")),
                rs.getString("operation_code"),
                rs.getString("operation_name"),
                rs.getString("gang_role"),
                clock(rs.getTimestamp("start_at")),
                clock(rs.getTimestamp("end_at")),
                rs.getBigDecimal("hours"),
                noBags ? null : bags,
                rs.getString("min_bags_met"),
                "Y".equals(rs.getString("is_ancillary")),
                rs.getString("pbnw_reason"));
    }

    private static String clock(Timestamp at) {
        return at == null ? null : at.toLocalDateTime().format(CLOCK);
    }
}
