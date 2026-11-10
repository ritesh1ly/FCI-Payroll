package in.gov.fci.payroll.nps.orchestration;

import in.gov.fci.payroll.nps.business.NpsRules;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Screen I-14 NPS Deduction (from HRMS) (plan 18 phase 18c; SRS-9-1, SRS-9-1a, SRS-9-2, SRS-9-6;
 * D18-7, D18-10): the month's NPS records of the user's cycle unit (every unit at Headquarters),
 * view only; the counts by status; the register export; and the NPS Deduction Report, member-wise
 * employee and employer amounts by month with the PRAN as a reference, by office and zone.
 *
 * <p>OPEN-QUESTION (PENDING 18-report-pdf): SRS-9-6 asks for the report in PDF and Excel; the
 * platform has no PDF renderer yet and the layout follows the current system (DDL report catalogue
 * note), so the report is produced as Excel / CSV only.
 */
@Service
@Loggable
public class NpsRegisterService {

    /** The screen of this module. */
    public static final String SCREEN = "I-14";

    /** I-14 filters: the month (YYYYMM, required), the status shown, an employee number. */
    public record Filter(String payrollYymm, String status, String eis) {}

    /**
     * One row of I-14.
     *
     * @param status APPLIED, RECEIVED, STOPPED_SEPARATION, FLAG_WITHOUT_AMOUNT or
     *     AMOUNT_WITHOUT_FLAG (the mismatch first: it is what the user acts on)
     */
    public record Row(
            long id,
            String eis,
            String employee,
            String office,
            boolean npsFlag,
            String pran,
            BigDecimal employeeAmount,
            BigDecimal employerAmount,
            BigDecimal deducted,
            LocalDate effectiveFrom,
            String status,
            String payPattern,
            String providentFund) {}

    /** One count of the month. */
    public record Count(String status, long count) {}

    /**
     * The four I-14 tiles of a month (SRS-9-1, SRS-9-1a): the amounts as HRMS sent them, summed
     * only, never recalculated (R-SRC). A mismatch carries no amount into the sums.
     *
     * @param yymm the month shown: the month asked for, or the unit's latest month before it that
     *     holds records (null when the unit has none)
     */
    public record Summary(
            String yymm,
            long members,
            BigDecimal employeeAmount,
            BigDecimal employerAmount,
            long mismatches) {}

    /** What I-14 shows: the status shown, from the stored statuses (SQL). */
    static final String STATUS =
            "CASE WHEN n.mismatch_status IN ('FLAG_WITHOUT_AMOUNT', 'AMOUNT_WITHOUT_FLAG') THEN"
                    + " n.mismatch_status ELSE n.apply_status END";

    private static final String FROM =
            " FROM nps_deduction n JOIN employee e ON e.employee_id = n.employee_id LEFT JOIN"
                    + " org_location l ON l.location_id = e.location_id LEFT JOIN org_zone z ON"
                    + " z.zone_id = l.zone_id";

    private static final String WHERE =
            " WHERE n.payroll_yymm = :yymm AND n.record_type = 'MONTHLY' AND (:all = 'Y' OR"
                    + " e.location_id IN (:locations)) AND (:status IS NULL OR "
                    + STATUS
                    + " = :status) AND (:eis IS NULL OR e.emp_num = :eis)";

    private static final String SELECT =
            "SELECT n.nps_deduction_id, e.emp_num, e.first_name, e.last_name, l.location_name,"
                    + " n.hrms_nps_flag, n.pran_reference, n.employee_amount, n.employer_amount,"
                    + " n.deducted_amount, n.hrms_effective_from, e.pay_pattern,"
                    + " e.provident_fund_type, "
                    + STATUS
                    + " AS shown_status";

    private final NamedParameterJdbcTemplate named;
    private final OrgDirectory org;
    private final Authorization policy;

    public NpsRegisterService(
            NamedParameterJdbcTemplate named, OrgDirectory org, Authorization policy) {
        this.named = named;
        this.org = org;
        this.policy = policy;
    }

