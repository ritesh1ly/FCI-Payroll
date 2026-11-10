package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.jobs.api.BackgroundJobs;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.payroll.business.SimulationRules;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-09 General (Simulation) Run (plan 05 phase 05i; SRS-15-7, SRS C-09, BR-CYC-007): the Payroll
 * Maker or Checker previews the payroll of the whole office instance, one member or a custom list,
 * as it stands or with a what-if DA rate (plan D-17), at any time and outside the cycle sequence
 * (no lease, no cycle instance: plan D-18).
 *
 * <ul>
 *   <li>The maximum selection and the interactive limit are A-07 settings, unseeded (OQ-17): until
 *       FCI sets them, a run is refused "not configured".
 *   <li>At or below the interactive limit the run is computed in the request and the member rows
 *       are returned once, never stored; above it the run is queued as C-13 job SIMULATION_RUN
 *       (plan D-21), which keeps the summary only.
 *   <li>The run record is kept for audit ({@code rule_simulation}, results_retained 'N'); opening a
 *       record compares its input hash with the inputs of now and says when the data changed.
 *   <li>Nothing here is posted, reported or paid, and members have no access (no eSeva route).
 * </ul>
 */
@Service
@Loggable
public class SimulationService {

    /** Audit entity of a run record. */
    public static final String ENTITY = "rule_simulation";

    private static final String SCREEN = "C-09";

    /** At most this many unknown employee numbers are named in a refusal. */
    private static final int NAMED = 5;

    /** What the requester asks for. */
    public record Input(
            SimulationRules.Population population,
            List<String> members,
            SimulationRules.WhatIf whatIf,
            BigDecimal daIda,
            BigDecimal daCda) {}

    /**
     * The C-09 tiles of the current cycle of the user's unit; the two limits null while not
     * configured.
     */
    public record Overview(
            String period,
            String unitCode,
            String unitLabel,
            Long maxSelection,
            Long interactiveLimit,
            long runsThisCycle,
            long mineThisCycle,
            long largestThisCycle,
            long retained) {}

    /** One run record (Recent Simulation Runs). */
    public record RunView(
            long id,
            String reference,
            String requestedBy,
            Instant requestedAt,
            String period,
            String population,
            long populationCount,
            String whatIf,
            BigDecimal daIda,
            BigDecimal daCda,
            String mode,
            String status,
            long done,
            Long jobRequestId,
            boolean resultsRetained,
            String failure) {}

    /** One pay code of the summary, as-is and what-if (null for an as-is preview). */
    public record ComponentView(int payCode, String name, BigDecimal asIs, BigDecimal whatIf) {}

    /** The figures of one side. */
    public record SideView(
            long computed,
            long stopped,
            Map<String, Long> notComputed,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal net) {}

    /** One member of an interactive preview (returned once; never stored). */
    public record MemberView(
            String eis,
            String name,
            String status,
            String reason,
            BigDecimal gross,
            BigDecimal net,
            BigDecimal whatIfGross,
            BigDecimal whatIfNet) {}

    /**
     * A run with its summary.
     *
     * @param current SRS-15-7: false when the data or rates changed since the run; null while the
     *     run is not complete
     * @param members the member rows (interactive run, at once only); empty otherwise
     */
    public record Detail(
            RunView run,
            SideView asIs,
            SideView whatIf,
            List<ComponentView> components,
            Boolean current,
            List<MemberView> members) {}

    private final NamedParameterJdbcTemplate named;
    private final OrgDirectory org;
    private final Authorization policy;
    private final SystemParameters parameters;
    private final PayrollPeriods periods;
    private final PayConfiguration configuration;
    private final SimulationEngine engine;
    private final SimulationRecords records;
    private final BackgroundJobs jobs;
    private final AuditService audit;

