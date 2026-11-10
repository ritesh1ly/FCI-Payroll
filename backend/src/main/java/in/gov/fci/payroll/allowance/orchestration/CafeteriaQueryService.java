package in.gov.fci.payroll.allowance.orchestration;

import in.gov.fci.payroll.allowance.business.ClaimRules;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * I-16 Cafeteria Allowance (plan 10 phase 10f; SRS-11-16, SRS I-16 field table): the selections
 * received from HRMS, read-only, newest first, each with its components and the current month's pay
 * effect (pro rata for a part month). Scoped to the user's offices. With a financial year named
 * (the screen always names one) a staff member in service with no selection is listed too, status
 * NO_SELECTION (SRS I-16 Status list).
 */
@Service
@Loggable
public class CafeteriaQueryService {

    static final String SCREEN = "I-16";

    /** The status of a row with no selection: never stored, the member simply has no version. */
    public static final String NO_SELECTION = "NO_SELECTION";

    private static final String ARREAR_RAISED = "ARREAR_RAISED";

    /** The statuses that pay in the month. */
    private static final Set<String> LIVE = Set.of("APPLIED", ARREAR_RAISED, "SUPERSEDED");

    private static final String PAGE = " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY";

    private static final int EXPORT_PAGE = 500;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyyyMM");

    /** One selected component. */
    public record Component(int payCode, String name, BigDecimal monthlyAmount) {}

    /**
     * One selection version as I-16 shows it.
     *
     * @param id null on a NO_SELECTION row
     * @param arrearFrom first locked month of the arrear (YYYYMM), when one was raised
     * @param arrearTo last locked month of the arrear (YYYYMM)
     * @param arrearIn the month the arrear was raised in (YYYYMM)
     */
    public record Selection(
            Long id,
            String eis,
            String name,
            String category,
            String finYear,
            int version,
            LocalDate startDate,
            LocalDate endDate,
            String hrmsReference,
            String status,
            List<Component> components,
            BigDecimal payEffect,
            String arrearFrom,
            String arrearTo,
            String arrearIn) {}

    /** The arrear event of a selection, its months and the month it was raised in. */
    private record Arrear(String from, String to, String raisedIn) {}

    private static final Arrear NO_ARREAR = new Arrear(null, null, null);

    private static final String FROM =
            " FROM cafeteria_election c JOIN employee e ON e.employee_id = c.employee_id WHERE"
                    + " (:all = 'Y' OR e.location_id IN (:locations)) AND (:eis IS NULL OR"
                    + " e.emp_num = :eis) AND (:finYear IS NULL OR c.fin_year = :finYear)";

    /** Of one year: every staff member on the rolls, with the selection where one is held. */
    private static final String FROM_YEAR =
            " FROM employee e LEFT JOIN cafeteria_election c ON c.employee_id = e.employee_id AND"
                + " c.fin_year = :finYear WHERE (:all = 'Y' OR e.location_id IN (:locations)) AND"
                + " (:eis IS NULL OR e.emp_num = :eis) AND e.employee_type = 'STAFF' AND"
                + " (c.election_id IS NOT NULL OR (e.record_status = 'ACTIVE' AND"
                + " e.employment_status NOT IN ('SEPARATED', 'DISMISSED')))";

    private static final String COLUMNS =
            "SELECT c.election_id, e.emp_num, e.first_name, e.last_name, e.pay_pattern,"
                    + " c.fin_year, c.version_no, c.effective_from, c.effective_to,"
                    + " c.hrms_request_id, c.election_status";

    /** Selections first, newest first; a member with none after them, by number. */
    private static final String ORDER =
            " ORDER BY CASE WHEN c.election_id IS NULL THEN 1 ELSE 0 END, c.election_id DESC,"
                    + " e.emp_num";

    private final NamedParameterJdbcTemplate named;
    private final ClaimScope scope;
    private final SystemParameters settings;

