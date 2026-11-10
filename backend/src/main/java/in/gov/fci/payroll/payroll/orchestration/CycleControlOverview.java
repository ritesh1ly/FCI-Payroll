package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What C-01 Payroll Cycle Control draws beside the gates (SRS-15-12, SRS-15-5, SRS-15-23,
 * SRS-15-28, SRS-15-39, SRS-15-42, SRS l.1755-1767, SRS-G-32), read on C-01 VIEW so that every role
 * C-01 draws reaches it (Finance and the Administrator hold no C-02): the band, the four tiles, the
 * initiation checks, the run and lease, the levels, the fallback status, the Period Register, the
 * Pre-Payroll Exception Report and the stored override notes. Read-only except the note: nothing
 * here decides, every action stays on its own screen under its own right.
 *
 * <p>The roll-ups (levels, fallback, report) cover the instances of the session's data scope and
 * never more: a Division's user sees its own scope, with the zero rows drawn (SRS-15-5, R-RBAC).
 */
@Service
@Loggable
public class CycleControlOverview {

    static final String SCREEN = "C-01";

    /** The override approval note hangs on the instance (SRS-G-32 names C-01). */
    static final String ORDER_ENTITY = "payroll_cycle_instance";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** The four levels SRS-15-20 runs the cycle at, in the drawn order. */
    private static final List<String> LEVELS = List.of("HQ", "ZO", "RO", "DO");

    /** No location id is negative: an empty data scope then matches no row. */
    private static final Set<Long> NOTHING = Set.of(-1L);

    /**
     * The tiles: {@code daysToDisbursement} from today to the calendar's date, null when the
     * calendar has none; {@code engines} the engine rows of the instance by payroll type.
     */
    public record Tiles(
            LocalDate disbursement,
            Long daysToDisbursement,
            Map<String, String> engines,
            long blockingExceptions,
            String verifiedBy,
            Instant verifiedAt) {}

    /** The run of record with its work-unit progress (SRS l.1764). */
    public record RunState(
            long id,
            String status,
            String startedBy,
            Instant startedAt,
            Integer units,
            int unitsCommitted,
            int unitsFailed) {}

    /** One level of "Cycle instances by organisational level" in the session's scope. */
    public record LevelRow(String level, long instances, long locked) {}

    /** One enabled fallback upload of the period (SRS l.1766; the status of its enablement). */
    public record FallbackRow(
            String unitCode, String unitLabel, String type, String status, boolean blocksLock) {}

    /** The whole overview; the cycle fields are null where no cycle was initiated. */
    public record Overview(
            String period,
            String finYear,
            String unitCode,
            String unitLabel,
            String unitLevel,
            Long cycleId,
            String status,
            String frozenBy,
            Instant frozenAt,
            boolean postedToEbs,
            boolean supplementaryOpen,
            Tiles tiles,
            List<PreRunChecks.Check> preRun,
            RunState run,
            CycleLeases.Lease lease,
            List<LevelRow> levels,
            List<FallbackRow> fallback) {}

    /** One row of the Period Register (SRS-15-12: the flags stand beside the status). */
    public record PeriodRow(
            String period,
            String status,
            Instant computedAt,
            String verifiedBy,
            Instant verifiedAt,
            Instant lockedAt,
            boolean postedToEbs,
            boolean supplementaryOpen,
            boolean mdpOpen) {}

    /** One failed check of the Pre-Payroll Exception Report, by office, with its fixing screen. */
    public record ReportRow(
            String unitCode,
            String unitLabel,
            String checkCode,
            String checkName,
            String severity,
            long failures,
            String detail,
            String correctionScreen) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final OrgDirectory org;
    private final Authorization policy;
    private final CycleGateService gates;
    private final CycleLeases leases;
    private final PreRunChecks preRun;
    private final DocumentStore documents;
    private final Clock clock;

    public CycleControlOverview(
            NamedParameterJdbcTemplate jdbc,
            OrgDirectory org,
            Authorization policy,
            CycleGateService gates,
            CycleLeases leases,
            PreRunChecks preRun,
            DocumentStore documents,
            Clock clock) {
        this.jdbc = jdbc;
        this.org = org;
        this.policy = policy;
        this.gates = gates;
        this.leases = leases;
        this.preRun = preRun;
        this.documents = documents;
        this.clock = clock;
    }

