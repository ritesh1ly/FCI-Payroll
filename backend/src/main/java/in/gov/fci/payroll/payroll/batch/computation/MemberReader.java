package in.gov.fci.payroll.payroll.batch.computation;

import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.payroll.business.PayRules;
import in.gov.fci.payroll.platform.persistence.InLists;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The members of a computation: ACTIVE records of the partition's locations whose employment did
 * not end (SEPARATED / DISMISSED are not paid), with what the pay rules need, in one paged query:
 *
 * <ul>
 *   <li>city class: the {@code city_type_location_map} version in force at the period start (target
 *       DDL v4.0), else the location's own class;
 *   <li>subsistence %: of the latest approved SUSPENSION (I-17);
 *   <li>salary stopped: the latest approved SALARY_STOP / HOLD / SALARY_RELEASE up to the period
 *       end is a stop or hold;
 *   <li>allowance flags (plan 16 phase 16d, SRS-1-4): the M-01 flags set to Y in their latest
 *       version in force at the period start;
 *   <li>payment flag (SRS-1-7): Stop-Payment, Hold or Absconding on the employee record;
 *   <li>Grade Pay (SRS l.5409, l.5412): {@code org_grade.grade_pay} of the member's grade; no grade
 *       or an empty column is no Grade Pay;
 *   <li>accommodation (SRS-1-3, SRS-11-17): the {@code lease_accommodation_detail} record in force
 *       on the period's first day, the day the rates and flags are read: a change within a month
 *       counts from the next month (no source gives a part-month rule, PENDING REF-1).
 * </ul>
 *
 * Stateful and restartable (sort key employee_id, saveState); one reader per partition.
 */
public final class MemberReader {

    /** One member as read. */
    public record MemberRow(
            Long employeeId,
            Long locationId,
            BigDecimal basic,
            String payPattern,
            String cityClass,
            String employmentStatus,
            BigDecimal subsistencePercent,
            String lastPayEvent,
            String allowanceFlags,
            String paymentFlag,
            // SRS-1-14 (i) (US-GAP-44): a pay scale version is in force for the member's band.
            boolean scaleInForce,
            BigDecimal gradePay,
            String accommodationType,
            boolean accommodationSuppressed)
            implements in.gov.fci.payroll.jobs.batch.support.ItemKey {

        /** A member with no Grade Pay and no accommodation record. */
        public MemberRow(
                Long employeeId,
                Long locationId,
                java.math.BigDecimal basic,
                String payPattern,
                String cityClass,
                String employmentStatus,
                java.math.BigDecimal subsistencePercent,
                String lastPayEvent,
                String allowanceFlags,
                String paymentFlag,
                boolean scaleInForce) {
            this(
                    employeeId,
                    locationId,
                    basic,
                    payPattern,
                    cityClass,
                    employmentStatus,
                    subsistencePercent,
                    lastPayEvent,
                    allowanceFlags,
                    paymentFlag,
                    scaleInForce,
                    null,
                    null,
                    false);
        }

        /** A member read before the pay scale check (callers that do not run it). */
        public MemberRow(
                Long employeeId,
                Long locationId,
                java.math.BigDecimal basic,
                String payPattern,
                String cityClass,
                String employmentStatus,
                java.math.BigDecimal subsistencePercent,
                String lastPayEvent,
                String allowanceFlags,
                String paymentFlag) {
            this(
                    employeeId,
                    locationId,
                    basic,
                    payPattern,
                    cityClass,
                    employmentStatus,
                    subsistencePercent,
                    lastPayEvent,
                    allowanceFlags,
                    paymentFlag,
                    true);
        }

        /** The member as the pay rules see it (one place for every caller of {@link PayRules}). */
        public PayRules.Member member() {
            // SRS-1-41: a Salary Stop is not computed; a HOLD event is Salary Withheld: computed,
            // its payment held until the release order (SALARY_RELEASE).
            boolean stopped = "SALARY_STOP".equals(lastPayEvent);
            String hold = "HOLD".equals(lastPayEvent) ? "SALARY_WITHHELD" : paymentFlag;
            return new PayRules.Member(
                    employeeId,
                    basic,
                    payPattern,
                    cityClass,
                    employmentStatus,
                    subsistencePercent,
                    stopped,
                    allowanceFlags == null || allowanceFlags.isBlank()
                            ? java.util.Set.of()
                            : java.util.Set.of(allowanceFlags.split(",")),
                    hold,
                    gradePay,
                    PayRules.hraSuppressed(accommodationType, accommodationSuppressed));
        }

        @Override
        public String itemKey() {
            return "employee:" + employeeId;
        }
    }

    /** The member's record in force on the period's first day; the latest start wins. */
    private static final String ACCOMMODATION_IN_FORCE =
            " FROM lease_accommodation_detail a WHERE a.employee_id = e.employee_id AND"
                    + " a.effective_from = (SELECT MAX(b.effective_from) FROM"
                    + " lease_accommodation_detail b WHERE b.employee_id = e.employee_id AND"
                    + " b.effective_from <= :periodStart AND (b.effective_to IS NULL OR"
                    + " b.effective_to >= :periodStart))";

