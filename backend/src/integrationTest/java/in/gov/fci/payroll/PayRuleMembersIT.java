package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.configuration.api.AllowanceConfiguration;
import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.payroll.batch.computation.MemberReader;
import in.gov.fci.payroll.payroll.business.PayRules;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SRS-1-3, SRS l.5409, l.5410, l.5412, SRS-11-17: the real read ({@link MemberReader}, real SQL)
 * and the rules give, for each demo member of V080_80, the earning lines the C-02 demo run holds,
 * so what the screen shows is what the engine computes. Also the accommodation record by date: the
 * record in force on the period's first day decides the whole month (no part-month rule, REF-1).
 */
class PayRuleMembersIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate MONTH = LocalDate.now(IST).withDayOfMonth(1);

    @Autowired private PayConfiguration configuration;
    @Autowired private NamedParameterJdbcTemplate named;
    @Autowired private TransactionTemplate transactions;

    private long employee(String eis) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, eis);
    }

    private PayRules.Outcome computed(String eis, LocalDate month) {
        return PayRules.compute(
                MemberReader.member(named, employee(eis), month).orElseThrow().member(),
                withNpa(configuration.staffConfiguration(month)));
    }

    /** The NPA ceiling stays unseeded on the demo (V079_24): a fixture here, not an FCI value. */
    private static PayConfiguration.Snapshot withNpa(PayConfiguration.Snapshot s) {
        return new PayConfiguration.Snapshot(
                s.asOf(),
                s.daPercent(),
                s.hraPercent(),
                s.ccaAmount(),
                s.ccaSeeded(),
                s.componentIds(),
                s.rateSetVersions(),
                s.versionSetHash(),
                new AllowanceConfiguration.Allowances(
                        Map.of("NPA", 951),
                        new BigDecimal("25"),
                        new BigDecimal("90000"),
                        List.of(),
                        "fixture"));
    }

    private Map<Integer, BigDecimal> seeded(String eis) {
        Map<Integer, BigDecimal> lines = new TreeMap<>();
        jdbc.query(
                "SELECT c.legacy_pay_code, c.amount FROM payroll_result_component c JOIN"
                        + " pay_component p ON p.component_id = c.component_id WHERE c.employee_id"
                        + " = ? AND p.component_type = 'EARNING'",
                rs -> {
                    lines.put(rs.getInt(1), rs.getBigDecimal(2).stripTrailingZeros());
                },
                employee(eis));
        return lines;
    }

    private static Map<Integer, BigDecimal> lines(PayRules.Outcome o) {
        Map<Integer, BigDecimal> lines = new TreeMap<>();
        o.lines().forEach(l -> lines.put(l.payCode(), l.amount().stripTrailingZeros()));
        return lines;
    }

    @Test
    void SRS_1_3_eachDemoMemberOfTheScreenIsWhatTheEngineComputes() {
        for (String eis :
                List.of("880701", "880702", "880703", "880704", "880705", "880706", "880707")) {
            assertThat(lines(computed(eis, MONTH))).as(eis).isEqualTo(seeded(eis));
        }
    }

    @Test
    void SRS_1_3_inQuartersOrOnLeaseNoHraWithNoRecordHraPaid() {
        assertThat(lines(computed("880701", MONTH))).doesNotContainKey(PayRules.HRA);
        assertThat(lines(computed("880702", MONTH))).doesNotContainKey(PayRules.HRA);
        assertThat(lines(computed("880703", MONTH)).get(PayRules.HRA))
                .isEqualByComparingTo("15600");
        // A third-party lease flagged not suppressed: HRA stands (no source says adjust how).
        assertThat(lines(computed("880707", MONTH)).get(PayRules.HRA))
                .isEqualByComparingTo("13500");
    }

    @Test
    void SRS_1_2_gradePayOfTheGradeReachesTheDaBaseFromTheDatabase() {
        PayRules.Line da =
                computed("880704", MONTH).lines().stream()
                        .filter(l -> l.payCode() == PayRules.DA)
                        .findFirst()
                        .orElseThrow();
        assertThat(da.basis()).isEqualByComparingTo("54600");
        assertThat(da.amount()).isEqualByComparingTo("27300");
    }

    /** Before its record starts, the same member is paid HRA: the record is read by date. */
    @Test
    void SRS_1_3_aRecordCountsFromItsStart() {
        LocalDate start =
                jdbc.queryForObject(
                                "SELECT MIN(effective_from) FROM lease_accommodation_detail WHERE"
                                        + " employee_id = ?",
                                Date.class,
                                employee("880701"))
                        .toLocalDate();
        assertThat(
                        MemberReader.member(named, employee("880701"), start.minusMonths(1))
                                .orElseThrow()
                                .member()
                                .hraSuppressed())
                .isFalse();
        assertThat(
                        MemberReader.member(named, employee("880701"), start)
                                .orElseThrow()
                                .member()
                                .hraSuppressed())
                .isTrue();
    }

    /**
     * A change within the month (REF-1, no part-month rule): quarters vacated on the 15th of the
     * month still hold the month; the month after is paid HRA. Allotted on the 15th, HRA stands for
     * the month and stops the next. Rolled back: the demo rows stay as seeded.
     */
    @Test
    void SRS_1_3_aChangeWithinTheMonthCountsFromTheNextMonth() {
        long vacated = employee("880703");
        LocalDate month = LocalDate.of(2030, 3, 1);
        transactions.executeWithoutResult(
                status -> {
                    status.setRollbackOnly();
                    jdbc.update(
                            "INSERT INTO lease_accommodation_detail (employee_id,"
                                    + " accommodation_type, source_code, hra_suppressed_flag,"
                                    + " effective_from, effective_to) VALUES (?,"
                                    + " 'DEPARTMENTAL_QUARTERS', 'HRMS', 'Y', ?, ?)",
                            vacated,
                            Date.valueOf(month.minusYears(1)),
                            Date.valueOf(month.withDayOfMonth(15)));
                    assertThat(
                                    MemberReader.member(named, vacated, month)
                                            .orElseThrow()
                                            .member()
                                            .hraSuppressed())
                            .as("the month of the change")
                            .isTrue();
                    assertThat(
                                    MemberReader.member(named, vacated, month.plusMonths(1))
                                            .orElseThrow()
                                            .member()
                                            .hraSuppressed())
                            .as("the month after")
                            .isFalse();
                    jdbc.update(
                            "INSERT INTO lease_accommodation_detail (employee_id,"
                                    + " accommodation_type, source_code, hra_suppressed_flag,"
                                    + " effective_from) VALUES (?, 'SPOUSE', 'HRMS', 'N', ?)",
                            vacated,
                            Date.valueOf(month.plusMonths(2).withDayOfMonth(15)));
                    assertThat(
                                    MemberReader.member(named, vacated, month.plusMonths(2))
                                            .orElseThrow()
                                            .member()
                                            .hraSuppressed())
                            .as("allotted on the 15th: this month")
                            .isFalse();
                    assertThat(
                                    MemberReader.member(named, vacated, month.plusMonths(3))
                                            .orElseThrow()
                                            .member()
                                            .hraSuppressed())
                            .as("allotted on the 15th: the next month, spouse flagged N")
                            .isTrue();
                });
    }

    /** Two records in force: the later start wins (the unique key is member and start). */
    @Test
    void SRS_1_3_theLatestRecordInForceDecides() {
        long member = employee("880703");
        LocalDate month = LocalDate.of(2030, 3, 1);
        transactions.executeWithoutResult(
                status -> {
                    status.setRollbackOnly();
                    jdbc.update(
                            "INSERT INTO lease_accommodation_detail (employee_id,"
                                    + " accommodation_type, source_code, hra_suppressed_flag,"
                                    + " effective_from) VALUES (?, 'THIRD_PARTY', 'HRMS', 'Y', ?),"
                                    + " (?, 'THIRD_PARTY', 'HRMS', 'N', ?)",
                            member,
                            Date.valueOf(month.minusYears(2)),
                            member,
                            Date.valueOf(month.minusYears(1)));
                    assertThat(
                                    MemberReader.member(named, member, month)
                                            .orElseThrow()
                                            .member()
                                            .hraSuppressed())
                            .isFalse();
                });
    }
}