    /** SRS l.1755-1767: the overview of the session's unit for {@code period}. */
    @Transactional(readOnly = true)
    public Overview overview(AccessContext context, YearMonth period) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "VIEW", unit.getId());
        String yymm = CycleKeys.yymm(period);
        Set<Long> scope = scope(context);
        Optional<Map<String, Object>> cycle = instance(yymm, unit);
        Long cycleId = cycle.map(CycleControlOverview::id).orElse(null);
        return new Overview(
                period.toString(),
                CycleKeys.finYear(period),
                unit.getCode(),
                unit.getName(),
                unit.getType().name(),
                cycleId,
                cycle.map(c -> (String) c.get("CYCLE_STATUS")).orElse(null),
                cycle.map(c -> (String) c.get("FROZEN_BY")).orElse(null),
                cycle.map(c -> CycleSupport.instant((Timestamp) c.get("FROZEN_AT"))).orElse(null),
                cycle.map(c -> flag(c, "POSTED_TO_EBS_FLAG")).orElse(false),
                cycle.map(c -> flag(c, "SUPPLEMENTARY_OPEN") || flag(c, "MDP_WINDOW_OPEN"))
                        .orElse(false),
                tiles(yymm, cycle),
                preRun.evaluate(unit.getId(), period),
                cycleId == null ? null : run(cycleId),
                cycleId == null ? null : leases.current(cycleId).orElse(null),
                levels(yymm, scope),
                fallback(yymm, scope));
    }

    /** SRS-15-12, US-05-15: the unit's periods, newest first, with their flags. */
    @Transactional(readOnly = true)
    public Paged<PeriodRow> periods(AccessContext context, PageSpec spec) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "VIEW", unit.getId());
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("unit", unit.getId())
                        .addValue("offset", (long) spec.page() * spec.size())
                        .addValue("size", spec.size());
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_cycle_instance WHERE org_unit_id = :unit",
                        params,
                        Long.class);
        List<PeriodRow> rows =
                jdbc.query(
                        "SELECT c.payroll_yymm, c.cycle_status, c.verified_by, c.verified_at,"
                                + " c.locked_at, c.posted_to_ebs_flag, c.supplementary_open,"
                                + " c.mdp_window_open, (SELECT MAX(r.completed_at) FROM"
                                + " computation_run r WHERE r.cycle_instance_id ="
                                + " c.cycle_instance_id AND r.run_type = 'ACTUAL') AS computed_at"
                                + " FROM payroll_cycle_instance c WHERE c.org_unit_id = :unit ORDER"
                                + " BY c.payroll_yymm DESC OFFSET :offset ROWS FETCH NEXT :size"
                                + " ROWS ONLY",
                        params,
                        CycleControlOverview::period);
        return new Paged<>(rows, spec.page(), spec.size(), total == null ? 0 : total);
    }

    /**
     * SRS-15-39, US-05-10 AC3: one Pre-Payroll Exception Report by office: each check that failed
     * at its latest evaluation, in the session's scope, with the screen that fixes it. Its layout
     * is not specified (PENDING C01-REPORT).
     */
    @Transactional(readOnly = true)
    public Paged<ReportRow> report(AccessContext context, YearMonth period, PageSpec spec) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "VIEW", unit.getId());
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("yymm", CycleKeys.yymm(period))
                        .addValue("scope", scope(context))
                        .addValue("offset", (long) spec.page() * spec.size())
                        .addValue("size", spec.size());
        String from =
                " FROM cycle_prepay_check_result r JOIN pre_payroll_check p ON p.check_id ="
                        + " r.check_id JOIN payroll_cycle_instance c ON c.cycle_instance_id ="
                        + " r.cycle_instance_id JOIN org_location l ON l.location_id ="
                        + " c.org_unit_id WHERE c.payroll_yymm = :yymm AND c.org_unit_id IN"
                        + " (:scope) AND r.check_result = 'FAIL' AND r.evaluated_at = (SELECT"
                        + " MAX(x.evaluated_at) FROM cycle_prepay_check_result x WHERE"
                        + " x.cycle_instance_id = r.cycle_instance_id AND x.check_id = r.check_id)";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, params, Long.class);
        List<ReportRow> rows =
                jdbc.query(
                        "SELECT l.location_code, l.location_name, p.check_code, p.check_name,"
                                + " r.severity_at_eval, r.failure_count, r.evaluation_detail,"
                                + " p.correction_screen"
                                + from
                                + " ORDER BY l.location_code, r.severity_at_eval, p.check_code"
                                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                        params,
                        (rs, i) ->
                                new ReportRow(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getLong(6),
                                        rs.getString(7),
                                        rs.getString(8)));
        return new Paged<>(rows, spec.page(), spec.size(), total == null ? 0 : total);
    }

    /**
     * SRS-G-32: the override approval notes of the period's instance; a period with no cycle yet
     * shows those of the unit's latest instance, as C-02 shows the latest run.
     */
    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> orders(AccessContext context, YearMonth period) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "VIEW", unit.getId());
        return latest(CycleKeys.yymm(period), unit)
                .map(id -> documents.active(ORDER_ENTITY, String.valueOf(id)))
                .orElse(List.of());
    }

    /**
     * SRS-G-30, SRS-G-32: stores an override approval note with the instance; the Payroll Maker of
     * the unit (the right the C-02 board holds). Type, size and count are the document module's.
     *
     * @throws FciException COM-404 when no cycle was initiated for the period
     */
    @Transactional
    public DocumentStore.AttachmentView attachOrder(
            AccessContext context, YearMonth period, DocumentStore.NewAttachment order) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, "C-02", "CREATE", unit.getId());
        long cycle = cycleId(period, unit);
        return documents.attach(
                context.loginId(),
                new DocumentStore.NewAttachment(
                        ORDER_ENTITY,
                        String.valueOf(cycle),
                        order.documentTypeCode(),
                        order.referenceNumber(),
                        order.orderDate(),
                        order.issuingAuthority(),
                        order.fileName(),
                        order.content(),
                        false));
    }

    /** One stored note, read through the document module (every read is logged there). */
    @Transactional
    public DocumentStore.Content file(
            AccessContext context, String sourceIp, YearMonth period, long fileId) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "VIEW", unit.getId());
        long cycle =
                latest(CycleKeys.yymm(period), unit)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        return documents.read(
                context.loginId(), sourceIp, ORDER_ENTITY, String.valueOf(cycle), fileId);
    }

    /** The unit's instance of the month, or its latest before it. */
    private Optional<Long> latest(String yymm, OrgLocation unit) {
        return jdbc
                .queryForList(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE org_unit_id ="
                                + " :unit AND payroll_yymm <= :yymm ORDER BY payroll_yymm DESC"
                                + " FETCH FIRST 1 ROWS ONLY",
                        new MapSqlParameterSource()
                                .addValue("yymm", yymm)
                                .addValue("unit", unit.getId()),
                        Long.class)
                .stream()
                .findFirst();
    }

    private long cycleId(YearMonth period, OrgLocation unit) {
        return instance(CycleKeys.yymm(period), unit)
                .map(CycleControlOverview::id)
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private OrgLocation unit(AccessContext context) {
        return CycleSupport.unit(org, context);
    }

    private static Set<Long> scope(AccessContext context) {
        Set<Long> scope = context.dataScope();
        return scope == null || scope.isEmpty() ? NOTHING : scope;
    }

    private Optional<Map<String, Object>> instance(String yymm, OrgLocation unit) {
        return jdbc
                .queryForList(
                        "SELECT * FROM payroll_cycle_instance WHERE payroll_yymm = :yymm AND"
                                + " org_unit_id = :unit",
                        new MapSqlParameterSource()
                                .addValue("yymm", yymm)
                                .addValue("unit", unit.getId()))
                .stream()
                .findFirst();
    }

    private Tiles tiles(String yymm, Optional<Map<String, Object>> cycle) {
        List<LocalDate> dates =
                jdbc.query(
                        "SELECT disbursement_date FROM payroll_calendar WHERE payroll_yymm = :yymm",
                        new MapSqlParameterSource("yymm", yymm),
                        (rs, i) -> rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate());
        LocalDate disbursement = dates.isEmpty() ? null : dates.get(0);
        Long days =
                disbursement == null
                        ? null
                        : ChronoUnit.DAYS.between(
                                clock.instant().atZone(IST).toLocalDate(), disbursement);
        if (cycle.isEmpty()) {
            return new Tiles(disbursement, days, Map.of(), 0, null, null);
        }
        long id = id(cycle.get());
        return new Tiles(
                disbursement,
                days,
                gates.engineStates(id),
                gates.blockingExceptions(id),
                (String) cycle.get().get("VERIFIED_BY"),
                CycleSupport.instant((Timestamp) cycle.get().get("VERIFIED_AT")));
    }

    private RunState run(long cycleId) {
        List<RunState> runs =
                jdbc.query(
                        "SELECT r.run_id, r.run_status, r.started_by, r.started_at, r.unit_total,"
                                + " r.unit_committed, r.unit_failed FROM computation_run r WHERE"
                                + " r.cycle_instance_id = :cycle AND r.run_type = 'ACTUAL' ORDER BY"
                                + " r.run_no DESC FETCH FIRST 1 ROWS ONLY",
                        new MapSqlParameterSource("cycle", cycleId),
                        (rs, i) ->
                                new RunState(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        CycleSupport.instant(rs.getTimestamp(4)),
                                        rs.getObject(5) == null ? null : rs.getInt(5),
                                        rs.getInt(6),
                                        rs.getInt(7)));
        return runs.isEmpty() ? null : runs.get(0);
    }

    private List<LevelRow> levels(String yymm, Set<Long> scope) {
        Map<String, LevelRow> found = new LinkedHashMap<>();
        for (String level : LEVELS) {
            found.put(level, new LevelRow(level, 0, 0));
        }
        jdbc.query(
                "SELECT org_unit_level, COUNT(*), SUM(CASE WHEN cycle_status = 'LOCKED' THEN 1"
                        + " ELSE 0 END) FROM payroll_cycle_instance WHERE payroll_yymm = :yymm AND"
                        + " org_unit_id IN (:scope) GROUP BY org_unit_level",
                new MapSqlParameterSource().addValue("yymm", yymm).addValue("scope", scope),
                rs -> {
                    String level = rs.getString(1);
                    found.put(level, new LevelRow(level, rs.getLong(2), rs.getLong(3)));
                });
        return new ArrayList<>(found.values());
    }

    private List<FallbackRow> fallback(String yymm, Set<Long> scope) {
        return jdbc.query(
                "SELECT l.location_code, l.location_name, f.fallback_type, f.enablement_status,"
                        + " f.blocks_lock FROM v_cycle_hrms_fallback_status f JOIN org_location l"
                        + " ON l.location_id = f.org_unit_id WHERE f.payroll_yymm = :yymm AND"
                        + " f.org_unit_id IN (:scope) ORDER BY f.fallback_type, l.location_code",
                new MapSqlParameterSource().addValue("yymm", yymm).addValue("scope", scope),
                (rs, i) ->
                        new FallbackRow(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                "Y".equals(rs.getString(5))));
    }

    private static PeriodRow period(ResultSet rs, int row) throws SQLException {
        String yymm = rs.getString(1);
        return new PeriodRow(
                yymm.substring(0, 4) + "-" + yymm.substring(4),
                rs.getString(2),
                CycleSupport.instant(rs.getTimestamp("computed_at")),
                rs.getString(3),
                CycleSupport.instant(rs.getTimestamp(4)),
                CycleSupport.instant(rs.getTimestamp(5)),
                "Y".equals(rs.getString(6)),
                "Y".equals(rs.getString(7)),
                "Y".equals(rs.getString(8)));
    }

    private static boolean flag(Map<String, Object> cycle, String column) {
        return "Y".equals(String.valueOf(cycle.get(column)).trim());
    }

    private static long id(Map<String, Object> cycle) {
        return ((Number) cycle.get("CYCLE_INSTANCE_ID")).longValue();
    }
}
