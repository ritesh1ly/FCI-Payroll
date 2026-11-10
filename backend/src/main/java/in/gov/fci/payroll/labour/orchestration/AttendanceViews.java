package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.labour.business.AttendanceRules;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
import in.gov.fci.payroll.labour.business.LabourRules;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of I-01 Labour Attendance Capture as Wireframes v6.1 draws it (OD-121; SRS-2-2,
 * SRS-2-27, SRS-2-35, SRS-2-36, SRS-2-37, SRS-2-38, SRS-2-40, SRS-2-57; FRS V0.5 §8.11.1; DDL
 * labour_attendance, attendance_source_master, biometric_enrolment, biometric_punch,
 * attendance_punch_exception, labour_leave_application): the tiles, the gang-wise month summary,
 * the day's blank entry across the gangs of the payroll type, the labour leave list and the
 * Attendance Source master. Biometric punches and Anna Darpan records are read as held in Payroll:
 * neither source has an API yet (PENDING API-23-4, API-23-5). Every read is limited to the
 * session's depots (a depot, or its parent office); the Attendance Source master is the one
 * India-level master that depots see read-only (SRS-2-36).
 */
@Service
@Loggable
public class AttendanceViews {

    static final String SCREEN = "I-01";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final int GANG_OPTIONS = 200;

    /** The gangs of the payroll type in the session's depots working in the period. */
    static final String GANGS =
            " FROM labour_gang g JOIN org_location l ON l.location_id = g.depot_location_id WHERE"
                    + " (:all = 'Y' OR l.location_id IN (:locations) OR l.parent_location_id IN"
                    + " (:locations)) AND g.payroll_type = :type AND g.gang_type IN ('HANDLING',"
                    + " 'ANCILLARY_POOL') AND g.effective_from <= :last AND (g.effective_to IS NULL"
                    + " OR g.effective_to >= :first) AND (:gang IS NULL OR g.gang_id = :gang) AND"
                    + " (:text IS NULL OR UPPER(g.gang_code) LIKE :text OR UPPER(g.gang_name) LIKE"
                    + " :text)";

    /** The criteria of a list: the payroll type, a gang (optional) and the list search. */
    public record Filter(String payrollType, Long gangId, String text) {}

    /** A gang of the toolbar's filter. */
    public record GangOption(long id, String code, String name, String type) {}

    /** A code of the payroll type's set. */
    public record CodeOption(String code, String meaning) {}

    /** The counts of the Anna Darpan strip (SRS-2-27, SRS-INT-39), from what Payroll holds. */
    public record AnnaCounts(long received, long confirmed, long returned) {}

    /**
     * The tiles and the controls of the month. {@code records} and {@code withSlip} count the marks
     * of the batches the Payroll Checker approved (SRS-2-29); the others count every mark in scope.
     */
    public record Overview(
            String month,
            long records,
            long withSlip,
            long lwpDays,
            long exceptions,
            long pastRetirement,
            long overCalendar,
            AnnaCounts anna,
            List<CodeOption> codes,
            List<GangOption> gangs) {}

    /** One gang's month. {@code counts} holds the number of marks by attendance code. */
    public record GangRow(
            long gangId,
            String gangCode,
            String gangName,
            String depot,
            int labour,
            int calendarDays,
            Map<String, Long> counts,
            BigDecimal presentDays,
            boolean overCalendar,
            String status) {}

    /**
     * A labour's day. {@code code} is the mark held for the day in this gang; {@code otherGang} and
     * {@code otherDepot} name a mark held in another gang (SRS-2-38); {@code sourceMode} is the
     * depot's Attendance Source of the day, {@code exception} an open punch exception.
     */
    public record DayRow(
            long employeeId,
            String labourCode,
            String name,
            long gangId,
            String gangCode,
            String code,
            String captureMode,
            boolean depotConfirmed,
            boolean workSlipFiled,
            String sourceMode,
            String punchIn,
            String punchOut,
            String exception,
            String otherCode,
            String otherGang,
            String otherDepot,
            boolean pastRetirement) {}

    /** One labour leave action (SRS-2-57). */
    public record LeaveRow(
            long id,
            String labourCode,
            String name,
            String origin,
            LocalDate from,
            LocalDate to,
            String fromCode,
            String toCode,
            boolean backDated,
            LocalDate raisedOn,
            String status) {}