    public SimulationService(
            NamedParameterJdbcTemplate named,
            OrgDirectory org,
            Authorization policy,
            SystemParameters parameters,
            PayrollPeriods periods,
            PayConfiguration configuration,
            SimulationEngine engine,
            SimulationRecords records,
            BackgroundJobs jobs,
            AuditService audit) {
        this.named = named;
        this.org = org;
        this.policy = policy;
        this.parameters = parameters;
        this.periods = periods;
        this.configuration = configuration;
        this.engine = engine;
        this.records = records;
        this.jobs = jobs;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public Overview overview(AccessContext context) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "VIEW", unit.getId());
        YearMonth period = periods.current();
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue(
                                "cycle",
                                SimulationRecords.prefix(CycleKeys.yymm(period), unit.getCode())
                                        + "%")
                        .addValue("unit", unitPattern(unit))
                        .addValue("me", login(context));
        Map<String, Object> tiles =
                named.queryForMap(
                        "SELECT (SELECT COUNT(*) FROM rule_simulation WHERE population_scope LIKE"
                                + " :cycle) AS runs, (SELECT COUNT(*) FROM rule_simulation WHERE"
                                + " population_scope LIKE :cycle AND requested_by = :me) AS mine,"
                                + " (SELECT MAX(population_count) FROM rule_simulation WHERE"
                                + " population_scope LIKE :cycle) AS largest, (SELECT COUNT(*) FROM"
                                + " rule_simulation WHERE population_scope LIKE :unit) AS retained"
                                + " FROM dual",
                        params);
        return new Overview(
                period.toString(),
                unit.getCode(),
                unit.getName(),
                setting(SystemParameters.Code.SIMULATION_MAX_SELECTION),
                setting(SystemParameters.Code.SIMULATION_INTERACTIVE_LIMIT),
                number(tiles.get("runs")),
                number(tiles.get("mine")),
                number(tiles.get("largest")),
                number(tiles.get("retained")));
    }

    /** Recent Simulation Runs of the user's unit, newest first, every period. */
    @Transactional(readOnly = true)
    public Paged<RunView> list(AccessContext context, String search, PageSpec spec) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "VIEW", unit.getId());
        MapSqlParameterSource params =
                listParams(unit, search)
                        .addValue("offset", (long) spec.page() * spec.size())
                        .addValue("size", spec.size());
        Long total =
                named.queryForObject(
                        "SELECT COUNT(*) FROM rule_simulation" + listWhere(search),
                        params,
                        Long.class);
        List<RunView> rows =
                named.query(
                        LIST
                                + listWhere(search)
                                + " ORDER BY simulation_id DESC OFFSET :offset ROWS FETCH NEXT"
                                + " :size ROWS ONLY",
                        params,
                        (rs, i) -> view(records.row(rs)));
        return new Paged<>(rows, spec.page(), spec.size(), CycleSupport.orZero(total));
    }

    /** Run records an export writes (the size check before streaming, SRS-G-13). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context, String search) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "EXPORT", unit.getId());
        Long total =
                named.queryForObject(
                        "SELECT COUNT(*) FROM rule_simulation" + listWhere(search),
                        listParams(unit, search),
                        Long.class);
        return CycleSupport.orZero(total);
    }

    /**
     * SRS l.1789, SC-05-0117: the run records of the unit as an export. The simulation marker
     * stands in the title and on every row: a line cut out of the file still says what it is
     * (SRS-15-9). Only the record is exported; the result rows are never kept (SRS-15-7).
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(AccessContext context, String search, ExportSink sink) throws IOException {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "EXPORT", unit.getId());
        sink.title(EXPORT_MARKER);
        sink.header(
                "Marker",
                "Run ID",
                "Requested by",
                "Requested at",
                "Period",
                "Population",
                "Size",
                "What-if",
                "DA IDA",
                "DA CDA",
                "Mode",
                "Result",
                "Results retained");
        List<RunView> rows =
                named.query(
                        LIST + listWhere(search) + " ORDER BY simulation_id DESC",
                        listParams(unit, search),
                        (rs, i) -> view(records.row(rs)));
        for (RunView r : rows) {
            sink.row(
                    EXPORT_MARKER,
                    r.reference(),
                    r.requestedBy(),
                    r.requestedAt(),
                    r.period(),
                    r.population(),
                    r.populationCount(),
                    r.whatIf(),
                    r.daIda(),
                    r.daCda(),
                    r.mode(),
                    r.status(),
                    r.resultsRetained() ? "Y" : "N");
        }
        ExportSink.requireComplete(rows.size(), sink);
    }

    private static final String LIST =
            "SELECT simulation_id, requested_by, requested_at, population_scope, population_count,"
                    + " input_hash, impact_summary FROM rule_simulation";

    /** The drawn marker of every simulation output (SRS l.1789). */
    static final String EXPORT_MARKER = "SIMULATION";

    private static String listWhere(String search) {
        return " WHERE population_scope LIKE :unit"
                + (search == null
                        ? ""
                        : " AND (LOWER(requested_by) LIKE :search ESCAPE '!' OR"
                                + " LOWER(population_scope) LIKE :search ESCAPE '!')");
    }

    private MapSqlParameterSource listParams(OrgLocation unit, String search) {
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("unit", unitPattern(unit));
        if (search != null) {
            params.addValue(
                    "search",
                    "%"
                            + search.toLowerCase(java.util.Locale.ROOT)
                                    .replace("!", "!!")
                                    .replace("%", "!%")
                                    .replace("_", "!_")
                            + "%");
        }
        return params;
    }

    /** One run with its summary; SRS-15-7: says whether the data changed since it ran. */
    @Transactional(readOnly = true)
    public Detail detail(AccessContext context, long id) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "VIEW", unit.getId());
        SimulationRecords.Row row =
                records.find(id)
                        .filter(r -> r.doc().unitId() == unit.getId())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        Boolean current =
                row.doc().status() == SimulationRecords.Status.COMPLETE
                        ? engine.inputHash(row.doc().request()).equals(row.inputHash())
                        : null;
        return detail(row, current, List.of());
    }

    // ---------------------------------------------------------------- run

    /**
     * SRS-15-7: runs a simulation. At or below the interactive limit the result comes back at once,
     * with the member rows; above it the run is queued (status QUEUED, its C-13 request named).
     */
    @Transactional
    public Detail run(AccessContext context, Input input) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "RUN", unit.getId());
        long max = required(SystemParameters.Code.SIMULATION_MAX_SELECTION);
        long interactive = required(SystemParameters.Code.SIMULATION_INTERACTIVE_LIMIT);
        SimulationRules.selectionDefect(input.population(), input.members(), max)
                .ifPresent(
                        d -> {
                            throw new FciException(
                                    ApiErrorCode.SIMULATION_SELECTION,
                                    new Object[] {d},
                                    List.of(),
                                    null);
                        });
        SimulationRules.whatIfDefect(input.whatIf(), input.daIda(), input.daCda())
                .ifPresent(
                        d -> {
                            throw new FciException(
                                    ApiErrorCode.SIMULATION_WHAT_IF,
                                    new Object[] {d},
                                    List.of(),
                                    null);
                        });
        List<Long> locations = engine.locations(unit.getId());
        Map<Long, String[]> people =
                input.population() == SimulationRules.Population.OFFICE
                        ? null
                        : resolve(input.members(), locations);
        YearMonth period = periods.current();
        SimulationEngine.Request request =
                new SimulationEngine.Request(
                        unit.getId(),
                        period,
                        people == null ? null : List.copyOf(new TreeSet<>(people.keySet())),
                        input.whatIf(),
                        input.daIda(),
                        input.daCda());
        long count = engine.count(request);
        if (!SimulationRules.withinMaximum(count, max)) {
            throw new FciException(
                    ApiErrorCode.SIMULATION_SELECTION,
                    new Object[] {"TOO_MANY (" + count + " > " + max + ")"},
                    List.of(),
                    null);
        }
        SimulationRules.Mode mode = SimulationRules.mode(count, interactive);
        SimulationRecords.Doc doc =
                new SimulationRecords.Doc(
                        mode == SimulationRules.Mode.INTERACTIVE
                                ? SimulationRecords.Status.COMPLETE
                                : SimulationRecords.Status.QUEUED,
                        mode,
                        period.toString(),
                        unit.getId(),
                        unit.getCode(),
                        input.population(),
                        request.employeeIds(),
                        input.whatIf(),
                        input.daIda(),
                        input.daCda(),
                        null,
                        0,
                        null,
                        null,
                        null,
                        null);
        String me = login(context);
        if (mode == SimulationRules.Mode.BACKGROUND) {
            long id =
                    records.insert(
                            me, CycleKeys.yymm(period), doc, count, SimulationRecords.NOT_YET);
            long requestId =
                    jobs.enqueue(
                            context,
                            BackgroundJobs.Kind.SIMULATION_RUN,
                            unit.getId(),
                            Map.of(
                                    "simulationId", String.valueOf(id),
                                    "locationId", String.valueOf(unit.getId()),
                                    "payPeriod", period.toString()));
            records.attachJob(id, doc, requestId);
            record(me, id, doc, count);
            return detail(records.find(id).orElseThrow(), null, List.of());
        }
        SimulationEngine.Outcome outcome = engine.run(request, true, n -> {});
        SimulationRecords.Doc done = doc.outcome(outcome);
        long id = records.insert(me, CycleKeys.yymm(period), done, count, outcome.inputHash());
        record(me, id, done, count);
        return detail(records.find(id).orElseThrow(), true, members(outcome.members(), people));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The employee numbers as members of the unit (ACTIVE records of its locations); one not found
     * is refused, naming the first few.
     */
    private Map<Long, String[]> resolve(List<String> numbers, List<Long> locations) {
        List<String> wanted = numbers.stream().map(String::strip).toList();
        Map<Long, String[]> found = new LinkedHashMap<>();
        for (List<String> chunk : chunk(wanted)) {
            for (List<Long> locs : SimulationEngine.chunks(locations)) {
                named.query(
                        "SELECT employee_id, emp_num, first_name, last_name FROM employee WHERE"
                                + " record_status = 'ACTIVE' AND emp_num IN (:numbers) AND"
                                + " location_id IN (:locations)",
                        new MapSqlParameterSource()
                                .addValue("numbers", chunk)
                                .addValue("locations", locs),
                        rs -> {
                            found.put(
                                    rs.getLong("employee_id"),
                                    new String[] {
                                        rs.getString("emp_num"),
                                        name(rs.getString("first_name"), rs.getString("last_name"))
                                    });
                        });
            }
        }
        java.util.Set<String> known = new java.util.HashSet<>();
        found.values().forEach(v -> known.add(v[0]));
        List<String> missing = wanted.stream().filter(n -> !known.contains(n)).toList();
        if (!missing.isEmpty()) {
            throw new FciException(
                    ApiErrorCode.SIMULATION_MEMBER,
                    new Object[] {
                        String.join(", ", missing.subList(0, Math.min(NAMED, missing.size())))
                                + (missing.size() > NAMED
                                        ? " (+" + (missing.size() - NAMED) + ")"
                                        : "")
                    },
                    List.of(),
                    null);
        }
        return found;
    }

    /** The member rows with employee number and name (read for an office run). */
    private List<MemberView> members(
            List<SimulationEngine.MemberResult> rows, Map<Long, String[]> people) {
        Map<Long, String[]> labels = people == null ? new HashMap<>() : people;
        if (people == null && !rows.isEmpty()) {
            List<Long> ids = rows.stream().map(SimulationEngine.MemberResult::employeeId).toList();
            for (List<Long> chunk : SimulationEngine.chunks(ids)) {
                named.query(
                        "SELECT employee_id, emp_num, first_name, last_name FROM employee WHERE"
                                + " employee_id IN (:ids)",
                        new MapSqlParameterSource("ids", chunk),
                        rs -> {
                            labels.put(
                                    rs.getLong("employee_id"),
                                    new String[] {
                                        rs.getString("emp_num"),
                                        name(rs.getString("first_name"), rs.getString("last_name"))
                                    });
                        });
            }
        }
        List<MemberView> out = new ArrayList<>(rows.size());
        for (SimulationEngine.MemberResult r : rows) {
            String[] label = labels.getOrDefault(r.employeeId(), new String[] {"", ""});
            out.add(
                    new MemberView(
                            label[0],
                            label[1],
                            r.status(),
                            r.reason(),
                            r.gross(),
                            r.net(),
                            r.whatIfGross(),
                            r.whatIfNet()));
        }
        return out;
    }

    private Detail detail(SimulationRecords.Row row, Boolean current, List<MemberView> members) {
        SimulationRecords.Doc doc = row.doc();
        return new Detail(
                view(row),
                side(doc.asIs()),
                side(doc.whatIfSide()),
                components(doc.asIs(), doc.whatIfSide()),
                current,
                members);
    }

    private List<ComponentView> components(
            SimulationEngine.Side asIs, SimulationEngine.Side whatIf) {
        if (asIs == null) {
            return List.of();
        }
        TreeSet<Integer> codes = new TreeSet<>(asIs.components().keySet());
        if (whatIf != null) {
            codes.addAll(whatIf.components().keySet());
        }
        List<ComponentView> out = new ArrayList<>();
        for (Integer code : codes) {
            out.add(
                    new ComponentView(
                            code,
                            configuration
                                    .component(code)
                                    .map(PayConfiguration.Component::name)
                                    .orElse(String.valueOf(code)),
                            asIs.components().get(code),
                            whatIf == null ? null : whatIf.components().get(code)));
        }
        return out;
    }

    private static SideView side(SimulationEngine.Side s) {
        return s == null
                ? null
                : new SideView(
                        s.computed(), s.stopped(), s.skipped(), s.gross(), s.deductions(), s.net());
    }

    private static RunView view(SimulationRecords.Row row) {
        SimulationRecords.Doc doc = row.doc();
        return new RunView(
                row.id(),
                "SIM-" + row.id(),
                row.requestedBy(),
                row.requestedAt(),
                doc.period(),
                doc.population().name(),
                row.population(),
                doc.whatIf().name(),
                doc.daIda(),
                doc.daCda(),
                doc.mode().name(),
                doc.status().name(),
                doc.done(),
                doc.jobRequestId(),
                false,
                doc.failure());
    }

    private void record(String me, long id, SimulationRecords.Doc doc, long count) {
        audit.record(
                me,
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(id),
                                "simulation",
                                null,
                                doc.mode()
                                        + " "
                                        + doc.population()
                                        + " "
                                        + count
                                        + " "
                                        + doc.whatIf(),
                                "C-09 simulation run (never posted)")
                        .module(SCREEN));
    }

    private long required(SystemParameters.Code code) {
        Long value = setting(code);
        if (value == null) {
            throw new FciException(
                    ApiErrorCode.SIMULATION_NOT_CONFIGURED,
                    new Object[] {code.name()},
                    List.of(),
                    null);
        }
        return value;
    }

    private Long setting(SystemParameters.Code code) {
        java.util.OptionalLong value = parameters.configured(code);
        return value.isPresent() ? value.getAsLong() : null;
    }

    private static List<List<String>> chunk(List<String> values) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i += SimulationEngine.PAGE) {
            out.add(values.subList(i, Math.min(values.size(), i + SimulationEngine.PAGE)));
        }
        return out;
    }

    private static String unitPattern(OrgLocation unit) {
        return "C09;%;" + unit.getCode() + ";%";
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    static String name(String first, String last) {
        return last == null || last.isBlank() ? first : first + " " + last;
    }

    private OrgLocation unit(AccessContext context) {
        return CycleSupport.unit(org, context);
    }

    private static String login(AccessContext context) {
        return CycleSupport.login(context);
    }
}
