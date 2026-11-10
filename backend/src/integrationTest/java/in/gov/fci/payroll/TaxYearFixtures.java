package in.gov.fci.payroll;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.tax.business.DeclarationRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The financial year a Module 08 integration test computes in: a year in the past (dates from the
 * current date, owner 2026-10-25), with its own test rates, closed at the year's end so that no
 * rate is ever in force today (TaxConfigurationIT; OQ-4: the system seeds none), and its payroll
 * months locked for a paying unit. Each test class takes a year of its own, so classes never share
 * a version, a cycle or an M-17 period. Every insert is made once, so a rerun finds its rows.
 */
final class TaxYearFixtures {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private TaxYearFixtures() {}

    /** The financial year {@code yearsBack} years before today's. */
    static String yearBack(int yearsBack) {
        return DeclarationRules.finYearOf(LocalDate.now(INDIA).minusYears(yearsBack));
    }

    static LocalDate april(String finYear) {
        return LocalDate.of(Integer.parseInt(finYear.substring(0, 4)), 4, 1);
    }

    static LocalDate march(String finYear) {
        return april(finYear).plusYears(1).minusDays(1);
    }

    /** The months of a year as payroll_yymm, April first. */
    static List<String> months(String finYear) {
        LocalDate a = april(finYear);
        return java.util.stream.IntStream.range(0, 12)
                .mapToObj(k -> a.plusMonths(k))
                .map(d -> String.format("%04d%02d", d.getYear(), d.getMonthValue()))
                .toList();
    }

    /**
     * Test rates of the year (fixtures, not statutory values): progressive slabs of both regimes,
     * surcharge above 50 lakh, cess 4 %, the no-PAN rate 20 %.
     */
    static void rates(JdbcTemplate jdbc, String finYear) {
        slab(
                jdbc,
                "TAX.SLAB.OLD",
                finYear,
                new String[][] {
                    {"0", "250000", "0"},
                    {"250000", "500000", "5"},
                    {"500000", "1000000", "20"},
                    {"1000000", null, "30"}
                });
        slab(
                jdbc,
                "TAX.SLAB.NEW",
                finYear,
                new String[][] {
                    {"0", "300000", "0"}, {"300000", "700000", "5"}, {"700000", null, "10"}
                });
        slab(
                jdbc,
                "TAX.SURCHARGE",
                finYear,
                new String[][] {{"0", "5000000", "0"}, {"5000000", null, "10"}});
        scalar(jdbc, "TAX.CESS", finYear, "4");
        scalar(jdbc, "TAX.NOPAN.RATE", finYear, "20");
    }

    /** Every month of the year LOCKED for the cycle unit (payroll_cycle_instance). */
    static void locked(JdbcTemplate jdbc, Long unitId, List<String> months, String finYear) {
        for (String m : months) {
            Integer held =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                                    + " org_unit_id = ?",
                            Integer.class,
                            m,
                            unitId);
            if (held == 0) {
                jdbc.update(
                        "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id,"
                            + " org_unit_level, fin_year, cycle_status, trigger_type, initiated_by)"
                            + " SELECT ?, location_id, location_type, ?, 'LOCKED', 'ON_DEMAND',"
                            + " 'it.fixture' FROM org_location WHERE location_id = ?",
                        m,
                        finYear,
                        unitId);
            }
        }
    }

    /** An approved, frozen regime of the member for the year (O or N). */
    static void regime(JdbcTemplate jdbc, Long employeeId, String finYear, String code) {
        Integer held =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM it_regime_detail WHERE employee_id = ? AND fin_year ="
                                + " ?",
                        Integer.class,
                        employeeId,
                        finYear);
        if (held == 0) {
            jdbc.update(
                    "INSERT INTO it_regime_detail (employee_id, fin_year, regime_code, elected_via,"
                        + " locked_flag, verify_status) VALUES (?, ?, ?, 'ESS', 'Y', 'APPROVED')",
                    employeeId,
                    finYear,
                    code);
        }
    }

    /** The demo member with this EIS (V039_9 and V039_11_9). */
    static Long member(JdbcTemplate jdbc, String eis) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, eis);
    }

    /** The paying unit (cycle unit) of DH001, as the services resolve it (OD-11). */
    static Long delhiUnit(JdbcTemplate jdbc, OrgDirectory org) {
        Long dh001 =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'DH001'",
                        Long.class);
        return org.cycleUnitOf(dh001).orElseThrow().getId();
    }

    private static void slab(JdbcTemplate jdbc, String key, String finYear, String[][] bands) {
        Long version = version(jdbc, key, finYear, null);
        if (version == null) {
            return;
        }
        for (int i = 0; i < bands.length; i++) {
            jdbc.update(
                    "INSERT INTO rule_slab_band (rule_version_id, band_order, range_from, range_to,"
                            + " rate_or_amount) VALUES (?, ?, ?, ?, ?)",
                    version,
                    i + 1,
                    new BigDecimal(bands[i][0]),
                    bands[i][1] == null ? null : new BigDecimal(bands[i][1]),
                    new BigDecimal(bands[i][2]));
            ApiTestSupport.rulesChanged(jdbc);
        }
    }

    private static void scalar(JdbcTemplate jdbc, String key, String finYear, String value) {
        version(jdbc, key, finYear, new BigDecimal(value));
    }

    /** The year's approved version of a rule; null when the year already has it. */
    private static Long version(JdbcTemplate jdbc, String key, String finYear, BigDecimal scalar) {
        Integer held =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rule_version v JOIN rule_definition d ON d.rule_id ="
                                + " v.rule_id WHERE d.rule_key = ? AND v.effective_from = ?",
                        Integer.class,
                        key,
                        april(finYear));
        if (held > 0) {
            return null;
        }
        jdbc.update(
                "INSERT INTO rule_version (rule_id, version_no, effective_from, effective_to,"
                        + " scalar_value, approval_state, is_blocked, created_by) SELECT d.rule_id,"
                        + " (SELECT COALESCE(MAX(v.version_no), 0) + 1 FROM rule_version v WHERE"
                        + " v.rule_id = d.rule_id), ?, ?, ?, 'APPROVED', 'N', 'it' FROM"
                        + " rule_definition d WHERE d.rule_key = ?",
                april(finYear),
                march(finYear),
                scalar,
                key);
        ApiTestSupport.rulesChanged(jdbc);
        return jdbc.queryForObject(
                "SELECT v.rule_version_id FROM rule_version v JOIN rule_definition d ON d.rule_id ="
                        + " v.rule_id WHERE d.rule_key = ? AND v.effective_from = ?",
                Long.class,
                key,
                april(finYear));
    }
}