    /** One office of the Attendance Source master with its enrolled labour (SRS-2-36, SRS-2-37). */
    public record SourceRow(
            String depot,
            String mode,
            boolean annaDarpan,
            String status,
            LocalDate effectiveFrom,
            long enrolled) {}

    /** A gang's marks of the month, added up. */
    private static final class Totals {
        private final Map<String, Long> counts = new LinkedHashMap<>();
        private final Set<Long> labour = new HashSet<>();
        private BigDecimal present = BigDecimal.ZERO;
        private long marks;
        private long filed;
        private long unfiled;
        private long lwp;
        private long pastRetirement;
        private long punchExceptions;
        private long expected;

        long gaps() {
            return marks == 0 ? 0 : Math.max(0, expected - marks);
        }

        boolean overCalendar(int calendarDays) {
            return present.compareTo(BigDecimal.valueOf((long) labour.size() * calendarDays)) > 0;
        }

        String status(int calendarDays) {
            if (marks == 0) {
                return "NOT_MARKED";
            }
            if (pastRetirement > 0
                    || punchExceptions > 0
                    || gaps() > 0
                    || overCalendar(calendarDays)) {
                return "EXCEPTION";
            }
            return unfiled > 0 ? "WORK_SLIP_MISSING" : "READY";
        }
    }

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final SystemParameters parameters;

    public AttendanceViews(
            NamedParameterJdbcTemplate named, Authorization policy, SystemParameters parameters) {
        this.named = named;
        this.policy = policy;
        this.parameters = parameters;
    }

