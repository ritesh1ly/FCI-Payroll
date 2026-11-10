package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.general.business.model.ExceptionEntry;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-08 Payroll Computation - Exceptions Review (SRS-G-9, SRS l.1641-1678, US-05-08): the X-05
 * worklist filtered to the current run, not a separate store. Its own read so that a role holding
 * C-08 and no X-05 (Establishment / HR, read-only, SRS-G-9) reaches the drawn data and nothing else
 * of X-05; the actions stay on X-05 (accept X-05 APPROVE, assign X-05 EDIT).
 *
 * <p>The run is the newest period, up to the session's, with an Actual run at the session's own
 * cycle unit, or in its data scope for an office with no cycle of its own (a clean run shows an
 * empty list, never an older one's rows). One row per condition with the eight fields of SRS
 * l.1669-1676. Payroll Type is derived, not stored: {@code employee.emp_lbr_flag} N is STAFF,
 * {@code labour_profile.payroll_type} gives the rest, and a condition across types or on the cycle
 * itself reads ALL (SC-05-0068, CLR-72, closes F-05-3). The severity shown and filtered is the
 * stored one: the SRS grades wait for FCI (CLR-46).
 */
@Service
@Loggable
public class ComputationExceptions {

    public static final String SCREEN = "C-08";

    /** A condition across payroll types, or a cycle-level one (SC-05-0068). */
    static final String ALL_TYPES = "ALL";

    /** The drawn tiles: the last three add up to {@code total}, every open item in one of them. */
    public record Tiles(long total, long blocking, long fixable, long hrms, long systemOrMember) {}

    /**
     * One condition (SRS l.1669-1676): {@code count} members affected, or items where the entries
     * are on the cycle; {@code blocksLock} an open blocking item holds gate G2; {@code owner} the
     * role that fixes it when one role does; {@code resolvable} every item is fixable in Payroll.
     */
    public record Condition(
            String moduleCode,
            String exceptionCode,
            String payrollType,
            long count,
            String severity,
            String fieldName,
            String fieldSource,
            String correctionScreen,
            boolean blocksLock,
            String owner,
            boolean resolvable,
            String detail) {}

    /** The run's period YYYY-MM (null when no run is in scope), its tiles, one page of rows. */
    public record Review(
            String period, Tiles tiles, List<Condition> items, int page, int size, long total) {}

    /** The filters of the screen: the session's month, the stored severity, the list search. */
    public record Filter(String sessionYymm, ExceptionEntry.Severity severity, String search) {}

    private static final String FROM =
            " FROM payroll_exception x JOIN payroll_cycle_instance c ON c.cycle_instance_id ="
                    + " x.cycle_instance_id LEFT JOIN employee e ON e.employee_id = x.employee_id"
                    + " LEFT JOIN labour_profile l ON l.employee_id = x.employee_id WHERE"
                    + " c.payroll_yymm = :yymm AND (e.location_id IN (:scope) OR (x.employee_id IS"
                    + " NULL AND c.org_unit_id IN (:scope))) AND x.exception_status IN (:open)";

    private static final String TYPE =
            "CASE WHEN x.employee_id IS NULL THEN NULL WHEN e.emp_lbr_flag = 'N' THEN 'STAFF' ELSE"
                    + " l.payroll_type END";

    private static final String BLOCKS =
            "CASE WHEN x.blocks_gate = 'G2' AND x.severity = 'BLOCKING' THEN 1 ELSE 0 END";

    private final NamedParameterJdbcTemplate jdbc;
    private final Authorization policy;
    private final ExceptionService worklist;
    private final OrgDirectory org;

    public ComputationExceptions(
            NamedParameterJdbcTemplate jdbc,
            Authorization policy,
            ExceptionService worklist,
            OrgDirectory org) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.worklist = worklist;
        this.org = org;
    }

    /** SRS-G-9: the run's conditions in the session's scope, with the drawn tiles. */
    @Transactional(readOnly = true)
    public Review review(AccessContext context, Filter filter, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        Set<Long> scope = Scopes.of(context);
        String yymm = runOf(context, scope, filter.sessionYymm());
        if (yymm == null) {
            return new Review(
                    null, new Tiles(0, 0, 0, 0, 0), List.of(), spec.page(), spec.size(), 0);
        }
        MapSqlParameterSource params = params(scope, yymm, filter);
        long total = conditionCount(params, filter);
        List<Condition> rows =
                jdbc.query(
                        conditions(filter) + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                        params.addValue("offset", (long) spec.page() * spec.size())
                                .addValue("size", spec.size()),
                        ComputationExceptions::condition);
        return new Review(
                period(yymm), tiles(params, filter), rows, spec.page(), spec.size(), total);
    }

    /** The items of one condition (the row's View), member by member, in the session's scope. */
    @Transactional(readOnly = true)
    public Paged<ExceptionService.ExceptionView> items(
            AccessContext context, String sessionYymm, String module, String code, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        Set<Long> scope = Scopes.of(context);
        String yymm = runOf(context, scope, sessionYymm);
        if (yymm == null) {
            return new Paged<>(List.of(), spec.page(), spec.size(), 0);
        }
        return worklist.items(
                scope,
                new ExceptionService.Filter(module, null, null, null, yymm, null, code),
                spec);
    }

    /** Conditions an export writes (the size check before streaming, SRS-G-13). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context, Filter filter) {
        policy.require(context, SCREEN, "EXPORT");
        Set<Long> scope = Scopes.of(context);
        String yymm = runOf(context, scope, filter.sessionYymm());
        return yymm == null ? 0 : conditionCount(params(scope, yymm, filter), filter);
    }

    /** SRS l.1678 Export worklist (SRS-G-13): the drawn columns of every condition of the run. */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(AccessContext context, Filter filter, ExportSink sink) throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        Set<Long> scope = Scopes.of(context);
        String yymm = runOf(context, scope, filter.sessionYymm());
        sink.header(
                "Exception",
                "Payroll type",
                "Count",
                "Severity",
                "Field involved",
                "Field source",
                "Fix where",
                "Blocks lock?");
        List<Condition> rows =
                yymm == null
                        ? List.of()
                        : jdbc.query(
                                conditions(filter),
                                params(scope, yymm, filter),
                                ComputationExceptions::condition);
        for (Condition c : rows) {
            sink.row(
                    c.exceptionCode() + ": " + c.detail(),
                    c.payrollType(),
                    c.count(),
                    c.severity(),
                    c.fieldName(),
                    c.fieldSource(),
                    c.correctionScreen(),
                    c.blocksLock() ? "Y" : "N");
        }
        ExportSink.requireComplete(rows.size(), sink);
    }

    /**
     * The current run of a scope: the newest period, not after the session's, with an Actual run.
     * The session's month without a run falls back as C-02 does (the unit's latest with a run).
     */
    private String runOf(AccessContext context, Set<Long> scope, String sessionYymm) {
        String own =
                context.locationId() == null
                        ? null
                        : org.cycleUnitOf(context.locationId())
                                .map(u -> latestRun(Set.of(u.getId()), sessionYymm))
                                .orElse(null);
        return own != null ? own : latestRun(scope, sessionYymm);
    }

    private String latestRun(Set<Long> units, String sessionYymm) {
        return jdbc.queryForObject(
                "SELECT MAX(c.payroll_yymm) FROM payroll_cycle_instance c WHERE c.org_unit_id IN"
                        + " (:scope) AND c.payroll_yymm <= :session AND EXISTS (SELECT 1 FROM"
                        + " computation_run r WHERE r.cycle_instance_id = c.cycle_instance_id AND"
                        + " r.run_type = 'ACTUAL')",
                new MapSqlParameterSource()
                        .addValue("scope", units)
                        .addValue("session", sessionYymm),
                String.class);
    }

    private static MapSqlParameterSource params(Set<Long> scope, String yymm, Filter filter) {
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("scope", scope)
                        .addValue("yymm", yymm)
                        .addValue(
                                "open",
                                ExceptionEntry.unsettledStatuses().stream()
                                        .map(Enum::name)
                                        .toList());
        if (filter.severity() != null) {
            params.addValue("severity", filter.severity().name());
        }
        if (filter.search() != null) {
            params.addValue("search", "%" + like(filter.search()) + "%");
        }
        return params;
    }

    /** The search as a LIKE pattern: its own % and _ match themselves. */
    static String like(String search) {
        return search.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private static String where(Filter filter) {
        return (filter.severity() == null ? "" : " AND x.severity = :severity")
                + (filter.search() == null
                        ? ""
                        : " AND (LOWER(x.exception_code) LIKE :search ESCAPE '!' OR"
                                + " LOWER(x.detail) LIKE :search ESCAPE '!')");
    }

    private long conditionCount(MapSqlParameterSource params, Filter filter) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM (SELECT x.module_code, x.exception_code"
                                + FROM
                                + where(filter)
                                + " GROUP BY x.module_code, x.exception_code) t",
                        params,
                        Long.class);
        return n == null ? 0 : n;
    }

    private static String conditions(Filter filter) {
        return "SELECT x.module_code, x.exception_code, COUNT(DISTINCT x.exception_id) items,"
                + " COUNT(DISTINCT x.employee_id) members, MIN(x.severity) severity,"
                + " MIN(x.field_name) field_name, MIN(x.field_source) field_source,"
                + " MIN(x.correction_screen) correction_screen, MAX("
                + BLOCKS
                + ") blocks, MIN(x.owner_role_code) owner_low, MAX(x.owner_role_code) owner_high,"
                + " MIN(CASE WHEN x.resolvable_in_payroll = 'Y' THEN 1 ELSE 0 END) resolvable,"
                + " MIN(x.detail) detail, MIN("
                + TYPE
                + ") type_low, MAX("
                + TYPE
                + ") type_high, COUNT("
                + TYPE
                + ") typed"
                + FROM
                + where(filter)
                + " GROUP BY x.module_code, x.exception_code ORDER BY MIN(x.raised_at) ASC,"
                + " x.module_code ASC, x.exception_code ASC";
    }

    private Tiles tiles(MapSqlParameterSource params, Filter filter) {
        return jdbc.queryForObject(
                "SELECT COUNT(DISTINCT x.exception_id), COUNT(DISTINCT CASE WHEN "
                        + BLOCKS
                        + " = 1 THEN x.exception_id END), COUNT(DISTINCT CASE WHEN"
                        + " x.resolvable_in_payroll = 'Y' AND COALESCE(x.field_source, '-') <>"
                        + " 'HRMS' THEN x.exception_id END), COUNT(DISTINCT CASE WHEN"
                        + " x.field_source = 'HRMS' THEN x.exception_id END), COUNT(DISTINCT CASE"
                        + " WHEN x.resolvable_in_payroll = 'N' AND COALESCE(x.field_source, '-') <>"
                        + " 'HRMS' THEN x.exception_id END)"
                        + FROM
                        + where(filter),
                params,
                (rs, i) ->
                        new Tiles(
                                rs.getLong(1),
                                rs.getLong(2),
                                rs.getLong(3),
                                rs.getLong(4),
                                rs.getLong(5)));
    }

    private static Condition condition(ResultSet rs, int row) throws SQLException {
        long members = rs.getLong("members");
        String low = rs.getString("type_low");
        String owner = rs.getString("owner_low");
        boolean oneType =
                low != null
                        && low.equals(rs.getString("type_high"))
                        && rs.getLong("typed") == rs.getLong("items");
        return new Condition(
                rs.getString("module_code"),
                rs.getString("exception_code"),
                oneType ? low : ALL_TYPES,
                members > 0 ? members : rs.getLong("items"),
                rs.getString("severity"),
                rs.getString("field_name"),
                rs.getString("field_source"),
                rs.getString("correction_screen"),
                rs.getLong("blocks") > 0,
                owner != null && owner.equals(rs.getString("owner_high")) ? owner : null,
                rs.getLong("resolvable") > 0,
                rs.getString("detail"));
    }

    /** "202607" as "2026-07". */
    private static String period(String yymm) {
        return yymm.substring(0, 4) + "-" + yymm.substring(4);
    }
}
