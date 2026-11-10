package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.cpf.business.InterestRateRules.FinYear;
import in.gov.fci.payroll.cpf.business.InterestRunRules;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader.MemberStatus;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The covered ledgers of a zone's year for C-12 (SRS-6-45), counted and paged in the database.
 *
 * <p>Deep profile 2026-10-27 (VOLUME set, 7,600 ledgers in the North zone): the summary and the
 * not-calculated report loaded every ledger as an entity and every member's identity in chunks of
 * 1,000 to answer two counts and one page (C-12 runs p99 539 ms, report 960 ms, 43 database round
 * trips a request). Here one grouped query gives the counts and one paged query the report; the
 * reason a member is not calculated is still {@link InterestRunRules#notCalculatedReason}, decided
 * per CPF-code group (1 = the member has a CPF code, 0 = none), so the answers are the same. The
 * SQL is constant; every value is a bind parameter.
 */
@Component
class InterestLedgers {

    private static final String HAS_CODE =
            "CASE WHEN LENGTH(TRIM(e.cpf_code)) > 0 THEN 1 ELSE 0 END";
    private static final String COVERED =
            " FROM employee_pf_header h LEFT JOIN employee e ON e.employee_id = h.employee_id"
                    + " WHERE h.fin_year = :finYear AND h.zone_id = :zone AND h.fund_type = :fund"
                    + " AND h.member_status IN (:statuses)";
    private static final String COUNTS =
            "SELECT " + HAS_CODE + " AS has_code, COUNT(*)" + COVERED + " GROUP BY " + HAS_CODE;
    // The session's data scope (SRS-G-29): C-12 VIEW reaches offices below the zone (SRS 14.4,
    // OD-125), and a zone's ledger list is no more than each office's own members.
    private static final String NOT_CALCULATED =
            COVERED + " AND " + HAS_CODE + " IN (:groups) AND e.location_id IN (:offices)";
    private static final String NOT_CALCULATED_COUNT = "SELECT COUNT(*)" + NOT_CALCULATED;
    private static final String NOT_CALCULATED_PAGE =
            "SELECT e.emp_num, e.first_name, e.middle_name, e.last_name, h.member_status,"
                    + " h.interest_end_date, "
                    + HAS_CODE
                    + NOT_CALCULATED
                    + " ORDER BY h.employee_id, h.pf_header_id OFFSET :offset ROWS FETCH NEXT :size"
                    + " ROWS ONLY";

    /** Members covered and how many of them are calculated. */
    record Counts(long members, long calculated) {}

    private final NamedParameterJdbcTemplate jdbc;

    InterestLedgers(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    Counts counts(String finYear, Long zoneId, Set<MemberStatus> covered, String fund) {
        List<Integer> notCalculated = notCalculatedGroups();
        long[] totals = new long[2];
        jdbc.query(
                COUNTS,
                params(finYear, zoneId, fund, covered),
                rs -> {
                    long n = rs.getLong(2);
                    totals[0] += n;
                    if (!notCalculated.contains(rs.getInt(1))) {
                        totals[1] += n;
                    }
                });
        return new Counts(totals[0], totals[1]);
    }

    /** One page of the members not calculated, in ledger order (employee, then ledger). */
    @Transactional(readOnly = true)
    Paged<InterestRunService.NotCalculated> notCalculated(
            String finYear,
            Long zoneId,
            Set<MemberStatus> covered,
            String fund,
            FinYear year,
            Collection<Long> offices,
            PageSpec page) {
        List<Integer> groups = notCalculatedGroups();
        if (groups.isEmpty() || offices.isEmpty()) {
            return new Paged<>(List.of(), page.page(), page.size(), 0);
        }
        MapSqlParameterSource params =
                params(finYear, zoneId, fund, covered)
                        .addValue("groups", groups)
                        .addValue("offices", offices);
        Long total = jdbc.queryForObject(NOT_CALCULATED_COUNT, params, Long.class);
        params.addValue("offset", page.page() * page.size()).addValue("size", page.size());
        List<InterestRunService.NotCalculated> rows =
                jdbc.query(
                        NOT_CALCULATED_PAGE,
                        params,
                        (rs, i) -> {
                            Date end = rs.getDate(6);
                            LocalDate interestEnd = end == null ? null : end.toLocalDate();
                            return new InterestRunService.NotCalculated(
                                    rs.getString(1),
                                    name(rs.getString(2), rs.getString(3), rs.getString(4)),
                                    rs.getString(5),
                                    InterestRunRules.notCalculatedReason(rs.getInt(7) == 1),
                                    interestEnd,
                                    InterestRunRules.stoppedIn(year, interestEnd));
                        });
        return new Paged<>(rows, page.page(), page.size(), RowCounts.of(total));
    }

    /**
     * The CPF-code groups (1 = has a code, 0 = none) whose members the rules leave uncalculated.
     */
    static List<Integer> notCalculatedGroups() {
        List<Integer> out = new ArrayList<>();
        for (int group = 0; group <= 1; group++) {
            if (InterestRunRules.notCalculatedReason(group == 1) != null) {
                out.add(group);
            }
        }
        return out;
    }

    /**
     * As {@code Employee.fullName()}: the non-blank parts joined by a space; null without a member.
     */
    static String name(String first, String middle, String last) {
        if (first == null && middle == null && last == null) {
            return null;
        }
        return Stream.of(first, middle, last)
                .filter(p -> p != null && !p.isBlank())
                .collect(Collectors.joining(" "));
    }

    private static MapSqlParameterSource params(
            String finYear, Long zoneId, String fund, Set<MemberStatus> covered) {
        return new MapSqlParameterSource()
                .addValue("finYear", finYear)
                .addValue("zone", zoneId)
                .addValue("fund", fund)
                .addValue("statuses", covered.stream().map(MemberStatus::name).sorted().toList());
    }
}
