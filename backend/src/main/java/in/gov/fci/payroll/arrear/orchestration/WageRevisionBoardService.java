package in.gov.fci.payroll.arrear.orchestration;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures I-25 draws over its list (Wireframes v6.1, OD-121; SRS-8-25 steps 5 to 8, FRS
 * §6.19.8, §6.10.5): the four tiles and the on-screen "Revision Status Report (DO-wise)". The SRS
 * names the report, not its screen API (PENDING API-13-3). The revisions are all-India; the member
 * figures stay within the session's offices (all at Headquarters). A depot's members count under
 * its Divisional Office; the status of each office is read from these counts on the screen.
 */
@Service
@Loggable
public class WageRevisionBoardService {

    private static final String SCREEN = WageRevisionService.SCREEN;

    /** The revision statuses from pay fixation on (steps 5 to 8). */
    private static final String FIXED_STATES =
            "('PAY_FIXED', 'ARREARS_COMPUTED', 'PAYING', 'COMPLETED')";

    private static final String OFFICES =
            " FROM (SELECT CASE WHEN l.location_type IN ('DEPOT', 'SHED', 'FSD') THEN"
                    + " COALESCE(p.location_name, l.location_name) ELSE l.location_name END AS"
                    + " office, m.fixation_status, m.payment_status FROM wage_revision_member m"
                    + " JOIN org_location l ON l.location_id = m.location_id LEFT JOIN org_location"
                    + " p ON p.location_id = l.parent_location_id WHERE m.wage_revision_id = :id"
                    + " AND (:all = 'Y' OR m.location_id IN (:locations))) x";

    /** The four tiles; the member figures are those of the latest fixed revision. */
    public record Board(
            long events,
            long approved,
            long inSimulation,
            String fixedCode,
            String fixedName,
            long membersFixed,
            long exceptions,
            BigDecimal arrearsPaid,
            long officesDone,
            long offices) {}

    /**
     * One row of the Revision Status Report (DO-wise): members fixed, pending, in exception, paid.
     */
    public record OfficeRow(
            String office, long members, long fixed, long pending, long exception, long paid) {}

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;

    public WageRevisionBoardService(NamedParameterJdbcTemplate named, Authorization policy) {
        this.named = named;
        this.policy = policy;
    }

    /** The tiles of the screen. */
    @Transactional(readOnly = true)
    public Board board(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        Map<String, Object> events =
                named.queryForMap(
                        "SELECT COUNT(*) AS n, COALESCE(SUM(CASE WHEN revision_status IN"
                                + " ('APPROVED', 'PAY_FIXED', 'ARREARS_COMPUTED', 'PAYING',"
                                + " 'COMPLETED') THEN 1 ELSE 0 END), 0) AS approved,"
                                + " COALESCE(SUM(CASE WHEN revision_status = 'SIMULATED' THEN 1"
                                + " ELSE 0 END), 0) AS simulated FROM wage_revision",
                        new MapSqlParameterSource());
        long count = number(events.get("N"));
        long approved = number(events.get("APPROVED"));
        long simulated = number(events.get("SIMULATED"));
        List<Map<String, Object>> latest =
                named.queryForList(
                        "SELECT wage_revision_id, revision_code, revision_name FROM wage_revision"
                                + " WHERE revision_status IN "
                                + FIXED_STATES
                                + " ORDER BY wage_revision_id DESC FETCH FIRST 1 ROWS ONLY",
                        new MapSqlParameterSource());
        if (latest.isEmpty()) {
            return new Board(count, approved, simulated, null, null, 0, 0, BigDecimal.ZERO, 0, 0);
        }
        Map<String, Object> revision = latest.getFirst();
        MapSqlParameterSource p =
                scope(context).addValue("id", number(revision.get("WAGE_REVISION_ID")));
        Map<String, Object> members =
                named.queryForMap(
                        "SELECT COALESCE(SUM(CASE WHEN m.fixation_status IN ('FIXED',"
                            + " 'FIXED_INDIVIDUALLY') THEN 1 ELSE 0 END), 0) AS fixed,"
                            + " COALESCE(SUM(CASE WHEN m.fixation_status = 'EXCEPTION' THEN 1 ELSE"
                            + " 0 END), 0) AS exceptions, COALESCE(SUM(m.arrear_paid), 0) AS paid"
                            + " FROM wage_revision_member m WHERE m.wage_revision_id = :id AND"
                            + " (:all = 'Y' OR m.location_id IN (:locations))",
                        p);
        Map<String, Object> units =
                named.queryForMap(
                        "SELECT COUNT(*) AS offices, COALESCE(SUM(CASE WHEN x.unpaid = 0 THEN 1"
                            + " ELSE 0 END), 0) AS done FROM (SELECT m.location_id, SUM(CASE WHEN"
                            + " m.payment_status = 'PAID' THEN 0 ELSE 1 END) AS unpaid FROM"
                            + " wage_revision_member m WHERE m.wage_revision_id = :id AND (:all ="
                            + " 'Y' OR m.location_id IN (:locations)) GROUP BY m.location_id) x",
                        p);
        return new Board(
                count,
                approved,
                simulated,
                (String) revision.get("REVISION_CODE"),
                (String) revision.get("REVISION_NAME"),
                number(members.get("FIXED")),
                number(members.get("EXCEPTIONS")),
                (BigDecimal) members.get("PAID"),
                number(units.get("DONE")),
                number(units.get("OFFICES")));
    }

    /** SRS-8-25 step 8: the revision office by office, one page. */
    @Transactional(readOnly = true)
    public Paged<OfficeRow> offices(AccessContext context, long id, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        Long known =
                named.queryForObject(
                        "SELECT COUNT(*) FROM wage_revision WHERE wage_revision_id = :id",
                        new MapSqlParameterSource("id", id),
                        Long.class);
        if (known == null || known == 0) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        MapSqlParameterSource p = scope(context).addValue("id", id);
        Long total =
                named.queryForObject("SELECT COUNT(DISTINCT x.office)" + OFFICES, p, Long.class);
        p.addValue("offset", (long) page.page() * page.size()).addValue("size", page.size());
        List<OfficeRow> rows =
                named.query(
                        "SELECT x.office, COUNT(*), SUM(CASE WHEN x.fixation_status IN ('FIXED',"
                                + " 'FIXED_INDIVIDUALLY') THEN 1 ELSE 0 END), SUM(CASE WHEN"
                                + " x.fixation_status = 'PENDING' THEN 1 ELSE 0 END), SUM(CASE WHEN"
                                + " x.fixation_status = 'EXCEPTION' THEN 1 ELSE 0 END), SUM(CASE"
                                + " WHEN x.payment_status = 'PAID' THEN 1 ELSE 0 END)"
                                + OFFICES
                                + " GROUP BY x.office ORDER BY x.office OFFSET :offset ROWS FETCH"
                                + " NEXT :size ROWS ONLY",
                        p,
                        (rs, i) ->
                                new OfficeRow(
                                        rs.getString(1),
                                        rs.getLong(2),
                                        rs.getLong(3),
                                        rs.getLong(4),
                                        rs.getLong(5),
                                        rs.getLong(6)));
        return new Paged<>(rows, page.page(), page.size(), RowCounts.of(total));
    }

    private MapSqlParameterSource scope(AccessContext context) {
        return OfficeScope.of(context, policy.isAtHeadquarters(context));
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
