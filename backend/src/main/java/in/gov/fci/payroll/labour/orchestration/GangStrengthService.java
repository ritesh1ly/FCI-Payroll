package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.labour.business.GangStrengthRules;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gang strength and shortfall (plan 20, group g5 G5-09; SRS-2-48 l.4403; I-01 l.4545; BR-LABM-011;
 * PENDING B-15 / LBR-20e-3; OD-90). Each day a handling gang's attendance is saved, its present
 * strength is compared with the Sanctioned Strength in force; a shortfall is raised to the Depot
 * Manager as the X-05 WARNING {@value GangStrengthRules#SHORTFALL} (gang, absent members, number
 * short) and is closed when the gap is filled (the day saved again with enough present, attached
 * labour or pool labour counted, SRS-2-46 / SRS-2-47) or when the Depot Manager records a reason.
 * The Gang Strength Report lists sanctioned and present strength by gang and day, each shortfall,
 * the absent members and the labour attached that day (how the gap was filled). The Ancillary Pool
 * is left out (it has no strength).
 */
@Service
@Loggable
public class GangStrengthService {

    static final String SCREEN = "M-09";
    static final String ENTITY = "labour_gang";

    /** One day of the Gang Strength Report. */
    public record Day(
            LocalDate date,
            int sanctioned,
            int present,
            int shortBy,
            List<String> absent,
            List<String> attached,
            boolean alertOpen) {}

    /** The Gang Strength Report of a gang's month. */
    public record Report(long gangId, String gangName, String depot, String yymm, List<Day> days) {}

    private final JdbcTemplate jdbc;
    private final LabourGangs gangs;
    private final Authorization policy;
    private final ExceptionWorklist worklist;
    private final AuditService audit;

    public GangStrengthService(
            JdbcTemplate jdbc,
            LabourGangs gangs,
            Authorization policy,
            ExceptionWorklist worklist,
            AuditService audit) {
        this.jdbc = jdbc;
        this.gangs = gangs;
        this.policy = policy;
        this.worklist = worklist;
        this.audit = audit;
    }

    /**
     * SRS-2-48: the day's check after its attendance is saved (I-01): a shortfall raises or keeps
     * the X-05 alert, a filled gap closes it. A gang without strength in force (the pool) is not
     * checked.
     */
    @Transactional
    public GangStrengthRules.Check checkDay(String actor, LabourGangs.Gang g, LocalDate day) {
        GangStrengthRules.Check c =
                GangStrengthRules.check(strength(g.id(), day), members(g.id(), day));
        if (c == null) {
            return null;
        }
        String key = GangStrengthRules.key(g.id(), day);
        if (c.shortfall()) {
            worklist.raise(
                    actor,
                    new ExceptionWorklist.NewEntry(
                            null,
                            null,
                            SCREEN,
                            GangStrengthRules.SHORTFALL,
                            "WARNING",
                            null,
                            null,
                            ENTITY,
                            key,
                            "CALC",
                            SCREEN,
                            GangStrengthRules.detail(g.name(), day, c)));
        } else {
            worklist.clear(
                    actor,
                    GangStrengthRules.SHORTFALL,
                    null,
                    null,
                    key,
                    "Gap filled: present " + c.present() + " of " + c.sanctioned() + " (SRS-2-48)");
        }
        return c;
    }

    /** SRS-2-48: the Gang Strength Report of a gang's month (the days with attendance marked). */
    @Transactional(readOnly = true)
    public Report report(AccessContext context, long gangId, YearMonth month) {
        LabourGangs.Gang g = gangs.workGang(gangId);
        policy.requireRecordAt(context, SCREEN, "VIEW", g.depotId());
        List<Day> days = new ArrayList<>();
        for (LocalDate day :
                jdbc
                        .queryForList(
                                "SELECT DISTINCT attendance_date FROM labour_attendance WHERE"
                                    + " gang_id = ? AND attendance_date BETWEEN ? AND ? ORDER BY"
                                    + " attendance_date",
                                java.sql.Date.class,
                                gangId,
                                Date.valueOf(month.atDay(1)),
                                Date.valueOf(month.atEndOfMonth()))
                        .stream()
                        .map(Date::toLocalDate)
                        .toList()) {
            GangStrengthRules.Check c =
                    GangStrengthRules.check(strength(gangId, day), members(gangId, day));
            if (c == null) {
                continue;
            }
            days.add(
                    new Day(
                            day,
                            c.sanctioned(),
                            c.present(),
                            c.shortBy(),
                            c.absent(),
                            attached(gangId, day),
                            open(GangStrengthRules.key(gangId, day))));
        }
        return new Report(
                gangId,
                g.name(),
                g.depot(),
                String.format("%d%02d", month.getYear(), month.getMonthValue()),
                days);
    }

    /**
     * M-09 "Gang strength today": one gang's day; sanctioned is null without a strength in force.
     */
    public record DayRead(
            Integer sanctioned,
            int present,
            int shortBy,
            boolean alertOpen,
            List<String> attached) {}