    /** I-14: the month's records, mismatches first, then by employee number; paged. */
    @Transactional(readOnly = true)
    public Paged<Row> page(AccessContext context, Filter filter, PageSpec spec) {
        MapSqlParameterSource params = params(scope(context, "VIEW"), filter);
        Long total = named.queryForObject("SELECT COUNT(*)" + FROM + WHERE, params, Long.class);
        params.addValue("offset", (long) spec.page() * spec.size());
        params.addValue("size", spec.size());
        List<Row> rows =
                named.query(
                        SELECT
                                + FROM
                                + WHERE
                                + " ORDER BY CASE WHEN n.mismatch_status IN"
                                + " ('FLAG_WITHOUT_AMOUNT', 'AMOUNT_WITHOUT_FLAG') THEN 0 ELSE 1"
                                + " END, e.emp_num, n.nps_deduction_id OFFSET :offset ROWS FETCH"
                                + " NEXT :size ROWS ONLY",
                        params,
                        (rs, i) -> row(rs));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** SRS-9-1a: the month's counts by the status shown. */
    @Transactional(readOnly = true)
    public List<Count> counts(AccessContext context, String payrollYymm) {
        MapSqlParameterSource params =
                params(scope(context, "VIEW"), new Filter(payrollYymm, null, null));
        return named.query(
                "SELECT "
                        + STATUS
                        + " AS shown_status, COUNT(*) AS n"
                        + FROM
                        + WHERE
                        + " GROUP BY "
                        + STATUS
                        + " ORDER BY 1",
                params,
                (rs, i) -> new Count(rs.getString("shown_status"), rs.getLong("n")));
    }

    /**
     * The I-14 tiles (SRS-9-1, SRS-9-1a; API-18-1): the month's members and amounts. A month
     * without records at the unit shows the unit's latest month before it, as C-02 does for its
     * run, so the screen opens on the unit's records.
     */
    @Transactional(readOnly = true)
    public Summary summary(AccessContext context, String payrollYymm) {
        Long unit = scope(context, "VIEW");
        String shown = latestMonth(unit, payrollYymm);
        if (shown == null) {
            return new Summary(null, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        return named.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN n.hrms_nps_flag = 'Y' THEN 1 ELSE 0 END), 0) AS"
                        + " members, COALESCE(SUM(CASE WHEN n.mismatch_status = 'CONSISTENT' THEN"
                        + " n.employee_amount END), 0) AS employee_total, COALESCE(SUM(CASE WHEN"
                        + " n.mismatch_status = 'CONSISTENT' THEN n.employer_amount END), 0) AS"
                        + " employer_total, COALESCE(SUM(CASE WHEN n.mismatch_status IN"
                        + " ('FLAG_WITHOUT_AMOUNT', 'AMOUNT_WITHOUT_FLAG') THEN 1 ELSE 0 END), 0)"
                        + " AS mismatches"
                        + FROM
                        + WHERE,
                params(unit, new Filter(shown, null, null)),
                (rs, i) ->
                        new Summary(
                                shown,
                                rs.getLong("members"),
                                rs.getBigDecimal("employee_total"),
                                rs.getBigDecimal("employer_total"),
                                rs.getLong("mismatches")));
    }

    /** The month itself when the unit holds records for it, else its latest month before it. */
    private String latestMonth(Long unit, String payrollYymm) {
        MapSqlParameterSource params = params(unit, new Filter(payrollYymm, null, null));
        return named.queryForObject(
                "SELECT MAX(n.payroll_yymm) FROM nps_deduction n JOIN employee e ON e.employee_id ="
                        + " n.employee_id WHERE n.payroll_yymm <= :yymm AND n.record_type ="
                        + " 'MONTHLY' AND (:all = 'Y' OR e.location_id IN (:locations))",
                params,
                String.class);
    }

    /** SRS-G-13: the rows the export will carry (checked against the limit first). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context, Filter filter) {
        MapSqlParameterSource params = params(scope(context, "EXPORT"), filter);
        Long n = named.queryForObject("SELECT COUNT(*)" + FROM + WHERE, params, Long.class);
        return RowCounts.of(n);
    }

    /** I-14 Export: the month's register as on screen, in one read-only snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(AccessContext context, Filter filter, ExportSink sink) throws IOException {
        MapSqlParameterSource params = params(scope(context, "EXPORT"), filter);
        Long expected = named.queryForObject("SELECT COUNT(*)" + FROM + WHERE, params, Long.class);
        sink.header(
                "eis",
                "employee",
                "office",
                "nps_flag",
                "pran",
                "employee_nps",
                "employer_nps",
                "deducted",
                "effective_from",
                "status");
        write(
                SELECT + FROM + WHERE + " ORDER BY e.emp_num, n.nps_deduction_id",
                params,
                rs -> {
                    Row r = row(rs);
                    return new Object[] {
                        r.eis(),
                        r.employee(),
                        nz(r.office()),
                        r.npsFlag() ? "Y" : "N",
                        nz(r.pran()),
                        nz(r.employeeAmount()),
                        nz(r.employerAmount()),
                        nz(r.deducted()),
                        nz(r.effectiveFrom()),
                        r.status()
                    };
                },
                sink);
        ExportSink.requireComplete(RowCounts.of(expected), sink);
    }

    /** The NPS Deduction Report's size for the months (checked against the export limit). */
    @Transactional(readOnly = true)
    public long reportSize(AccessContext context, String fromYymm, String toYymm) {
        MapSqlParameterSource params = reportParams(context, fromYymm, toYymm);
        Long n = named.queryForObject("SELECT COUNT(*)" + FROM + REPORT_WHERE, params, Long.class);
        return RowCounts.of(n);
    }

