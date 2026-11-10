package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.ChangeType;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.labour.business.AttendanceRules;
import in.gov.fci.payroll.labour.business.AttendanceRules.BatchState;
import in.gov.fci.payroll.labour.business.AttendanceRules.Code;
import in.gov.fci.payroll.labour.business.AttendanceRules.MonthCount;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.error.RecordedRefusal;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * I-01 Labour Attendance Capture (plan 20 phase 20b; SRS-2-2, SRS-2-3, SRS-2-29, SRS-2-35,
 * SRS-2-38, SRS-3-1; BR-LABM-004, BR-LABM-005; D20-7, D20-8). The Labour Dealing Hand of the depot
 * marks a gang's day in the code set of the gang's payroll type: the grid opens blank, every member
 * of the gang that day is marked before the day is saved, a labour past the retirement date is not
 * marked, and a labour has one status a day across all gangs and units. A refused attempt to mark
 * over another gang's mark, past retirement or over a filed work-slip is logged ({@code
 * attendance_amendment_block}) and kept. When the month is over, the gang's month is submitted as
 * one batch (route I01_ATTENDANCE) to the Payroll Checker of the Divisional Office; approved, the
 * month's counts are derived from the daily marks (never typed, never above the calendar days) and
 * the attendance is usable for computation; returned, it may be changed and submitted again.
 */
@Service
@Loggable
public class AttendanceService {

    static final String SCREEN = "I-01";
    static final String ENTITY = "labour_attendance_batch";
    private static final String DPS = "DPS_LABOUR";
    static final String ROUTE = "I01_ATTENDANCE";

    /** One code of the gang's set. */
    public record CodeRow(
            String code, String meaning, BigDecimal weight, boolean leave, boolean confirmed) {}

    /** One day's mark of a member; {@code otherGang} when it was marked in another gang. */
    public record DayMark(LocalDate date, String code, boolean otherGang, boolean workSlipFiled) {}

    /** A member of the gang in the month, with the marks and the month's derived counts. */
    public record MemberGrid(
            long employeeId,
            String labourCode,
            String name,
            String role,
            LocalDate from,
            LocalDate to,
            LocalDate retirementDate,
            List<DayMark> marks,
            int marked,
            BigDecimal presentDays,
            BigDecimal leaveDays,
            BigDecimal absentDays) {}

    /**
     * The depot's Attendance Source in force (SRS-2-36 l.4392; I-01 field table l.4543; DDL {@code
     * attendance_source_master}, kept by Headquarters): manual, biometric or both, the Anna Darpan
     * flag, the status (pilot, live, suspended), the fallback to manual and the start date. Shown
     * read-only; null when Headquarters has recorded no source for the depot ("not configured").
     */
    public record AttendanceSource(
            String mode,
            boolean annaDarpan,
            String status,
            boolean fallbackToManual,
            LocalDate effectiveFrom) {}

    /**
     * The I-01 grid of a gang's month; {@code returnReason} is the Payroll Checker's reason of a
     * RETURNED batch (SRS-2-29 l.4381, SRS l.4516), else null; {@code attendanceSource} is the
     * depot's source in force on the grid's day (SRS-2-36), null when not configured.
     */
    public record Grid(
            long gangId,
            String gangName,
            String depot,
            String payrollType,
            String yymm,
            LocalDate lastMarkable,
            String batchState,
            Long approvalId,
            String returnReason,
            Integer sanctionedStrength,
            List<CodeRow> codes,
            List<MemberGrid> members,
            AttendanceSource attendanceSource) {}

    /** One member's mark of a day. */
    public record Mark(long employeeId, String code) {}

    /**
     * What a saved day wrote.
     *
     * @param markedAbsent the labour codes whose first Sunday of a split week was marked absent
     *     (SRS-4-5, DPS)
     */
    public record Saved(
            LocalDate date, int marked, int added, int changed, List<String> markedAbsent) {}

    private record Member(long employeeId, String labourCode, LocalDate retirementDate) {}

    private record CodeId(long id, Code code) {}

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final LabourGangs gangs;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final SystemParameters parameters;
    private final AuditService audit;
    private final Clock clock;
    private final GangStrengthService strengths;