    /**
     * BR-LABM-011: the same check I-01 makes on saving the day, read for M-09: a gang without
     * Sanctioned Strength (the Ancillary Pool) has its present labour and no shortfall.
     */
    @Transactional(readOnly = true)
    public DayRead read(long gangId, LocalDate day) {
        Integer sanctioned = strength(gangId, day);
        List<GangStrengthRules.Member> members = members(gangId, day);
        GangStrengthRules.Check c = GangStrengthRules.check(sanctioned, members);
        int present = (int) members.stream().filter(GangStrengthRules::present).count();
        return new DayRead(
                sanctioned,
                present,
                c == null ? 0 : c.shortBy(),
                c != null && open(GangStrengthRules.key(gangId, day)),
                attached(gangId, day));
    }

    /**
     * SRS-2-48 "the alert closes when ... the Depot Manager records a reason": the Depot Manager of
     * the gang's depot (M-09 APPROVE) closes the day's open alert with the reason, audited.
     */
    @Transactional
    public int close(AccessContext context, long gangId, LocalDate day, String reason) {
        LabourGangs.Gang g = gangs.workGang(gangId);
        policy.requireRecordAt(context, SCREEN, "APPROVE", g.depotId());
        if (day == null) {
            throw invalid("DATE");
        }
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw invalid("REASON");
        }
        String key = GangStrengthRules.key(gangId, day);
        if (!open(key)) {
            throw invalid("NO_OPEN_SHORTFALL");
        }
        int closed =
                worklist.clear(
                        context.loginId(),
                        GangStrengthRules.SHORTFALL,
                        null,
                        null,
                        key,
                        "Closed by the Depot Manager: " + reason.strip());
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                g.code(),
                                "gang_shortfall " + day,
                                "OPEN",
                                "CLOSED",
                                reason.strip())
                        .module(SCREEN)
                        .effective(day));
        return closed;
    }

    private Integer strength(long gangId, LocalDate day) {
        return jdbc
                .queryForList(
                        "SELECT s.sanctioned_strength FROM labour_gang_strength s JOIN labour_gang"
                            + " g ON g.gang_id = s.gang_id WHERE s.gang_id = ? AND g.gang_type ="
                            + " 'HANDLING' AND s.effective_from <= ? AND (s.effective_to IS NULL OR"
                            + " s.effective_to >= ?) ORDER BY s.effective_from DESC,"
                            + " s.gang_strength_id DESC",
                        Integer.class,
                        gangId,
                        Date.valueOf(day),
                        Date.valueOf(day))
                .stream()
                .findFirst()
                .orElse(null);
    }

    /** The members of the gang that day (attached labour included), with the day's mark. */
    private List<GangStrengthRules.Member> members(long gangId, LocalDate day) {
        List<GangStrengthRules.Member> members = new ArrayList<>();
        for (Map<String, Object> r :
                jdbc.queryForList(
                        "SELECT p.labour_number, a.attendance_code, c.present_day_weight FROM"
                            + " labour_gang_membership m JOIN labour_profile p ON p.employee_id ="
                            + " m.employee_id LEFT JOIN labour_attendance a ON a.employee_id ="
                            + " m.employee_id AND a.gang_id = m.gang_id AND a.attendance_date = ?"
                            + " LEFT JOIN attendance_code_master c ON c.attendance_code_id ="
                            + " a.attendance_code_id WHERE m.gang_id = ? AND m.effective_from <= ?"
                            + " AND (m.effective_to IS NULL OR m.effective_to >= ?) ORDER BY"
                            + " p.labour_number",
                        Date.valueOf(day),
                        gangId,
                        Date.valueOf(day),
                        Date.valueOf(day))) {
            members.add(
                    new GangStrengthRules.Member(
                            (String) r.get("LABOUR_NUMBER"),
                            (String) r.get("ATTENDANCE_CODE"),
                            r.get("PRESENT_DAY_WEIGHT") == null
                                    ? null
                                    : new BigDecimal(r.get("PRESENT_DAY_WEIGHT").toString())));
        }
        return members;
    }

    /** SRS-2-47: the labour attached to the gang that day (how a gap was filled). */
    private List<String> attached(long gangId, LocalDate day) {
        return jdbc.queryForList(
                "SELECT p.labour_number FROM labour_temp_attachment t JOIN labour_profile p ON"
                        + " p.employee_id = t.employee_id WHERE t.to_gang_id = ? AND"
                        + " t.attachment_status = 'APPROVED' AND t.from_date <= ? AND (t.to_date IS"
                        + " NULL OR t.to_date >= ?) ORDER BY p.labour_number",
                String.class,
                gangId,
                Date.valueOf(day),
                Date.valueOf(day));
    }

    private boolean open(String key) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_exception WHERE exception_code = ? AND"
                                + " field_name = ? AND exception_status IN ('OPEN', 'IN_PROGRESS')",
                        Integer.class,
                        GangStrengthRules.SHORTFALL,
                        key);
        return n != null && n > 0;
    }

    private static FciException invalid(String defect) {
        return new FciException(LabourErrorCode.ENTRY, new Object[] {defect}, List.of(), null);
    }
}
