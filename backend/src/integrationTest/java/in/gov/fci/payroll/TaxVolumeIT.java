package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.tax.orchestration.Form16Service;
import in.gov.fci.payroll.tax.orchestration.ReturnService;
import jakarta.persistence.EntityManagerFactory;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Volume profile of the Module 08 batch operations on the 50,000-employee VOLUME data set
 * (docs/perf/VOLUME_DATA.md; owner 2026-10-25: volume and PT runs on 50K employees, the normal set
 * otherwise; SRS-NF-6). The year-end of the whole country, paying unit after paying unit: Form 16
 * Part B, the Q4 Form 24Q and its validation with the year-end reconciliation, then the registers.
 * Each step records its time, SQL statements and heap to docs/perf/module-08-volume.json. Runs only
 * with FCI_PERF=1; never part of the check.
 */
@EnabledIfEnvironmentVariable(named = "FCI_PERF", matches = "1")
@ActiveProfiles("volume")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class TaxVolumeIT extends ApiTestSupport {

    private static final String YEAR = TaxYearFixtures.yearBack(39);

    @Autowired private EntityManagerFactory emf;
    @Autowired private OrgDirectory org;
    @Autowired private Form16Service form16;
    @Autowired private ReturnService returns;

    private final Map<String, Map<String, Object>> steps = new LinkedHashMap<>();

    private <T> T measure(String step, long rows, Callable<T> call) throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        System.gc();
        long heapBefore = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        stats.clear();
        long t0 = System.nanoTime();
        T result = call.call();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long heapAfter = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows);
        m.put("ms", ms);
        m.put("msPerRow", rows == 0 ? 0 : Math.round(ms * 100.0 / rows) / 100.0);
        m.put("statements", stats.getPrepareStatementCount());
        m.put(
                "statementsPerRow",
                rows == 0
                        ? 0
                        : Math.round(stats.getPrepareStatementCount() * 100.0 / rows) / 100.0);
        m.put("heapGrowthMb", (heapAfter - heapBefore) / (1024 * 1024));
        steps.put(step, m);
        System.out.println("TAX VOLUME " + step + ": " + m);
        return result;
    }

    /** The paying units of the volume set with their members (cycle units holding employees). */
    private List<Long> units() {
        return jdbc.queryForList(
                "SELECT DISTINCT l.location_id FROM org_location l WHERE l.is_cycle_unit = 'Y' AND"
                        + " l.location_type IN ('DO', 'RO') ORDER BY l.location_id",
                Long.class);
    }

    /**
     * The Payroll Maker of a unit, as the session of its desk would be: one DIVISION group covering
     * the unit's subtree with the C-10 and C-11 grants of the role (V039_11), so the real policy
     * runs.
     */
    private AccessContext makerAt(Long unit) {
        Set<Long> scope = Set.copyOf(org.subtree(unit));
        Long role =
                jdbc.queryForObject(
                        "SELECT role_id FROM app_role WHERE role_code = 'PAYROLL_MAKER'",
                        Long.class);
        in.gov.fci.payroll.platform.security.ActiveGroup group =
                new in.gov.fci.payroll.platform.security.ActiveGroup(
                        -unit,
                        "AG-VOL-PERF",
                        "Volume run",
                        role,
                        "PAYROLL_MAKER",
                        "Payroll Maker",
                        in.gov.fci.payroll.platform.security.ScopeType.DIVISION,
                        false,
                        scope,
                        Set.of(),
                        in.gov.fci.payroll.platform.security.AccessMode.FULL);
        Set<String> grants =
                Set.of(
                        AccessContext.key("C-10", "VIEW"),
                        AccessContext.key("C-10", "CREATE"),
                        AccessContext.key("C-11", "VIEW"),
                        AccessContext.key("C-11", "CREATE"),
                        AccessContext.key("C-11", "SUBMIT"));
        return new AccessContext(
                2L,
                "vol.perf",
                "Volume run",
                "perf",
                "PAYROLL_MAKER",
                unit,
                null,
                List.of(group),
                Map.of(-unit, grants),
                Set.of(),
                Set.of(),
                scope);
    }

    private void definitions() throws Exception {
        for (String key : List.of("FORM16_PART_B", "FORM_24Q")) {
            Integer held =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM report_definition_version v JOIN"
                                + " report_definition d ON d.report_def_id = v.report_def_id WHERE"
                                + " d.report_key = ?",
                            Integer.class,
                            key);
            if (held > 0) {
                continue;
            }
            Map<String, Object> v = new HashMap<>();
            v.put("effectiveFrom", TaxYearFixtures.april(YEAR).toString());
            v.put("layoutFormat", "PDF");
            v.put("periodLockRequired", true);
            v.put(
                    "fields",
                    List.of(Map.of("label", "Form", "binding", "LITERAL", "reference", key)));
            v.put("reason", "volume");
            MvcResult p =
                    mvc.perform(
                                    userPost(
                                            login(
                                                    "su.hq@fci-demo.test",
                                                    "RULE_ADMINISTRATOR",
                                                    "HQ001"),
                                            "/v1/tax/output-definitions/" + key + "/versions",
                                            v))
                            .andReturn();
            decide(
                    login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001"),
                    body(p).path("approvalId").asLong(),
                    "APPROVE",
                    "volume",
                    null);
        }
    }

    @Test
    void SRS_NF_6_theYearEndOfTheWholeCountryScalesWithItsMembers() throws Exception {
        long staff =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM employee WHERE emp_num LIKE 'V______'", Long.class);
        long labour =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM employee WHERE emp_num LIKE 'L______'", Long.class);
        assertThat(staff + labour).as("the volume data set (profile volume)").isEqualTo(50_000);
        definitions();
        TaxYearFixtures.rates(jdbc, YEAR);
        List<Long> units = units();
        measure(
                "lock the year for every paying unit",
                units.size(),
                () -> {
                    units.forEach(
                            u ->
                                    TaxYearFixtures.locked(
                                            jdbc, u, TaxYearFixtures.months(YEAR), YEAR));
                    return null;
                });
        measure(
                "approve a regime for every staff member",
                staff,
                () ->
                        jdbc.update(
                                "INSERT INTO it_regime_detail (employee_id, fin_year, regime_code,"
                                    + " elected_via, locked_flag, verify_status) SELECT"
                                    + " employee_id, ?, CASE WHEN MOD(employee_id, 3) = 0 THEN 'N'"
                                    + " ELSE 'O' END, 'ESS', 'Y', 'APPROVED' FROM employee WHERE"
                                    + " emp_num LIKE 'V______'",
                                YEAR));
        // The engines' year of every staff member: actual tax 1,79,400 on the old slabs, deducted
        // in
        // full (12 x 14,950), so the years reconcile; a member of the new regime differs
        // (SRS-7-19).
        List<Long> ids =
                jdbc.queryForList(
                        "SELECT employee_id FROM employee WHERE emp_num LIKE 'V______'",
                        Long.class);
        for (Long id : ids) {
            ItTaxPorts.YEARS.put(id + "|" + YEAR, ItTaxPorts.year(YEAR, "100000", "14950", true));
        }
        try {
            long[] generated = new long[1];
            measure(
                    "C-10 Part B, every paying unit",
                    staff + labour,
                    () -> {
                        for (Long u : units) {
                            generated[0] += form16.generate(makerAt(u), YEAR).generated();
                        }
                        return null;
                    });
            assertThat(generated[0]).isPositive();
            measure(
                    "C-10 Part B again (regenerated), every paying unit",
                    staff + labour,
                    () -> {
                        for (Long u : units) {
                            form16.generate(makerAt(u), YEAR);
                        }
                        return null;
                    });
            Long biggest =
                    jdbc.queryForObject(
                            "SELECT location_id FROM org_location WHERE location_code = 'DV-PB02'",
                            Long.class);
            measure(
                    "C-10 register, 100 rows",
                    100,
                    () -> form16.register(makerAt(biggest), YEAR, null, new PageSpec(0, 100)));
            Map<Long, Long> q4 = new HashMap<>();
            measure(
                    "C-11 Q4 return, every paying unit",
                    staff + labour,
                    () -> {
                        for (Long u : units) {
                            q4.put(u, returns.generate(makerAt(u), YEAR, "Q4", null).id());
                        }
                        return null;
                    });
            measure(
                    "C-11 Q4 validation with the year-end reconciliation, every paying unit",
                    staff + labour,
                    () -> {
                        q4.forEach((u, id) -> returns.validate(makerAt(u), id));
                        return null;
                    });
            measure(
                    "C-11 Q4 regenerated in place, every paying unit",
                    staff + labour,
                    () -> {
                        for (Long u : units) {
                            returns.generate(makerAt(u), YEAR, "Q4", null);
                        }
                        return null;
                    });
            measure(
                    "C-11 deductees, 100 rows",
                    100,
                    () ->
                            returns.deductees(
                                    makerAt(biggest), q4.get(biggest), new PageSpec(0, 100)));
        } finally {
            ItTaxPorts.YEARS.clear();
            Path out = Path.of("..", "docs", "perf", "module-08-volume.json");
            Files.createDirectories(out.getParent());
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("dataSet", "volume (docs/perf/VOLUME_DATA.md)");
            report.put("employees", staff + labour);
            report.put("staff", staff);
            report.put("payingUnits", units.size());
            report.put("finYear", YEAR);
            report.put(
                    "database",
                    "H2 in memory, integration test JVM (indicative; Oracle 19c is the target)");
            report.put("steps", steps);
            Files.writeString(
                    out, json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        }
    }
}