    public CafeteriaQueryService(
            NamedParameterJdbcTemplate named, ClaimScope scope, SystemParameters settings) {
        this.named = named;
        this.scope = scope;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public Paged<Selection> selections(AccessContext context, String eis, PageSpec spec) {
        return selections(context, eis, null, spec);
    }

    /**
     * SRS l.2836-2838 (I-16 "for the financial year"): the selections of one financial year
     * (YYYY-YY), or of every year when none is given.
     */
    @Transactional(readOnly = true)
    public Paged<Selection> selections(
            AccessContext context, String eis, String finYear, PageSpec spec) {
        MapSqlParameterSource p = params(context, eis, finYear);
        String from = from(finYear);
        Long total = named.queryForObject("SELECT COUNT(*)" + from, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<Selection> rows = read(COLUMNS + from + ORDER + PAGE, p);
        return new Paged<>(complete(rows), spec.page(), spec.size(), RowCounts.of(total));
    }

    /** Rows an export would write, for the export size check. */
    @Transactional(readOnly = true)
    public long size(AccessContext context, String eis, String finYear) {
        return RowCounts.of(
                named.queryForObject(
                        "SELECT COUNT(*)" + from(finYear),
                        params(context, eis, finYear),
                        Long.class));
    }

    /** SRS-G-13, I-16 EXPORT: the list as the screen filters it, row for row. */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void selections(AccessContext context, String eis, String finYear, ExportSink sink)
            throws IOException {
        MapSqlParameterSource p = params(context, eis, finYear);
        String from = from(finYear);
        sink.header(
                "eis",
                "name",
                "category",
                "financial_year",
                "selected_components",
                "start_date",
                "hrms_reference",
                "pay_effect",
                "status");
        Long expected = named.queryForObject("SELECT COUNT(*)" + from, p, Long.class);
        int page = 0;
        List<Selection> rows;
        do {
            p.addValue("offset", (long) page * EXPORT_PAGE).addValue("size", EXPORT_PAGE);
            rows = complete(read(COLUMNS + from + ORDER + PAGE, p));
            for (Selection s : rows) {
                sink.row(
                        s.eis(),
                        s.name(),
                        s.category(),
                        s.finYear(),
                        s.components().stream()
                                .map(Component::name)
                                .collect(Collectors.joining(" / ")),
                        s.startDate(),
                        s.hrmsReference(),
                        s.payEffect(),
                        s.status());
            }
            page++;
        } while (rows.size() == EXPORT_PAGE);
        ExportSink.requireComplete(RowCounts.of(expected), sink);
    }

    private static String from(String finYear) {
        return finYear == null || finYear.isBlank() ? FROM : FROM_YEAR;
    }

    private MapSqlParameterSource params(AccessContext context, String eis, String finYear) {
        Optional<Set<Long>> offices = scope.offices(context, SCREEN, "VIEW");
        return new MapSqlParameterSource()
                .addValue("all", offices.isEmpty() ? "Y" : "N", Types.VARCHAR)
                .addValue("locations", offices.orElse(Set.of(-1L)))
                .addValue("eis", eis == null || eis.isBlank() ? null : eis, Types.VARCHAR)
                .addValue(
                        "finYear",
                        finYear == null || finYear.isBlank() ? null : finYear,
                        Types.VARCHAR);
    }

    private List<Selection> read(String sql, MapSqlParameterSource p) {
        List<Selection> rows = new ArrayList<>();
        named.query(
                sql,
                p,
                rs -> {
                    Date start = rs.getDate(8);
                    Date to = rs.getDate(9);
                    String last = rs.getString(4);
                    String name = last == null ? rs.getString(3) : rs.getString(3) + " " + last;
                    rows.add(
                            start == null
                                    ? new Selection(
                                            null,
                                            rs.getString(2),
                                            name,
                                            rs.getString(5),
                                            null,
                                            0,
                                            null,
                                            null,
                                            null,
                                            NO_SELECTION,
                                            List.of(),
                                            BigDecimal.ZERO,
                                            null,
                                            null,
                                            null)
                                    : new Selection(
                                            rs.getLong(1),
                                            rs.getString(2),
                                            name,
                                            rs.getString(5),
                                            rs.getString(6),
                                            rs.getInt(7),
                                            start.toLocalDate(),
                                            to == null ? null : to.toLocalDate(),
                                            rs.getString(10),
                                            rs.getString(11),
                                            List.of(),
                                            BigDecimal.ZERO,
                                            null,
                                            null,
                                            null));
                });
        return rows;
    }

    /** Each row with its components, the month's pay effect and the arrear raised on it. */
    private List<Selection> complete(List<Selection> rows) {
        YearMonth month = YearMonth.from(settings.today());
        List<Long> ids = rows.stream().map(Selection::id).filter(Objects::nonNull).toList();
        Map<Long, List<Component>> components = components(ids);
        Map<Long, Arrear> arrears =
                rows.stream().anyMatch(s -> ARREAR_RAISED.equals(s.status()))
                        ? arrears(ids)
                        : Map.of();
        List<Selection> out = new ArrayList<>();
        for (Selection s : rows) {
            BigDecimal effect = BigDecimal.ZERO;
            List<Component> cs =
                    s.id() == null ? List.of() : components.getOrDefault(s.id(), List.of());
            if (LIVE.contains(s.status())) {
                for (Component c : cs) {
                    effect =
                            effect.add(
                                    ClaimRules.proRata(
                                            c.monthlyAmount(), s.startDate(), s.endDate(), month));
                }
            }
            Arrear arrear = s.id() == null ? NO_ARREAR : arrears.getOrDefault(s.id(), NO_ARREAR);
            out.add(
                    new Selection(
                            s.id(),
                            s.eis(),
                            s.name(),
                            s.category(),
                            s.finYear(),
                            s.version(),
                            s.startDate(),
                            s.endDate(),
                            s.hrmsReference(),
                            s.status(),
                            cs,
                            effect,
                            arrear.from(),
                            arrear.to(),
                            arrear.raisedIn()));
        }
        return out;
    }

    private Map<Long, Arrear> arrears(List<Long> ids) {
        Map<Long, Arrear> out = new HashMap<>();
        named.query(
                "SELECT c.election_id, a.scope_from_yymm, a.scope_to_yymm, a.raised_at FROM"
                        + " cafeteria_election c JOIN arrear_event a ON a.arrear_event_id ="
                        + " c.arrear_event_id WHERE c.election_id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                rs -> {
                    out.put(
                            rs.getLong(1),
                            new Arrear(
                                    rs.getString(2),
                                    rs.getString(3),
                                    YYMM.format(rs.getTimestamp(4).toInstant().atZone(IST))));
                });
        return out;
    }

    private Map<Long, List<Component>> components(List<Long> ids) {
        Map<Long, List<Component>> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        named.query(
                "SELECT l.election_id, p.legacy_pay_code, p.component_name, l.elected_amount FROM"
                        + " cafeteria_election_line l JOIN pay_component p ON p.component_id ="
                        + " l.component_id WHERE l.election_id IN (:ids) ORDER BY"
                        + " p.legacy_pay_code",
                new MapSqlParameterSource("ids", ids),
                rs -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new Component(rs.getInt(2), rs.getString(3), rs.getBigDecimal(4)));
                });
        return out;
    }
}