    /** I-01: the tiles and the toolbar's lists of the payroll type and month. */
    @Transactional(readOnly = true)
    public Overview overview(AccessContext context, Filter filter, YearMonth month) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = params(context, filter, month);
        Map<Long, Totals> totals = totals(GANG_IDS, p, month);
        Set<Long> approved = approvedGangs(month);
        long records = 0;
        long withSlip = 0;
        long lwp = 0;
        long exceptions = 0;
        long pastRetirement = 0;
        long overCalendar = 0;
        for (Map.Entry<Long, Totals> e : totals.entrySet()) {
            Totals t = e.getValue();
            if (approved.contains(e.getKey())) {
                records += t.marks;
                withSlip += t.filed;
            }
            lwp += t.lwp;
            pastRetirement += t.pastRetirement;
            boolean over = t.overCalendar(month.lengthOfMonth());
            overCalendar += over ? 1 : 0;
            exceptions += t.pastRetirement + t.punchExceptions + t.gaps() + (over ? 1 : 0);
        }
        return new Overview(
                month.toString(),
                records,
                withSlip,
                lwp,
                exceptions,
                pastRetirement,
                overCalendar,
                anna(p),
                codes(filter.payrollType(), month),
                gangOptions(p));
    }

    /** I-01 Gang Attendance Summary (FRS Annexure A.7): one row per gang of the month. */
    @Transactional(readOnly = true)
    public Paged<GangRow> gangSummary(
            AccessContext context, Filter filter, YearMonth month, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = params(context, filter, month);
        Long total = named.queryForObject("SELECT COUNT(*)" + GANGS, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<GangRow> rows = gangRows(p, month, " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY");
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** The rows the export carries, for the size check before streaming. */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context, Filter filter, YearMonth month) {
        policy.require(context, SCREEN, "EXPORT");
        Long total =
                named.queryForObject(
                        "SELECT COUNT(*)" + GANGS, params(context, filter, month), Long.class);
        return total == null ? 0 : total;
    }

    /** The Gang Attendance Summary as an export (SRS-G-13): every gang of the filter. */
    @Transactional(readOnly = true)
    public void export(AccessContext context, Filter filter, YearMonth month, ExportSink sink)
            throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        sink.header("Gang", "Gang name", "Depot", "Labour", "Calendar days", "Marks by code");
        for (GangRow r : gangRows(params(context, filter, month), month, "")) {
            sink.row(
                    r.gangCode(),
                    r.gangName(),
                    r.depot(),
                    r.labour(),
                    r.calendarDays(),
                    r.counts().toString());
        }
    }

    /**
     * I-01 daily attendance (SRS-2-35, SRS-2-37, SRS-2-38): the labour of the gangs on the day with
     * the day's mark, or nothing where it is still to be marked. The screen never fills a blank.
     */
    @Transactional(readOnly = true)
    public Paged<DayRow> daily(AccessContext context, Filter filter, LocalDate day, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        requireType(filter.payrollType());
        LocalDate shown = day == null ? parameters.today() : day;
        MapSqlParameterSource p = params(context, filter, YearMonth.from(shown));
        p.addValue("day", Date.valueOf(shown));
        String from =
                " FROM labour_gang_membership m JOIN labour_gang g ON g.gang_id = m.gang_id JOIN"
                    + " org_location l ON l.location_id = g.depot_location_id JOIN employee e ON"
                    + " e.employee_id = m.employee_id JOIN labour_profile lp ON lp.employee_id ="
                    + " m.employee_id WHERE (:all = 'Y' OR l.location_id IN (:locations) OR"
                    + " l.parent_location_id IN (:locations)) AND g.payroll_type = :type AND"
                    + " g.gang_type IN ('HANDLING', 'ANCILLARY_POOL') AND m.effective_from <= :day"
                    + " AND (m.effective_to IS NULL OR m.effective_to >= :day) AND (:gang IS NULL"
                    + " OR g.gang_id = :gang) AND (:text IS NULL OR UPPER(lp.labour_number) LIKE"
                    + " :text OR UPPER(g.gang_code) LIKE :text OR UPPER(e.first_name) LIKE :text OR"
                    + " UPPER(e.last_name) LIKE :text)";
        Long total = named.queryForObject("SELECT COUNT(*)" + from, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<Map<String, Object>> members =
                named.queryForList(
                        "SELECT m.employee_id, m.gang_id, g.gang_code, g.depot_location_id,"
                                + " lp.labour_number, e.first_name, e.last_name, e.retirement_date"
                                + from
                                + " ORDER BY g.gang_code, lp.labour_number"
                                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                        p);
        List<DayRow> rows = members.isEmpty() ? List.of() : dayRows(members, shown);
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** I-01 labour leave (SRS-2-57): the actions on the labour of the depots, newest first. */
    @Transactional(readOnly = true)
    public Paged<LeaveRow> leave(AccessContext context, Filter filter, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        requireType(filter.payrollType());
        MapSqlParameterSource p = params(context, filter, YearMonth.from(parameters.today()));
        String from =
                " FROM labour_leave_application a JOIN labour_leave_type t ON"
                    + " t.labour_leave_type_id = a.labour_leave_type_id JOIN labour_profile lp ON"
                    + " lp.labour_profile_id = a.labour_profile_id JOIN employee e ON e.employee_id"
                    + " = a.employee_id JOIN org_location l ON l.location_id = lp.depot_location_id"
                    + " LEFT JOIN labour_leave_application c ON c.labour_leave_app_id ="
                    + " a.converted_from_app_id LEFT JOIN labour_leave_type ct ON"
                    + " ct.labour_leave_type_id = c.labour_leave_type_id WHERE (:all = 'Y' OR"
                    + " l.location_id IN (:locations) OR l.parent_location_id IN (:locations)) AND"
                    + " t.payroll_type = :type AND (:text IS NULL OR UPPER(lp.labour_number) LIKE"
                    + " :text OR UPPER(e.first_name) LIKE :text OR UPPER(e.last_name) LIKE :text)";
        Long total = named.queryForObject("SELECT COUNT(*)" + from, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<LeaveRow> rows =
                named.query(
                        "SELECT a.labour_leave_app_id, lp.labour_number, e.first_name, e.last_name,"
                                + " a.entry_origin, a.leave_from, a.leave_to, a.is_back_dated,"
                                + " a.created_at, a.application_status, t.leave_type_code,"
                                + " ct.leave_type_code AS from_code"
                                + from
                                + " ORDER BY a.created_at DESC, a.labour_leave_app_id DESC"
                                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                        p,
                        (rs, i) ->
                                new LeaveRow(
                                        rs.getLong("labour_leave_app_id"),
                                        rs.getString("labour_number"),
                                        name(rs.getString("first_name"), rs.getString("last_name")),
                                        rs.getString("entry_origin"),
                                        rs.getDate("leave_from").toLocalDate(),
                                        rs.getDate("leave_to").toLocalDate(),
                                        rs.getString("from_code"),
                                        rs.getString("leave_type_code"),
                                        "Y".equals(rs.getString("is_back_dated")),
                                        rs.getTimestamp("created_at")
                                                .toLocalDateTime()
                                                .toLocalDate(),
                                        rs.getString("application_status")));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /**
     * I-01 Attendance Source master (SRS-2-36): the record in force at each office, read-only
     * everywhere on this screen, with the labour enrolled on devices (SRS-2-37).
     */
    @Transactional(readOnly = true)
    public Paged<SourceRow> sources(AccessContext context, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p =
                new MapSqlParameterSource().addValue("today", Date.valueOf(parameters.today()));
        String from =
                " FROM attendance_source_master s JOIN org_location l ON l.location_id ="
                        + " s.location_id WHERE s.effective_from <= :today AND (s.effective_to IS"
                        + " NULL OR s.effective_to >= :today)";
        Long total = named.queryForObject("SELECT COUNT(*)" + from, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<SourceRow> rows =
                named.query(
                        "SELECT l.location_code, l.location_name, s.attendance_mode,"
                                + " s.anna_darpan_flag, s.source_status, s.effective_from, (SELECT"
                                + " COUNT(DISTINCT b.employee_id) FROM biometric_enrolment b WHERE"
                                + " b.location_id = s.location_id AND b.effective_from <= :today"
                                + " AND (b.effective_to IS NULL OR b.effective_to >= :today)) AS"
                                + " enrolled"
                                + from
                                + " ORDER BY l.location_code"
                                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                        p,
                        (rs, i) ->
                                new SourceRow(
                                        rs.getString("location_code")
                                                + " - "
                                                + rs.getString("location_name"),
                                        rs.getString("attendance_mode"),
                                        "Y".equals(rs.getString("anna_darpan_flag")),
                                        rs.getString("source_status"),
                                        rs.getDate("effective_from").toLocalDate(),
                                        rs.getLong("enrolled")));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    // ---------------------------------------------------------------- shared with WorkSlipViews

    /** The named parameters of a list: the scope, the payroll type, the month and the criteria. */
    MapSqlParameterSource params(AccessContext context, Filter filter, YearMonth month) {
        requireType(filter.payrollType());
        return OfficeScope.of(context, policy.isAtHeadquarters(context))
                .addValue("type", filter.payrollType())
                .addValue("gang", filter.gangId(), Types.BIGINT)
                .addValue("text", like(filter.text()), Types.VARCHAR)
                .addValue("first", Date.valueOf(month.atDay(1)))
                .addValue("last", Date.valueOf(month.atEndOfMonth()));
    }

    /** The gangs of the toolbar's filter (OD-123: from the master), at most {@code 200}. */
    List<GangOption> gangOptions(MapSqlParameterSource p) {
        // The filter lists every gang of the type, whichever one the list is narrowed to.
        p.addValue("options", GANG_OPTIONS)
                .addValue("gang", null, Types.BIGINT)
                .addValue("text", null, Types.VARCHAR);
        return named.query(
                "SELECT g.gang_id, g.gang_code, g.gang_name, g.gang_type"
                        + GANGS
                        + " ORDER BY g.gang_code FETCH FIRST :options ROWS ONLY",
                p,
                (rs, i) ->
                        new GangOption(
                                rs.getLong("gang_id"),
                                rs.getString("gang_code"),
                                rs.getString("gang_name"),
                                rs.getString("gang_type")));
    }

    /**
     * The Anna Darpan strip: records held with source Anna Darpan, attendance and work-slips. A
     * mark counts as confirmed once the depot confirmed it; a returned record is a work-slip
     * refused on import.
     */
    AnnaCounts anna(MapSqlParameterSource p) {
        Map<String, Object> marks =
                named.queryForMap(
                        "SELECT COUNT(*) AS received, COALESCE(SUM(CASE WHEN"
                                + " a.depot_confirmed_flag = 'Y' THEN 1 ELSE 0 END), 0) AS"
                                + " confirmed FROM labour_attendance a WHERE a.capture_mode ="
                                + " 'ANNA_DARPAN' AND a.attendance_date BETWEEN :first AND :last"
                                + " AND a.gang_id IN (SELECT g.gang_id"
                                + GANGS
                                + ")",
                        p);
        Map<String, Object> slips =
                named.queryForMap(
                        "SELECT COUNT(*) AS received, COALESCE(SUM(CASE WHEN s.slip_status ="
                                + " 'REJECTED' THEN 1 ELSE 0 END), 0) AS returned FROM work_slip s"
                                + " WHERE s.source_code = 'INTF' AND s.work_slip_date BETWEEN"
                                + " :first AND :last AND s.gang_id IN (SELECT g.gang_id"
                                + GANGS
                                + ")",
                        p);
        long slipsIn = count(slips.get("RECEIVED"));
        long returned = count(slips.get("RETURNED"));
        return new AnnaCounts(
                count(marks.get("RECEIVED")) + slipsIn,
                count(marks.get("CONFIRMED")) + slipsIn - returned,
                returned);
    }

    static String name(String first, String last) {
        return last == null || last.isBlank() ? first : first + " " + last;
    }

    static String like(String text) {
        return text == null || text.isBlank()
                ? null
                : "%" + text.strip().toUpperCase(Locale.ROOT) + "%";
    }

    static void requireType(String type) {
        if (type == null || !LabourRules.PAYROLL_TYPES.contains(type)) {
            throw new FciException(
                    LabourErrorCode.ATTENDANCE, new Object[] {"PAYROLL_TYPE"}, List.of(), null);
        }
    }

    // ---------------------------------------------------------------- the month's figures

    private static final String GANG_IDS = "(SELECT g.gang_id" + GANGS + ")";

    private List<GangRow> gangRows(MapSqlParameterSource p, YearMonth month, String window) {
        List<Map<String, Object>> gangs =
                named.queryForList(
                        "SELECT g.gang_id, g.gang_code, g.gang_name, l.location_code,"
                                + " l.location_name"
                                + GANGS
                                + " ORDER BY g.gang_code"
                                + window,
                        p);
        if (gangs.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource ids =
                new MapSqlParameterSource()
                        .addValue("first", p.getValue("first"))
                        .addValue("last", p.getValue("last"))
                        .addValue("ids", gangs.stream().map(g -> count(g.get("GANG_ID"))).toList());
        Map<Long, Totals> totals = totals("(:ids)", ids, month);
        List<GangRow> rows = new ArrayList<>();
        for (Map<String, Object> g : gangs) {
            long id = count(g.get("GANG_ID"));
            Totals t = totals.getOrDefault(id, new Totals());
            int calendar = month.lengthOfMonth();
            rows.add(
                    new GangRow(
                            id,
                            (String) g.get("GANG_CODE"),
                            (String) g.get("GANG_NAME"),
                            g.get("LOCATION_CODE") + " - " + g.get("LOCATION_NAME"),
                            t.labour.size(),
                            calendar,
                            t.counts,
                            t.present,
                            t.overCalendar(calendar),
                            t.status(calendar)));
        }
        return rows;
    }

    /**
     * Adds a month's marks up by gang. {@code gangs} is a subquery or a bound list of gang ids. The
     * expected days of a gap run from each membership's first day in the month to its last,
     * retirement and yesterday included (today may still be open).
     */
    private Map<Long, Totals> totals(String gangs, MapSqlParameterSource p, YearMonth month) {
        Map<Long, Totals> byGang = new HashMap<>();
        named.query(
                "SELECT a.gang_id, a.attendance_code, COUNT(*) AS marks, SUM(c.present_day_weight)"
                    + " AS present, SUM(CASE WHEN c.is_lwp = 'Y' THEN 1 ELSE 0 END) AS lwp,"
                    + " SUM(CASE WHEN a.work_slip_filed_flag = 'Y' THEN 1 ELSE 0 END) AS filed,"
                    + " SUM(CASE WHEN c.counts_toward_present = 'Y' AND a.work_slip_filed_flag ="
                    + " 'N' THEN 1 ELSE 0 END) AS unfiled, SUM(CASE WHEN e.retirement_date <"
                    + " a.attendance_date THEN 1 ELSE 0 END) AS past FROM labour_attendance a JOIN"
                    + " attendance_code_master c ON c.attendance_code_id = a.attendance_code_id"
                    + " JOIN employee e ON e.employee_id = a.employee_id WHERE a.attendance_date"
                    + " BETWEEN :first AND :last AND a.gang_id IN "
                        + gangs
                        + " GROUP BY a.gang_id, a.attendance_code",
                p,
                rs -> {
                    Totals t = byGang.computeIfAbsent(rs.getLong("gang_id"), k -> new Totals());
                    t.counts.put(rs.getString("attendance_code"), rs.getLong("marks"));
                    t.present = t.present.add(rs.getBigDecimal("present"));
                    t.marks += rs.getLong("marks");
                    t.filed += rs.getLong("filed");
                    t.unfiled += rs.getLong("unfiled");
                    t.lwp += rs.getLong("lwp");
                    t.pastRetirement += rs.getLong("past");
                });
        LocalDate yesterday = parameters.today().minusDays(1);
        LocalDate lastDay =
                month.atEndOfMonth().isBefore(yesterday) ? month.atEndOfMonth() : yesterday;
        named.query(
                "SELECT m.gang_id, m.employee_id, m.effective_from, m.effective_to,"
                        + " e.retirement_date FROM labour_gang_membership m JOIN employee e ON"
                        + " e.employee_id = m.employee_id WHERE m.effective_from <= :last AND"
                        + " (m.effective_to IS NULL OR m.effective_to >= :first) AND m.gang_id IN "
                        + gangs,
                p,
                rs -> {
                    Totals t = byGang.computeIfAbsent(rs.getLong("gang_id"), k -> new Totals());
                    t.labour.add(rs.getLong("employee_id"));
                    LocalDate from = rs.getDate("effective_from").toLocalDate();
                    Date to = rs.getDate("effective_to");
                    Date retires = rs.getDate("retirement_date");
                    t.expected +=
                            AttendanceRules.markableDays(
                                    month,
                                    from,
                                    to == null ? null : to.toLocalDate(),
                                    retires == null ? null : retires.toLocalDate(),
                                    lastDay);
                });
        named.query(
                "SELECT m.gang_id, COUNT(*) AS open_exceptions FROM attendance_punch_exception x"
                    + " JOIN labour_gang_membership m ON m.employee_id = x.employee_id AND"
                    + " m.effective_from <= x.attendance_date AND (m.effective_to IS NULL OR"
                    + " m.effective_to >= x.attendance_date) WHERE x.exception_status = 'OPEN' AND"
                    + " x.attendance_date BETWEEN :first AND :last AND m.gang_id IN "
                        + gangs
                        + " GROUP BY m.gang_id",
                p,
                rs -> {
                    byGang.computeIfAbsent(rs.getLong("gang_id"), k -> new Totals())
                                    .punchExceptions =
                            rs.getLong("open_exceptions");
                });
        return byGang;
    }

    /** The gangs whose month batch the Payroll Checker approved (SRS-2-29). */
    private Set<Long> approvedGangs(YearMonth month) {
        String suffix = ":" + AttendanceRules.yymm(month);
        Set<Long> gangs = new HashSet<>();
        named.query(
                "SELECT q.entity_key FROM approval_queue q WHERE q.entity_name = :entity AND"
                        + " q.status = 'APPROVED' AND q.entity_key LIKE :key",
                new MapSqlParameterSource()
                        .addValue("entity", AttendanceService.ENTITY)
                        .addValue("key", "%" + suffix),
                rs -> {
                    String key = rs.getString("entity_key");
                    gangs.add(Long.valueOf(key.substring(0, key.length() - suffix.length())));
                });
        return gangs;
    }

    private List<CodeOption> codes(String payrollType, YearMonth month) {
        return named.query(
                "SELECT attendance_code, code_meaning FROM attendance_code_master WHERE engine_code"
                        + " = :type AND effective_from <= :last AND (effective_to IS NULL OR"
                        + " effective_to >= :first) ORDER BY attendance_code",
                new MapSqlParameterSource()
                        .addValue("type", payrollType)
                        .addValue("first", Date.valueOf(month.atDay(1)))
                        .addValue("last", Date.valueOf(month.atEndOfMonth())),
                (rs, i) ->
                        new CodeOption(
                                rs.getString("attendance_code"), rs.getString("code_meaning")));
    }

    // ---------------------------------------------------------------- the day's rows

    private List<DayRow> dayRows(List<Map<String, Object>> members, LocalDate day) {
        List<Long> employees = members.stream().map(m -> count(m.get("EMPLOYEE_ID"))).toList();
        List<Long> depots =
                members.stream().map(m -> count(m.get("DEPOT_LOCATION_ID"))).distinct().toList();
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("ids", employees)
                        .addValue("depots", depots)
                        .addValue("day", Date.valueOf(day))
                        .addValue("next", Date.valueOf(day.plusDays(1)))
                        .addValue("period", Date.valueOf(day.withDayOfMonth(1)));
        Map<Long, Map<String, Object>> marks = new HashMap<>();
        named.queryForList(
                        "SELECT a.employee_id, a.gang_id, a.attendance_code, a.capture_mode,"
                                + " a.depot_confirmed_flag, a.work_slip_filed_flag, g.gang_code,"
                                + " l.location_code FROM labour_attendance a LEFT JOIN labour_gang"
                                + " g ON g.gang_id = a.gang_id LEFT JOIN org_location l ON"
                                + " l.location_id = a.depot_location_id WHERE a.attendance_date ="
                                + " :day AND a.employee_id IN (:ids)",
                        p)
                .forEach(m -> marks.put(count(m.get("EMPLOYEE_ID")), m));
        Map<Long, String[]> punches = new HashMap<>();
        named.query(
                "SELECT employee_id, punch_direction, MIN(punch_at) AS first_at, MAX(punch_at) AS"
                        + " last_at FROM biometric_punch WHERE period_date = :period AND punch_at"
                        + " >= :day AND punch_at < :next AND employee_id IN (:ids) GROUP BY"
                        + " employee_id, punch_direction",
                p,
                rs -> {
                    String[] pair =
                            punches.computeIfAbsent(rs.getLong("employee_id"), k -> new String[2]);
                    boolean in = "IN".equals(rs.getString("punch_direction"));
                    pair[in ? 0 : 1] = clock(rs.getTimestamp(in ? "first_at" : "last_at"));
                });
        Map<Long, String> exceptions = new HashMap<>();
        named.query(
                "SELECT employee_id, MIN(exception_type) AS exception_type FROM"
                        + " attendance_punch_exception WHERE attendance_date = :day AND"
                        + " exception_status = 'OPEN' AND employee_id IN (:ids) GROUP BY"
                        + " employee_id",
                p,
                rs -> {
                    exceptions.put(rs.getLong("employee_id"), rs.getString("exception_type"));
                });
        Map<Long, String> modes = new HashMap<>();
        named.query(
                "SELECT location_id, attendance_mode, source_status, fallback_to_manual FROM"
                        + " attendance_source_master WHERE effective_from <= :day AND (effective_to"
                        + " IS NULL OR effective_to >= :day) AND location_id IN (:depots)",
                p,
                rs -> {
                    boolean manual =
                            "Y".equals(rs.getString("fallback_to_manual"))
                                    || "SUSPENDED".equals(rs.getString("source_status"));
                    modes.put(
                            rs.getLong("location_id"),
                            manual ? "MANUAL" : rs.getString("attendance_mode"));
                });
        List<DayRow> rows = new ArrayList<>();
        for (Map<String, Object> m : members) {
            long employee = count(m.get("EMPLOYEE_ID"));
            long gang = count(m.get("GANG_ID"));
            Map<String, Object> mark = marks.get(employee);
            boolean own = mark != null && count(mark.get("GANG_ID")) == gang;
            boolean other = mark != null && !own;
            String[] punch = punches.getOrDefault(employee, new String[2]);
            rows.add(
                    new DayRow(
                            employee,
                            (String) m.get("LABOUR_NUMBER"),
                            name((String) m.get("FIRST_NAME"), (String) m.get("LAST_NAME")),
                            gang,
                            (String) m.get("GANG_CODE"),
                            own ? (String) mark.get("ATTENDANCE_CODE") : null,
                            own ? (String) mark.get("CAPTURE_MODE") : null,
                            own && "Y".equals(mark.get("DEPOT_CONFIRMED_FLAG")),
                            own && "Y".equals(mark.get("WORK_SLIP_FILED_FLAG")),
                            modes.getOrDefault(count(m.get("DEPOT_LOCATION_ID")), "MANUAL"),
                            punch[0],
                            punch[1],
                            exceptions.get(employee),
                            other ? (String) mark.get("ATTENDANCE_CODE") : null,
                            other ? (String) mark.get("GANG_CODE") : null,
                            other ? (String) mark.get("LOCATION_CODE") : null,
                            m.get("RETIREMENT_DATE") instanceof java.util.Date retires
                                    && day.isAfter(new Date(retires.getTime()).toLocalDate())));
        }
        return rows;
    }

    private static String clock(Timestamp at) {
        return at == null ? null : at.toLocalDateTime().format(CLOCK);
    }

    private static long count(Object value) {
        return ((Number) value).longValue();
    }
}