    private static final String APPROVED_EVENT =
            "FROM employee_lifecycle_event x JOIN approval_queue q ON q.approval_id = x.approval_id"
                    + " AND q.status = 'APPROVED' WHERE x.employee_id = e.employee_id";

    static final String SELECT =
            "SELECT e.employee_id, e.location_id, e.current_basic, e.pay_pattern,"
                + " e.employment_status, CASE WHEN EXISTS (SELECT 1 FROM pay_scale_band_version sv"
                + " WHERE sv.band_id = e.band_id AND sv.effective_from <= :periodStart AND"
                + " (sv.effective_to IS NULL OR sv.effective_to >= :periodStart)) THEN 'Y' ELSE 'N'"
                + " END AS scale_in_force, COALESCE((SELECT m.city_class FROM"
                + " city_type_location_map m WHERE m.location_id = e.location_id   AND"
                + " m.effective_from = (SELECT MAX(m2.effective_from) FROM city_type_location_map"
                + " m2   WHERE m2.location_id = e.location_id AND m2.effective_from <="
                + " :periodStart)),   (SELECT l.city_class FROM org_location l WHERE l.location_id"
                + " = e.location_id)) AS city_class, (SELECT MAX(x.subsistence_percent) "
                    + APPROVED_EVENT
                    + " AND x.event_type = 'SUSPENSION'   AND x.event_date = (SELECT"
                    + " MAX(y.event_date) FROM employee_lifecycle_event y JOIN approval_queue r  "
                    + " ON r.approval_id = y.approval_id AND r.status = 'APPROVED' WHERE"
                    + " y.employee_id = e.employee_id   AND y.event_type = 'SUSPENSION')) AS"
                    + " subsistence_percent, (SELECT MAX(x.event_type) "
                    + APPROVED_EVENT
                    + " AND x.event_type IN ('SALARY_STOP','HOLD','SALARY_RELEASE')   AND"
                    + " x.event_date = (SELECT MAX(y.event_date) FROM employee_lifecycle_event y"
                    + " JOIN approval_queue r   ON r.approval_id = y.approval_id AND r.status ="
                    + " 'APPROVED' WHERE y.employee_id = e.employee_id   AND y.event_type IN"
                    + " ('SALARY_STOP','HOLD','SALARY_RELEASE') AND y.event_date <= :periodEnd)) AS"
                    + " last_pay_event, (SELECT LISTAGG(f.flag_code, ',') WITHIN GROUP (ORDER BY"
                    + " f.flag_code) FROM employee_eligibility_flag f WHERE f.employee_id ="
                    + " e.employee_id AND f.flag_value = 'Y' AND f.effective_from <= :periodStart"
                    + " AND (f.effective_to IS NULL OR f.effective_to >= :periodStart) AND NOT"
                    + " EXISTS (SELECT 1 FROM employee_eligibility_flag g WHERE g.employee_id ="
                    + " f.employee_id AND g.flag_code = f.flag_code AND g.effective_from >"
                    + " f.effective_from AND g.effective_from <= :periodStart)) AS"
                    + " allowance_flags, CASE WHEN e.stop_payment_flag = 'Y' THEN"
                    + " 'STOP_PAYMENT_FLAG' WHEN e.hold_flag = 'Y' THEN 'HOLD_FLAG' WHEN"
                    + " e.absconding_flag = 'Y' THEN 'ABSCONDING' END AS payment_flag, (SELECT"
                    + " g.grade_pay FROM org_grade g WHERE g.grade_id = e.grade_id) AS grade_pay,"
                    + " (SELECT a.accommodation_type"
                    + ACCOMMODATION_IN_FORCE
                    + ") AS accommodation_type, (SELECT a.hra_suppressed_flag"
                    + ACCOMMODATION_IN_FORCE
                    + ") AS hra_suppressed_flag";

    static final String FROM = "FROM employee e";

    /**
     * Eligible members of the partition's locations. A member whose salary for the month was paid
     * off-cycle (I-20) is excluded: an ACTIVE {@code off_cycle_payment} approved by the Checker
     * with {@code excluded_from_yymm} = this month (SRS-G-10: "exclude the disbursed amount from
     * that cycle"; "no role can override"). Pay no one twice. Staff only: {@code employee_type} is
     * the engine discriminator (FRS §2.1; SRS-15-11 "all four payroll engines"), and departmental,
     * DPS and NWNP labour are computed by their own engines (modules 18-22), never here.
     */
    static final String WHERE =
            "WHERE e.employee_type = 'STAFF' AND e.record_status = 'ACTIVE' AND e.employment_status"
                + " NOT IN ('SEPARATED', 'DISMISSED') AND e.location_id IN (:locations) AND NOT"
                + " EXISTS (SELECT 1 FROM off_cycle_payment o JOIN approval_queue oq ON"
                + " oq.approval_id = o.approval_id AND oq.status = 'APPROVED' WHERE o.employee_id ="
                + " e.employee_id AND o.record_status = 'ACTIVE' AND o.excluded_from_yymm = :yymm)";

