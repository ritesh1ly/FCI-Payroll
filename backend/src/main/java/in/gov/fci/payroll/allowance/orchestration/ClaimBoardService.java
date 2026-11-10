package in.gov.fci.payroll.allowance.orchestration;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.payroll.api.AccommodationRule;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tiles and the Lease / Licence table of I-03 and the breach tile of M-15 (SRS-11-11, SRS-11-3,
 * SRS-11-17, SRS-16-14; drawn, Wireframes v6.1; API-10-3). Every figure is scoped to the user's
 * offices. The 7 % figure is what the record holds: the rule itself is not built (PENDING 10-lease,
 * SRS-11-3), so nothing is computed here.
 */
@Service
@Loggable
public class ClaimBoardService {

    /** A claim in the month: not yet settled, or one whose period overlaps the month. */
    private static final String IN_MONTH =
            " (c.claim_status IN ('SUBMITTED', 'VALIDATED', 'CAPPED', 'APPROVED') OR"
                    + " (c.period_to >= :first AND c.period_from <= :last))";

    private static final String CLAIMS =
            " FROM reimbursement_claim c JOIN employee e ON e.employee_id = c.employee_id WHERE"
                    + " (:all = 'Y' OR e.location_id IN (:locations)) AND"
                    + IN_MONTH;

    private static final String LEASES =
            " FROM lease_accommodation_detail a JOIN employee e ON e.employee_id = a.employee_id"
                    + " WHERE (:all = 'Y' OR e.location_id IN (:locations)) AND a.effective_from <="
                    + " :today AND (a.effective_to IS NULL OR a.effective_to >= :today)";

    /** The four tiles of I-03 for the month. */
    public record Counts(
            long claims, BigDecimal payable, long pendingApproval, long capped, long leases) {}

    /**
     * One accommodation record in force.
     *
     * @param sevenPercent the stored 7 % of Basic, null until the rule is built (SRS-11-3)
     * @param hraSuppressed whether the engine stops HRA for it (the one test, SRS-1-3)
     * @param conflict lease held with HRA not stopped: SRS-11-17 "never pay lease and full HRA"
     */
    public record LeaseRow(
            long id,
            String eis,
            String name,
            String memberType,
            String accommodationType,
            BigDecimal leaseAmount,
            BigDecimal sevenPercent,
            boolean hraSuppressed,
            boolean conflict,
            String source,
            String hrmsRequestId,
            LocalDate effectiveFrom) {}

    private final NamedParameterJdbcTemplate named;
    private final ClaimScope scope;
    private final SystemParameters settings;

    public ClaimBoardService(
            NamedParameterJdbcTemplate named, ClaimScope scope, SystemParameters settings) {
        this.named = named;
        this.scope = scope;
        this.settings = settings;
    }

    /** I-03 tiles. */
    @Transactional(readOnly = true)
    public Counts counts(AccessContext context) {
        MapSqlParameterSource p = params(context, LabourClaimService.SCREEN);
        Map<String, Object> c =
                named.queryForMap(
                        "SELECT COUNT(*) AS claims, COALESCE(SUM(c.payable_amount), 0) AS payable,"
                            + " COALESCE(SUM(CASE WHEN c.claim_status IN ('VALIDATED', 'CAPPED')"
                            + " THEN 1 ELSE 0 END), 0) AS waiting, COALESCE(SUM(CASE WHEN"
                            + " c.was_capped = 'Y' THEN 1 ELSE 0 END), 0) AS capped"
                                + CLAIMS,
                        p);
        Long leases = named.queryForObject("SELECT COUNT(*)" + LEASES, p, Long.class);
        return new Counts(
                number(c.get("CLAIMS")),
                (BigDecimal) c.get("PAYABLE"),
                number(c.get("WAITING")),
                number(c.get("CAPPED")),
                RowCounts.of(leases));
    }

    /** M-15 tile: the claims capped at the entitlement this month, in the user's offices. */
    @Transactional(readOnly = true)
    public long ceilingBreaches(AccessContext context) {
        return RowCounts.of(
                named.queryForObject(
                        "SELECT COUNT(*)" + CLAIMS + " AND c.was_capped = 'Y'",
                        params(context, "M-15"),
                        Long.class));
    }

    /** The accommodation records in force today (SRS-11-17), read-only: paged on the server. */
    @Transactional(readOnly = true)
    public Paged<LeaseRow> leases(AccessContext context, PageSpec spec) {
        MapSqlParameterSource p = params(context, LabourClaimService.SCREEN);
        Long total = named.queryForObject("SELECT COUNT(*)" + LEASES, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<LeaseRow> rows =
                named.query(
                        "SELECT a.lease_detail_id, e.emp_num, e.first_name, e.last_name,"
                                + " e.employee_type, a.accommodation_type, a.lease_amount,"
                                + " a.seven_pct_basic_amount, a.hra_suppressed_flag, a.source_code,"
                                + " a.hrms_request_id, a.effective_from"
                                + LEASES
                                + " ORDER BY a.lease_detail_id DESC OFFSET :offset ROWS FETCH NEXT"
                                + " :size ROWS ONLY",
                        p,
                        (rs, i) -> {
                            String last = rs.getString(4);
                            String type = rs.getString(6);
                            boolean suppressed =
                                    AccommodationRule.hraSuppressed(
                                            type, "Y".equals(rs.getString(9)));
                            return new LeaseRow(
                                    rs.getLong(1),
                                    rs.getString(2),
                                    last == null ? rs.getString(3) : rs.getString(3) + " " + last,
                                    "STAFF".equals(rs.getString(5)) ? "EMPLOYEE" : "LABOUR",
                                    type,
                                    rs.getBigDecimal(7),
                                    rs.getBigDecimal(8),
                                    suppressed,
                                    !suppressed,
                                    rs.getString(10),
                                    rs.getString(11),
                                    rs.getDate(12).toLocalDate());
                        });
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    private MapSqlParameterSource params(AccessContext context, String screen) {
        Optional<Set<Long>> offices = scope.offices(context, screen, "VIEW");
        LocalDate today = settings.today();
        return new MapSqlParameterSource()
                .addValue("all", offices.isEmpty() ? "Y" : "N", Types.VARCHAR)
                .addValue("locations", offices.orElse(Set.of(-1L)))
                .addValue("today", Date.valueOf(today), Types.DATE)
                .addValue("first", Date.valueOf(today.withDayOfMonth(1)), Types.DATE)
                .addValue(
                        "last",
                        Date.valueOf(today.withDayOfMonth(today.lengthOfMonth())),
                        Types.DATE);
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