    /**
     * SRS-9-6: the NPS Deduction Report: member-wise employee and employer NPS by month, the PRAN
     * as a reference, with the office and zone (unit, zone and HQ level by the user's scope); the
     * NPS members' records only (flag set, consistent).
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void report(AccessContext context, String fromYymm, String toYymm, ExportSink sink)
            throws IOException {
        MapSqlParameterSource params = reportParams(context, fromYymm, toYymm);
        Long expected =
                named.queryForObject("SELECT COUNT(*)" + FROM + REPORT_WHERE, params, Long.class);
        sink.header(
                "zone",
                "office",
                "month",
                "eis",
                "employee",
                "pran",
                "employee_nps",
                "employer_nps",
                "status");
        write(
                "SELECT z.zone_name, l.location_name, n.payroll_yymm, e.emp_num, e.first_name,"
                        + " e.last_name, n.pran_reference, n.employee_amount, n.employer_amount, "
                        + STATUS
                        + " AS shown_status"
                        + FROM
                        + REPORT_WHERE
                        + " ORDER BY z.zone_name, l.location_name, n.payroll_yymm, e.emp_num,"
                        + " n.nps_deduction_id",
                params,
                rs ->
                        new Object[] {
                            nz(rs.getString("zone_name")),
                            nz(rs.getString("location_name")),
                            rs.getString("payroll_yymm"),
                            rs.getString("emp_num"),
                            name(rs.getString("first_name"), rs.getString("last_name")),
                            nz(rs.getString("pran_reference")),
                            nz(rs.getBigDecimal("employee_amount")),
                            nz(rs.getBigDecimal("employer_amount")),
                            rs.getString("shown_status")
                        },
                sink);
        ExportSink.requireComplete(RowCounts.of(expected), sink);
    }

    private static final String REPORT_WHERE =
            " WHERE n.payroll_yymm BETWEEN :from AND :to AND n.record_type = 'MONTHLY' AND"
                    + " n.hrms_nps_flag = 'Y' AND n.mismatch_status IN ('CONSISTENT',"
                    + " 'RESOLVED_IN_HRMS') AND (:all = 'Y' OR e.location_id IN (:locations))";

    // ---------------------------------------------------------------- helpers

    /** The cycle unit the user sees (null at Headquarters: every unit), checked for the action. */
    Long scope(AccessContext context, String action) {
        if (context.locationId() == null) {
            throw new FciException(CommonErrorCode.ACCESS_DENIED);
        }
        if (policy.isAtHeadquarters(context)) {
            policy.requireAt(context, SCREEN, action, context.locationId());
            return null;
        }
        OrgLocation unit =
                org.cycleUnitOf(context.locationId())
                        .orElseThrow(() -> new FciException(ApiErrorCode.CYCLE_NO_UNIT));
        policy.requireAt(context, SCREEN, action, unit.getId());
        return unit.getId();
    }

    private MapSqlParameterSource params(Long unit, Filter filter) {
        if (NpsRules.month(filter.payrollYymm()).isEmpty()) {
            throw invalid();
        }
        return scoped(unit)
                .addValue("yymm", filter.payrollYymm())
                .addValue("status", blank(filter.status()), Types.VARCHAR)
                .addValue("eis", blank(filter.eis()), Types.VARCHAR);
    }

    private MapSqlParameterSource reportParams(
            AccessContext context, String fromYymm, String toYymm) {
        if (NpsRules.month(fromYymm).isEmpty()
                || NpsRules.month(toYymm).isEmpty()
                || fromYymm.compareTo(toYymm) > 0) {
            throw invalid();
        }
        return scoped(scope(context, "EXPORT")).addValue("from", fromYymm).addValue("to", toYymm);
    }

    private MapSqlParameterSource scoped(Long unit) {
        Set<Long> locations = unit == null ? Set.of(-1L) : org.subtree(unit);
        return new MapSqlParameterSource()
                .addValue("all", unit == null ? "Y" : "N", Types.VARCHAR)
                .addValue("locations", locations);
    }

    /** A row of the export, from the current result row. */
    @FunctionalInterface
    private interface Cells {
        Object[] of(ResultSet rs) throws SQLException;
    }

    private void write(String sql, MapSqlParameterSource params, Cells cells, ExportSink sink)
            throws IOException {
        try {
            named.query(
                    sql,
                    params,
                    rs -> {
                        try {
                            sink.row(cells.of(rs));
                        } catch (IOException failed) {
                            throw new UncheckedIOException(failed);
                        }
                    });
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    private static Row row(ResultSet rs) throws SQLException {
        java.sql.Date from = rs.getDate("hrms_effective_from");
        return new Row(
                rs.getLong("nps_deduction_id"),
                rs.getString("emp_num"),
                name(rs.getString("first_name"), rs.getString("last_name")),
                rs.getString("location_name"),
                "Y".equals(rs.getString("hrms_nps_flag")),
                rs.getString("pran_reference"),
                rs.getBigDecimal("employee_amount"),
                rs.getBigDecimal("employer_amount"),
                rs.getBigDecimal("deducted_amount"),
                from == null ? null : from.toLocalDate(),
                rs.getString("shown_status"),
                rs.getString("pay_pattern"),
                rs.getString("provident_fund_type"));
    }

    private static String name(String first, String last) {
        return last == null || last.isBlank() ? first : first + " " + last;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Object nz(Object value) {
        return value == null ? "" : value;
    }

    /** A month (YYYYMM) or a range of months that is not one: the request is not valid. */
    private static FciException invalid() {
        return new FciException(CommonErrorCode.VALIDATION_FAILED);
    }
}
