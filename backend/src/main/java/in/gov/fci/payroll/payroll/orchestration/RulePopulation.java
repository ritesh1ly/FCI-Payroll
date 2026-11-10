package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.configuration.api.SimulationPopulation;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.batch.computation.MemberReader;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sample population of an M-08 rule simulation (plan 06 phase 06e; SRS-19-4), read with the
 * member eligibility of a payroll run ({@link MemberReader}). The members here are the staff of the
 * employee master: the labour payroll types have no members until their engines exist (modules 21,
 * 22), so their population is empty.
 */
@Component
public class RulePopulation implements SimulationPopulation {

    private static final String STAFF = "STAFF";

    private final NamedParameterJdbcTemplate jdbc;
    private final OrgDirectory org;

    public RulePopulation(NamedParameterJdbcTemplate jdbc, OrgDirectory org) {
        this.jdbc = jdbc;
        this.org = org;
    }

    @Override
    @Transactional(readOnly = true)
    public Sample sample(LocalDate day, String payrollType, Long officeId, int limit) {
        if (payrollType != null && !STAFF.equals(payrollType)) {
            return new Sample(0, List.of());
        }
        String yymm = MemberReader.yymm(day.withDayOfMonth(1));
        List<Long> offices =
                officeId == null ? null : org.subtree(officeId).stream().sorted().toList();
        long total = 0;
        List<Member> members = new ArrayList<>();
        for (List<Long> chunk : chunks(offices)) {
            MapSqlParameterSource params =
                    new MapSqlParameterSource().addValue("yymm", yymm).addValue("locations", chunk);
            String where = where(chunk == null);
            Long n =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM employee e " + where, params, Long.class);
            total += RowCounts.of(n);
            int room = limit - members.size();
            if (room > 0) {
                params.addValue("room", room);
                members.addAll(
                        jdbc.query(
                                "SELECT e.employee_id, e.location_id FROM employee e "
                                        + where
                                        + " ORDER BY e.employee_id FETCH FIRST :room ROWS ONLY",
                                params,
                                (rs, i) -> new Member(rs.getLong(1), STAFF, rs.getLong(2))));
            }
        }
        return new Sample(total, members);
    }

    /** The run's eligibility; every office when {@code anyOffice}. */
    private static String where(boolean anyOffice) {
        String where = MemberReader.where();
        return anyOffice ? where.replace("e.location_id IN (:locations)", "1 = 1") : where;
    }

    /** At most 1000 offices per IN list (Oracle); a single null chunk for every office. */
    static List<List<Long>> chunks(List<Long> offices) {
        if (offices == null) {
            List<List<Long>> all = new ArrayList<>();
            all.add(null);
            return all;
        }
        List<List<Long>> out = new ArrayList<>();
        for (int i = 0; i < offices.size(); i += 1000) {
            out.add(offices.subList(i, Math.min(offices.size(), i + 1000)));
        }
        return out.isEmpty() ? List.of(List.of(-1L)) : out;
    }
}