    private MemberReader() {}

    public static JdbcPagingItemReader<MemberRow> create(
            String name,
            DataSource dataSource,
            Collection<Long> locations,
            LocalDate periodStart,
            int pageSize)
            throws Exception {
        // Oracle review F1: the paging reader builds its own template, so a partition of more
        // than 1,000 locations is split into IN lists Oracle takes here (ORA-01795).
        InLists.ExpandedMap where =
                InLists.expand(
                        WHERE,
                        Map.of(
                                "locations", locations,
                                "yymm", yymm(periodStart),
                                "periodStart", java.sql.Date.valueOf(periodStart),
                                "periodEnd",
                                        java.sql.Date.valueOf(
                                                periodStart.withDayOfMonth(
                                                        periodStart.lengthOfMonth()))));
        JdbcPagingItemReader<MemberRow> reader =
                new JdbcPagingItemReaderBuilder<MemberRow>()
                        .name(name)
                        .dataSource(dataSource)
                        .selectClause(SELECT)
                        .fromClause(FROM)
                        .whereClause(where.sql())
                        .parameterValues(where.params())
                        .sortKeys(Map.of("employee_id", Order.ASCENDING))
                        .pageSize(pageSize)
                        .fetchSize(pageSize)
                        .saveState(true)
                        .rowMapper(MemberReader::row)
                        .build();
        reader.afterPropertiesSet();
        return reader;
    }

    /**
     * One member as {@link #create} would read it (the I-20 off-cycle computation uses the same
     * inputs).
     */
    public static Optional<MemberRow> member(
            NamedParameterJdbcTemplate jdbc, long employeeId, LocalDate periodStart) {
        Map<String, Object> params = new HashMap<>();
        params.put("employeeId", employeeId);
        params.put("periodStart", java.sql.Date.valueOf(periodStart));
        params.put(
                "periodEnd",
                java.sql.Date.valueOf(periodStart.withDayOfMonth(periodStart.lengthOfMonth())));
        return jdbc
                .query(
                        SELECT + " " + FROM + " WHERE e.employee_id = :employeeId",
                        params,
                        MemberReader::row)
                .stream()
                .findFirst();
    }

    /**
     * One page of the eligible members of {@code locations} (the same eligibility as {@link
     * #create}) after {@code afterId}, in employee id order; {@code employeeIds}, when not null,
     * narrows it to those members (C-09 single member / custom list). Each list stays within the
     * 1000 an Oracle IN list takes: the caller chunks them.
     */
    public static List<MemberRow> page(
            NamedParameterJdbcTemplate jdbc,
            Collection<Long> locations,
            Collection<Long> employeeIds,
            LocalDate periodStart,
            long afterId,
            int size) {
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("locations", locations)
                        .addValue("yymm", yymm(periodStart))
                        .addValue("periodStart", java.sql.Date.valueOf(periodStart))
                        .addValue(
                                "periodEnd",
                                java.sql.Date.valueOf(
                                        periodStart.withDayOfMonth(periodStart.lengthOfMonth())))
                        .addValue("afterId", afterId)
                        .addValue("size", size);
        String members = "";
        if (employeeIds != null) {
            params.addValue("employeeIds", employeeIds);
            members = " AND e.employee_id IN (:employeeIds)";
        }
        return jdbc.query(
                SELECT
                        + " "
                        + FROM
                        + " "
                        + WHERE
                        + members
                        + " AND e.employee_id > :afterId ORDER BY e.employee_id FETCH FIRST :size"
                        + " ROWS ONLY",
                params,
                MemberReader::row);
    }

    private static MemberRow row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new MemberRow(
                rs.getLong("employee_id"),
                rs.getLong("location_id"),
                rs.getBigDecimal("current_basic"),
                rs.getString("pay_pattern"),
                rs.getString("city_class"),
                rs.getString("employment_status"),
                rs.getBigDecimal("subsistence_percent"),
                rs.getString("last_pay_event"),
                rs.getString("allowance_flags"),
                rs.getString("payment_flag"),
                "Y".equals(rs.getString("scale_in_force")),
                rs.getBigDecimal("grade_pay"),
                rs.getString("accommodation_type"),
                "Y".equals(rs.getString("hra_suppressed_flag")));
    }

    /** YYYYMM of a period start (the payroll_yymm convention). */
    public static String yymm(LocalDate periodStart) {
        return CycleKeys.yymm(java.time.YearMonth.from(periodStart));
    }

    /** The eligibility condition (parameters locations and yymm) over {@code employee e}. */
    public static String where() {
        return WHERE;
    }

    /**
     * Members per location, for the partitioner (same eligibility; parameters locations and yymm).
     */
    public static String countSql() {
        return "SELECT e.location_id, COUNT(*) FROM employee e "
                + WHERE
                + " GROUP BY e.location_id";
    }
}