    public AttendanceService(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate named,
            LabourGangs gangs,
            Authorization policy,
            MakerChecker approvals,
            SystemParameters parameters,
            AuditService audit,
            Clock clock,
            GangStrengthService strengths) {
        this.jdbc = jdbc;
        this.named = named;
        this.gangs = gangs;
        this.policy = policy;
        this.approvals = approvals;
        this.parameters = parameters;
        this.audit = audit;
        this.clock = clock;
        this.strengths = strengths;
    }

    /** I-01: the gang's month: members, marks, derived counts and the batch state. */
    @Transactional(readOnly = true)
    public Grid grid(AccessContext context, long gangId, YearMonth month) {
        LabourGangs.Gang g = gangs.workGang(gangId);
        policy.requireRecordAt(context, SCREEN, "VIEW", g.depotId());
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        Map<String, CodeId> codes = codes(g.payrollType(), last);
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT m.employee_id, m.gang_role, m.effective_from, m.effective_to,"
                                + " p.labour_number, e.first_name, e.last_name, e.retirement_date"
                                + " FROM labour_gang_membership m JOIN employee e ON e.employee_id"
                                + " = m.employee_id JOIN labour_profile p ON p.employee_id ="
                                + " m.employee_id WHERE m.gang_id = ? AND m.effective_from <= ?"
                                + " AND (m.effective_to IS NULL OR m.effective_to >= ?) ORDER BY"
                                + " CASE m.gang_role WHEN 'SARDAR' THEN 0 WHEN 'MANDAL' THEN 1 ELSE"
                                + " 2 END, p.labour_number",
                        gangId,
                        Date.valueOf(last),
                        Date.valueOf(first));
        Map<Long, List<Map<String, Object>>> marks =
                marksOf(rows.stream().map(r -> number(r.get("EMPLOYEE_ID"))).toList(), first, last);
        List<MemberGrid> members = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long employee = number(r.get("EMPLOYEE_ID"));
            List<DayMark> days = new ArrayList<>();
            List<Code> counted = new ArrayList<>();
            for (Map<String, Object> m : marks.getOrDefault(employee, List.of())) {
                days.add(
                        new DayMark(
                                date(m.get("ATTENDANCE_DATE")),
                                (String) m.get("ATTENDANCE_CODE"),
                                number(m.get("GANG_ID")) != gangId,
                                "Y".equals(m.get("WORK_SLIP_FILED_FLAG"))));
                counted.add(codeOf(m));
            }
            MonthCount count = AttendanceRules.month(month, counted);
            String lastName = (String) r.get("LAST_NAME");
            members.add(
                    new MemberGrid(
                            employee,
                            (String) r.get("LABOUR_NUMBER"),
                            lastName == null || lastName.isBlank()
                                    ? (String) r.get("FIRST_NAME")
                                    : r.get("FIRST_NAME") + " " + lastName,
                            (String) r.get("GANG_ROLE"),
                            date(r.get("EFFECTIVE_FROM")),
                            r.get("EFFECTIVE_TO") == null ? null : date(r.get("EFFECTIVE_TO")),
                            r.get("RETIREMENT_DATE") == null
                                    ? null
                                    : date(r.get("RETIREMENT_DATE")),
                            days,
                            count.marked(),
                            count.present(),
                            count.leave(),
                            count.absent()));
        }
        LocalDate lastMarkable = AttendanceRules.lastMarkable(month, parameters.today());
        LabourGangs.Batch batch = gangs.batch(ENTITY, gangId, month);
        return new Grid(
                gangId,
                g.name(),
                g.depot(),
                g.payrollType(),
                AttendanceRules.yymm(month),
                lastMarkable,
                batch.state().name(),
                batch.approvalId(),
                batch.state() == AttendanceRules.BatchState.RETURNED ? batch.returnReason() : null,
                strength(gangId, lastMarkable.isBefore(first) ? first : lastMarkable),
                codes.values().stream()
                        .map(
                                c ->
                                        new CodeRow(
                                                c.code().code(),
                                                meaning(g.payrollType(), c.code().code()),
                                                c.code().weight(),
                                                c.code().leave(),
                                                c.code().confirmed()))
                        .toList(),
                members,
                attendanceSource(g.depotId(), lastMarkable.isBefore(first) ? first : lastMarkable));
    }

    /**
     * I-01: the gang's day, every member marked (SRS-2-35). A mark over another gang's mark, past
     * retirement or over a filed work-slip is refused and the attempt is kept (RecordedRefusal).
     */
    @Transactional(noRollbackFor = RecordedRefusal.class)
    public Saved saveDay(AccessContext context, long gangId, LocalDate day, List<Mark> input) {
        LabourGangs.Gang g = gangs.workGang(gangId);
        policy.requireRecordAt(context, SCREEN, "CREATE", g.depotId());
        if (day == null || input == null) {
            throw invalid("DAY");
        }
        if (!g.activeOn(day)) {
            throw invalid("GANG_NOT_ACTIVE");
        }
        YearMonth month = YearMonth.from(day);
        BatchState state = gangs.batch(ENTITY, gangId, month).state();
        if (!state.editable()) {
            throw new FciException(
                    LabourErrorCode.STATE,
                    new Object[] {
                        AttendanceRules.batchKey(gangId, month), state, "OPEN or RETURNED"
                    },
                    List.of(),
                    null);
        }
        Map<String, CodeId> codes = codes(g.payrollType(), day);
        if (codes.isEmpty()) {
            throw new FciException(
                    LabourErrorCode.NOT_CONFIGURED,
                    new Object[] {"Attendance codes of " + g.payrollType()},
                    List.of(),
                    null);
        }
        Map<Long, Member> members = membersOn(gangId, day);
        Map<Long, String> marks = new LinkedHashMap<>();
        for (Mark m : input) {
            if (marks.put(m.employeeId(), m.code() == null ? null : m.code().strip()) != null) {
                throw invalid("MARKED_TWICE " + m.employeeId());
            }
        }
        // BR-LABM-004 / SRS-2-2: no attendance past the retirement date.
        Set<Long> expected = new HashSet<>();
        for (Member m : members.values()) {
            if (m.retirementDate() != null && day.isAfter(m.retirementDate())) {
                if (marks.containsKey(m.employeeId())) {
                    block(
                            context,
                            m.employeeId(),
                            day,
                            marks.get(m.employeeId()),
                            "PAST_RETIREMENT");
                    throw new RecordedRefusal(
                            LabourErrorCode.BLOCKED,
                            new Object[] {m.labourCode(), day, "PAST_RETIREMENT"});
                }
            } else {
                expected.add(m.employeeId());
            }
        }
        List<String> markedAbsent = splitWeekSunday(g, day, expected, members, marks, codes);
        AttendanceRules.dayDefect(day, parameters.today(), expected, marks, codes.keySet())
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        Map<Long, Map<String, Object>> existing = existing(day, expected);
        for (Map.Entry<Long, Map<String, Object>> e : existing.entrySet()) {
            Map<String, Object> row = e.getValue();
            String asked = marks.get(e.getKey());
            if (number(row.get("GANG_ID")) != gangId) {
                // SRS-2-38: one status per labour per date across all gangs and units.
                block(context, e.getKey(), day, asked, "ALREADY_MARKED_OTHER_GANG");
                throw new RecordedRefusal(
                        LabourErrorCode.MARKED,
                        new Object[] {
                            members.get(e.getKey()).labourCode(),
                            row.get("ATTENDANCE_CODE"),
                            day,
                            row.get("GANG_NAME"),
                            row.get("LOCATION_CODE")
                        });
            }
            if ("Y".equals(row.get("WORK_SLIP_FILED_FLAG"))
                    && !asked.equals(row.get("ATTENDANCE_CODE"))) {
                // SRS-2-3 / BR-LABM-005: after the work-slip, only the back-dated correction.
                block(context, e.getKey(), day, asked, "WORK_SLIP_FILED");
                throw new RecordedRefusal(
                        LabourErrorCode.BLOCKED,
                        new Object[] {
                            members.get(e.getKey()).labourCode(), day, "WORK_SLIP_FILED"
                        });
            }
        }
        int added = 0;
        int changed = 0;
        List<AuditService.Entry> trail = new ArrayList<>();
        for (Map.Entry<Long, String> m : marks.entrySet()) {
            CodeId code = codes.get(m.getValue());
            Map<String, Object> row = existing.get(m.getKey());
            String key = m.getKey() + ":" + day;
            if (row == null) {
                jdbc.update(
                        "INSERT INTO labour_attendance (employee_id, gang_id, depot_location_id,"
                                + " attendance_date, attendance_code_id, attendance_code,"
                                + " engine_code, source_code, capture_mode) VALUES (?, ?, ?, ?, ?,"
                                + " ?, ?, 'ENT', 'MANUAL')",
                        m.getKey(),
                        gangId,
                        g.depotId(),
                        Date.valueOf(day),
                        code.id(),
                        m.getValue(),
                        g.payrollType());
                added++;
                trail.add(
                        entry(
                                key,
                                null,
                                m.getValue(),
                                "Attendance marked",
                                ChangeType.INITIAL_VALUE,
                                day));
            } else if (!m.getValue().equals(row.get("ATTENDANCE_CODE"))) {
                jdbc.update(
                        "UPDATE labour_attendance SET attendance_code_id = ?, attendance_code = ?,"
                                + " amended_flag = 'Y' WHERE attendance_id = ?",
                        code.id(),
                        m.getValue(),
                        number(row.get("ATTENDANCE_ID")));
                changed++;
                trail.add(
                        entry(
                                key,
                                (String) row.get("ATTENDANCE_CODE"),
                                m.getValue(),
                                "Attendance changed before the batch",
                                ChangeType.CHANGE,
                                day));
            }
        }
        audit.recordAll(context.loginId(), trail);
        // SRS-2-48: the day's present strength against the Sanctioned Strength (alert or close).
        strengths.checkDay(context.loginId(), g, day);
        return new Saved(day, marks.size(), added, changed, markedAbsent);
    }

    /**
     * SRS-2-29: the gang's month as one batch for the Payroll Checker of the Divisional Office,
     * once the month is over and every member is marked for every day of the month in the gang.
     */
    @Transactional
    public SubmittedApproval submit(AccessContext context, long gangId, YearMonth month) {
        LabourGangs.Gang g = gangs.workGang(gangId);
        policy.requireRecordAt(context, SCREEN, "SUBMIT", g.depotId());
        LocalDate today = parameters.today();
        if (month == null || !month.atEndOfMonth().isBefore(today)) {
            throw invalid("MONTH_NOT_OVER");
        }
        BatchState state = gangs.batch(ENTITY, gangId, month).state();
        if (!state.editable()) {
            throw new FciException(
                    LabourErrorCode.STATE,
                    new Object[] {
                        AttendanceRules.batchKey(gangId, month), state, "OPEN or RETURNED"
                    },
                    List.of(),
                    null);
        }
        LocalDate start = month.atDay(1).isBefore(g.from()) ? g.from() : month.atDay(1);
        LocalDate end =
                g.to() != null && g.to().isBefore(month.atEndOfMonth())
                        ? g.to()
                        : month.atEndOfMonth();
        if (start.isAfter(end)) {
            throw invalid("GANG_NOT_ACTIVE");
        }
        // SRS-2-35 over the month: every member of the gang that day has its mark in this gang.
        int missing = unmarked(gangId, start, end);
        if (missing > 0) {
            throw invalid("UNMARKED_DAYS " + missing);
        }
        for (Map.Entry<Long, MonthCount> c : counts(gangId, month).entrySet()) {
            if (!AttendanceRules.withinCalendar(c.getValue())) {
                throw invalid("CALENDAR_EXCEEDED " + c.getKey());
            }
        }
        String key = AttendanceRules.batchKey(gangId, month);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                ROUTE,
                                SCREEN,
                                "SUBMIT",
                                ENTITY,
                                key,
                                ActionType.UPDATE,
                                g.depotId(),
                                "{\"gangId\":"
                                        + gangId
                                        + ",\"yymm\":\""
                                        + AttendanceRules.yymm(month)
                                        + "\"}",
                                month.atDay(1),
                                "Attendance of "
                                        + g.name()
                                        + " for "
                                        + AttendanceRules.yymm(month)),
                        null);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                key,
                                "batch_state",
                                state.name(),
                                "SUBMITTED",
                                "Batch submitted")
                        .approval(item.id())
                        .module(SCREEN)
                        .effective(month.atDay(1)));
        return item;
    }

    /**
     * The Payroll Checker's decision: approved, the month's counts of each member are derived from
     * the daily marks (SRS-2-38); returned or rejected, the marks may change again.
     */
    @EventListener
    @Transactional
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY)) {
            return;
        }
        String[] key = decision.entityKey().split(":");
        long gangId = Long.parseLong(key[0]);
        YearMonth month =
                YearMonth.of(
                        Integer.parseInt(key[1].substring(0, 4)),
                        Integer.parseInt(key[1].substring(4)));
        String outcome = decision.approved() ? "APPROVED" : "RETURNED";
        if (decision.approved()) {
            for (Map.Entry<Long, MonthCount> c : counts(gangId, month).entrySet()) {
                derive(c.getKey(), month, c.getValue());
            }
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                decision.entityKey(),
                                "batch_state",
                                "SUBMITTED",
                                outcome,
                                decision.reason() == null ? outcome : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN)
                        .effective(month.atDay(1)));
    }

    // ---------------------------------------------------------------- reads

    /** The payroll type's code set in force on a day, by code. */
    private Map<String, CodeId> codes(String payrollType, LocalDate day) {
        Map<String, CodeId> codes = new LinkedHashMap<>();
        jdbc.query(
                "SELECT attendance_code_id, attendance_code, present_day_weight, is_leave_code,"
                        + " is_confirmed FROM attendance_code_master WHERE engine_code = ? AND"
                        + " effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)"
                        + " ORDER BY attendance_code_id",
                rs -> {
                    codes.put(
                            rs.getString("attendance_code"),
                            new CodeId(
                                    rs.getLong("attendance_code_id"),
                                    new Code(
                                            rs.getString("attendance_code"),
                                            rs.getBigDecimal("present_day_weight"),
                                            "Y".equals(rs.getString("is_leave_code")),
                                            "Y".equals(rs.getString("is_confirmed")))));
                },
                payrollType,
                Date.valueOf(day),
                Date.valueOf(day));
        return codes;
    }

    private String meaning(String payrollType, String code) {
        return jdbc.queryForObject(
                "SELECT MAX(code_meaning) FROM attendance_code_master WHERE engine_code = ? AND"
                        + " attendance_code = ?",
                String.class,
                payrollType,
                code);
    }

    private Map<Long, Member> membersOn(long gangId, LocalDate day) {
        Map<Long, Member> members = new LinkedHashMap<>();
        jdbc.query(
                "SELECT m.employee_id, p.labour_number, e.retirement_date FROM"
                        + " labour_gang_membership m JOIN employee e ON e.employee_id ="
                        + " m.employee_id JOIN labour_profile p ON p.employee_id = m.employee_id"
                        + " WHERE m.gang_id = ? AND m.effective_from <= ? AND (m.effective_to IS"
                        + " NULL OR m.effective_to >= ?)",
                rs -> {
                    java.sql.Date retire = rs.getDate("retirement_date");
                    members.put(
                            rs.getLong("employee_id"),
                            new Member(
                                    rs.getLong("employee_id"),
                                    rs.getString("labour_number"),
                                    retire == null ? null : retire.toLocalDate()));
                },
                gangId,
                Date.valueOf(day),
                Date.valueOf(day));
        return members;
    }

    /**
     * SRS-4-5 (DPS): on save of the first Sunday of a split week, a labour absent on every day of
     * the week in the previous month is marked absent on the Sunday, whatever was entered.
     */
    private List<String> splitWeekSunday(
            LabourGangs.Gang g,
            LocalDate day,
            Set<Long> expected,
            Map<Long, Member> members,
            Map<Long, String> marks,
            Map<String, CodeId> codes) {
        List<LocalDate> split =
                DPS.equals(g.payrollType()) ? AttendanceRules.splitWeekDays(day) : List.of();
        if (split.isEmpty() || !codes.containsKey(AttendanceRules.ABSENT)) {
            return List.of();
        }
        Map<Long, List<Map<String, Object>>> before =
                marksOf(new ArrayList<>(expected), split.get(0), split.get(split.size() - 1));
        List<String> absent = new ArrayList<>();
        for (Long id : expected) {
            Map<LocalDate, String> byDay = new HashMap<>();
            before.getOrDefault(id, List.of())
                    .forEach(
                            r ->
                                    byDay.put(
                                            date(r.get("ATTENDANCE_DATE")),
                                            (String) r.get("ATTENDANCE_CODE")));
            if (marks.containsKey(id)
                    && AttendanceRules.absentThroughout(split, byDay)
                    && !AttendanceRules.ABSENT.equals(marks.get(id))) {
                marks.put(id, AttendanceRules.ABSENT);
                absent.add(members.get(id).labourCode());
            }
        }
        return absent;
    }

    /** The marks of the members on the day, in any gang, by member. */
    private Map<Long, Map<String, Object>> existing(LocalDate day, Set<Long> employees) {
        Map<Long, Map<String, Object>> existing = new HashMap<>();
        if (employees.isEmpty()) {
            return existing;
        }
        named.queryForList(
                        "SELECT a.attendance_id, a.employee_id, a.attendance_code, a.gang_id,"
                                + " a.work_slip_filed_flag, g.gang_name, l.location_code FROM"
                                + " labour_attendance a LEFT JOIN labour_gang g ON g.gang_id ="
                                + " a.gang_id LEFT JOIN org_location l ON l.location_id ="
                                + " a.depot_location_id WHERE a.attendance_date = :day AND"
                                + " a.employee_id IN (:ids)",
                        new MapSqlParameterSource()
                                .addValue("day", Date.valueOf(day))
                                .addValue("ids", employees))
                .forEach(r -> existing.put(number(r.get("EMPLOYEE_ID")), r));
        return existing;
    }

    private Map<Long, List<Map<String, Object>>> marksOf(
            List<Long> employees, LocalDate first, LocalDate last) {
        Map<Long, List<Map<String, Object>>> marks = new HashMap<>();
        if (employees.isEmpty()) {
            return marks;
        }
        named.queryForList(
                        "SELECT a.employee_id, a.attendance_date, a.attendance_code, a.gang_id,"
                                + " a.work_slip_filed_flag, c.present_day_weight, c.is_leave_code,"
                                + " c.is_confirmed FROM labour_attendance a JOIN"
                                + " attendance_code_master c ON c.attendance_code_id ="
                                + " a.attendance_code_id WHERE a.employee_id IN (:ids) AND"
                                + " a.attendance_date BETWEEN :first AND :last ORDER BY"
                                + " a.attendance_date",
                        new MapSqlParameterSource()
                                .addValue("ids", employees)
                                .addValue("first", Date.valueOf(first))
                                .addValue("last", Date.valueOf(last)))
                .forEach(
                        r ->
                                marks.computeIfAbsent(
                                                number(r.get("EMPLOYEE_ID")),
                                                k -> new ArrayList<>())
                                        .add(r));
        return marks;
    }

    /** The month's counts of every member of the gang in the month, from all their marks. */
    private Map<Long, MonthCount> counts(long gangId, YearMonth month) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        List<Long> employees =
                jdbc.queryForList(
                        "SELECT DISTINCT employee_id FROM labour_gang_membership WHERE gang_id = ?"
                                + " AND effective_from <= ? AND (effective_to IS NULL OR"
                                + " effective_to >= ?)",
                        Long.class,
                        gangId,
                        Date.valueOf(last),
                        Date.valueOf(first));
        Map<Long, List<Map<String, Object>>> marks = marksOf(employees, first, last);
        Map<Long, MonthCount> counts = new LinkedHashMap<>();
        for (Long e : employees) {
            counts.put(
                    e,
                    AttendanceRules.month(
                            month,
                            marks.getOrDefault(e, List.of()).stream()
                                    .map(AttendanceService::codeOf)
                                    .toList()));
        }
        return counts;
    }

    /** The member-days of the gang between two dates that have no mark in the gang. */
    private int unmarked(long gangId, LocalDate start, LocalDate end) {
        List<Map<String, Object>> spans =
                jdbc.queryForList(
                        "SELECT m.employee_id, m.effective_from, m.effective_to, e.retirement_date"
                            + " FROM labour_gang_membership m JOIN employee e ON e.employee_id ="
                            + " m.employee_id WHERE m.gang_id = ? AND m.effective_from <= ? AND"
                            + " (m.effective_to IS NULL OR m.effective_to >= ?)",
                        gangId,
                        Date.valueOf(end),
                        Date.valueOf(start));
        Set<String> marked = new HashSet<>();
        jdbc.query(
                "SELECT employee_id, attendance_date FROM labour_attendance WHERE gang_id = ? AND"
                        + " attendance_date BETWEEN ? AND ?",
                rs -> {
                    marked.add(rs.getLong(1) + ":" + rs.getDate(2).toLocalDate());
                },
                gangId,
                Date.valueOf(start),
                Date.valueOf(end));
        int missing = 0;
        for (Map<String, Object> span : spans) {
            LocalDate from = later(start, date(span.get("EFFECTIVE_FROM")));
            LocalDate to =
                    span.get("EFFECTIVE_TO") == null
                            ? end
                            : earlier(end, date(span.get("EFFECTIVE_TO")));
            if (span.get("RETIREMENT_DATE") != null) {
                to = earlier(to, date(span.get("RETIREMENT_DATE")));
            }
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (!marked.contains(number(span.get("EMPLOYEE_ID")) + ":" + d)) {
                    missing++;
                }
            }
        }
        return missing;
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate earlier(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private Integer strength(long gangId, LocalDate day) {
        return jdbc
                .queryForList(
                        "SELECT sanctioned_strength FROM labour_gang_strength WHERE gang_id = ? AND"
                            + " effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)"
                            + " ORDER BY effective_from DESC",
                        Integer.class,
                        gangId,
                        Date.valueOf(day),
                        Date.valueOf(day))
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * SRS-2-36: the depot's Attendance Source row in force on the day (one row a depot and start
     * date, DDL {@code uk_att_source_master}); read only, Headquarters keeps the master.
     */
    private AttendanceSource attendanceSource(long depotId, LocalDate day) {
        return jdbc
                .queryForList(
                        "SELECT attendance_mode, anna_darpan_flag, source_status,"
                                + " fallback_to_manual, effective_from FROM"
                                + " attendance_source_master WHERE location_id = ? AND"
                                + " effective_from <= ? AND (effective_to IS NULL OR effective_to"
                                + " >= ?) ORDER BY effective_from DESC, att_source_id DESC",
                        depotId,
                        Date.valueOf(day),
                        Date.valueOf(day))
                .stream()
                .findFirst()
                .map(
                        r ->
                                new AttendanceSource(
                                        (String) r.get("ATTENDANCE_MODE"),
                                        "Y".equals(r.get("ANNA_DARPAN_FLAG")),
                                        (String) r.get("SOURCE_STATUS"),
                                        "Y".equals(r.get("FALLBACK_TO_MANUAL")),
                                        date(r.get("EFFECTIVE_FROM"))))
                .orElse(null);
    }

    // ---------------------------------------------------------------- writes

    /** DDL {@code labour_attendance_month}: the derived counts of the month (one row a month). */
    private void derive(long employeeId, YearMonth month, MonthCount c) {
        String yymm = AttendanceRules.yymm(month);
        Timestamp now = Timestamp.from(clock.instant());
        int updated =
                jdbc.update(
                        "UPDATE labour_attendance_month SET marked_days = ?, present_days = ?,"
                                + " leave_days = ?, absent_days = ?, derived_at = ? WHERE"
                                + " employee_id = ? AND payroll_yymm = ?",
                        c.marked(),
                        c.present(),
                        c.leave(),
                        c.absent(),
                        now,
                        employeeId,
                        yymm);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO labour_attendance_month (employee_id, payroll_yymm, period_date,"
                            + " calendar_days, marked_days, present_days, leave_days, absent_days,"
                            + " derived_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    employeeId,
                    yymm,
                    Date.valueOf(month.atDay(1)),
                    c.calendarDays(),
                    c.marked(),
                    c.present(),
                    c.leave(),
                    c.absent(),
                    now);
        }
    }

    /** DDL {@code attendance_amendment_block}: each blocked attempt is logged (BR-LABM-005). */
    private void block(
            AccessContext context, long employeeId, LocalDate day, String code, String why) {
        jdbc.update(
                "INSERT INTO attendance_amendment_block (employee_id, attendance_date,"
                    + " attempted_code, block_reason, attempted_by, attempted_at) VALUES (?, ?, ?,"
                    + " ?, ?, ?)",
                employeeId,
                Date.valueOf(day),
                code,
                why,
                context.loginId(),
                Timestamp.from(clock.instant()));
    }

    private static AuditService.Entry entry(
            String key, String before, String after, String why, ChangeType type, LocalDate day) {
        return AuditService.Entry.change(
                        "labour_attendance", key, "attendance_code", before, after, why)
                .type(type)
                .module(SCREEN)
                .effective(day);
    }

    private static Code codeOf(Map<String, Object> mark) {
        return new Code(
                (String) mark.get("ATTENDANCE_CODE"),
                (BigDecimal) mark.get("PRESENT_DAY_WEIGHT"),
                "Y".equals(mark.get("IS_LEAVE_CODE")),
                "Y".equals(mark.get("IS_CONFIRMED")));
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static LocalDate date(Object value) {
        return value instanceof java.sql.Date d
                ? d.toLocalDate()
                : new java.sql.Date(((java.util.Date) value).getTime()).toLocalDate();
    }

    private static FciException invalid(String defect) {
        return new FciException(LabourErrorCode.ATTENDANCE, new Object[] {defect}, List.of(), null);
    }
}
