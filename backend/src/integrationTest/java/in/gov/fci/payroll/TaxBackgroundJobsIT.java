package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.business.JobParameterCodec;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The Module 08 statutory outputs as background jobs (SRS-NF-2, SRS-NF-7; C-10, C-11 through C-13):
 * TAX_FORM16_PART_B and TAX_24Q_RETURN over the Delhi paying unit, partitioned by its locations, on
 * the per-member logic of the screens. The run is restartable from its last chunk, a return stays
 * DRAFT until its last member is written, and a run refused at its opening checks again when
 * restarted. The batch profile on a private in-memory database, with the IT stand-ins of the
 * payroll engines (ItTaxPorts).
 */
@SpringBootTest(classes = FciPayrollApplication.class)
@ActiveProfiles({"local", "demo", "batch", "it"})
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:taxjobs-${random.uuid};MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.flyway.enabled=true",
            "fci.batch.poller.enabled=false",
            "fci.common.management-port=0",
            "fci.batch.partition.grid-size=3",
            "fci.batch.chunk.size=5",
            "fci.batch.chunk.page-size=5"
        })
class TaxBackgroundJobsIT {

    private static final String YEAR = TaxYearFixtures.yearBack(30);
    private static final String LATER = TaxYearFixtures.yearBack(29);
    private static final String MAKER = "tax.maker.delhi@fci-demo.test";

    @Autowired private JobOperator operator;
    @Autowired private JobRepositories.JobRequests requests;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrgDirectory org;
    @Autowired private Clock clock;

    @Autowired
    @Qualifier("taxForm16PartBJob")
    private Job partBJob;

    @Autowired
    @Qualifier("tax24QReturnJob")
    private Job returnJob;

    @AfterEach
    void clear() {
        ItTaxPorts.YEARS.clear();
        ItTaxPorts.FAILING.clear();
    }

    private Long unit() {
        return TaxYearFixtures.delhiUnit(jdbc, org);
    }

    /** An approved version of {@code key} from the year's April, without an end. */
    private void definition(String key) {
        Integer held =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM report_definition_version v JOIN report_definition d"
                                + " ON d.report_def_id = v.report_def_id WHERE d.report_key = ?",
                        Integer.class,
                        key);
        if (held == 0) {
            jdbc.update(
                    "INSERT INTO report_definition_version (report_def_id, version_no,"
                            + " effective_from, layout_format, period_lock_required,"
                            + " output_store_mode, approval_state, is_blocked, created_by,"
                            + " created_at) SELECT report_def_id, 1, ?, 'PDF', 'Y', 'STORE',"
                            + " 'APPROVED', 'N', 'it.fixture', CURRENT_TIMESTAMP FROM"
                            + " report_definition WHERE report_key = ?",
                    java.sql.Date.valueOf(TaxYearFixtures.april(YEAR)),
                    key);
        }
    }

    /**
     * The year of Form16IT: 870001 and 870003 had the whole actual tax deducted (12 x 14,950 and 12
     * x 20,000 at the no-PAN rate); 870005 only 12 x 8,000, so its year does not reconcile.
     */
    private void finalYear() {
        definition("FORM16_PART_B");
        definition("FORM_24Q");
        TaxYearFixtures.rates(jdbc, YEAR);
        TaxYearFixtures.locked(jdbc, unit(), TaxYearFixtures.months(YEAR), YEAR);
        Map.of("870001", "14950", "870003", "20000", "870005", "8000")
                .forEach(
                        (eis, monthly) -> {
                            Long id = TaxYearFixtures.member(jdbc, eis);
                            TaxYearFixtures.regime(jdbc, id, YEAR, "O");
                            ItTaxPorts.YEARS.put(
                                    id + "|" + YEAR,
                                    ItTaxPorts.year(YEAR, "100000", monthly, true));
                        });
    }

    /** A request as the C-13 API queues it after the Checker's approval, PICKED by this node. */
    private BatchJobRequest picked(JobType type, Map<String, String> params) {
        BatchJobRequest request =
                BatchJobRequest.submit(
                        type,
                        JobParameterCodec.toJson(params),
                        JobParameterCodec.hash(type.name(), params),
                        5,
                        BatchJobRequest.Source.UI,
                        null,
                        MAKER,
                        unit(),
                        null,
                        clock.instant());
        request = requests.save(request);
        request.pick("test-node", clock.instant());
        return requests.save(request);
    }

    private JobExecution run(Job job, BatchJobRequest request) throws Exception {
        return operator.start(job, JobLaunchService.parameters(request));
    }

    /** RESTART as the control processor applies it: PICKED again, the same parameters. */
    private JobExecution restart(Job job, BatchJobRequest request) throws Exception {
        BatchJobRequest failed = requests.findById(request.getId()).orElseThrow();
        failed.pick("test-node", clock.instant());
        return run(job, requests.save(failed));
    }

    private Map<String, String> quarter(String finYear) {
        return Map.of("locationId", unit().toString(), "finYear", finYear, "quarter", "Q4");
    }

    private Map<String, Object> theReturn(String finYear) {
        return jdbc.queryForMap(
                "SELECT return_id, return_status, deductee_count, total_amount FROM"
                    + " statutory_return WHERE org_unit_id = ? AND fin_year = ? AND period_code ="
                    + " 'Q4' AND is_correction = 'N'",
                unit(),
                finYear);
    }

    private long rows(Object returnId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM statutory_return_deductee WHERE return_id = ?",
                Long.class,
                returnId);
    }

    private static long workers(
            JobExecution e, java.util.function.ToLongFunction<StepExecution> f) {
        return e.getStepExecutions().stream()
                .filter(s -> s.getStepName().contains(":partition"))
                .mapToLong(f)
                .sum();
    }

    @Test
    void SRS_NF_7_partBOfTheWholeUnitRunsInTheBackgroundAsOnTheScreen() throws Exception {
        finalYear();
        BatchJobRequest request =
                picked(
                        JobType.TAX_FORM16_PART_B,
                        Map.of("locationId", unit().toString(), "finYear", YEAR));

        JobExecution execution = run(partBJob, request);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(workers(execution, StepExecution::getWriteCount)).isEqualTo(2);
        for (String eis : new String[] {"870001", "870003"}) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM form16 WHERE employee_id = ?"
                                            + " AND fin_year = ?",
                                    Integer.class,
                                    TaxYearFixtures.member(jdbc, eis),
                                    YEAR))
                    .as(eis)
                    .isEqualTo(1);
        }
        Long short5 = TaxYearFixtures.member(jdbc, "870005");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM form16 WHERE employee_id = ? AND"
                                        + " fin_year = ?",
                                Integer.class,
                                short5,
                                YEAR))
                .as("SRS-7-19: a year that does not reconcile is not certified")
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payroll_exception WHERE employee_id = ? AND"
                                        + " exception_code = 'TAX_YEAR_END_MISMATCH'",
                                Integer.class,
                                short5))
                .as("raised on X-05 by the run")
                .isPositive();
        String code = org.location(unit()).orElseThrow().getCode();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_key = ? AND"
                                        + " changed_by = ?",
                                Integer.class,
                                code + "|" + YEAR,
                                MAKER))
                .as("the run is audited in the requester's name")
                .isPositive();
        BatchJobRequest done = requests.findById(request.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.getResultSummary()).contains("certificates written 2");
    }

    @Test
    void SRS_NF_7_theQuarterIsDraftUntilDoneAndRegeneratedInPlace() throws Exception {
        finalYear();
        JobExecution first = run(returnJob, picked(JobType.TAX_24Q_RETURN, quarter(YEAR)));

        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> r = theReturn(YEAR);
        assertThat(r.get("RETURN_STATUS")).isEqualTo("GENERATED");
        assertThat(((Number) r.get("DEDUCTEE_COUNT")).longValue()).isEqualTo(3);
        // Q4 is January - March: 3 x (14,950 + 20,000 + 8,000).
        assertThat((BigDecimal) r.get("TOTAL_AMOUNT")).isEqualByComparingTo("128850");
        assertThat(rows(r.get("RETURN_ID"))).isEqualTo(3);

        // C-11 alternate flow: the data corrected, the quarter generated again, in place.
        Long short5 = TaxYearFixtures.member(jdbc, "870005");
        ItTaxPorts.YEARS.put(short5 + "|" + YEAR, ItTaxPorts.year(YEAR, "100000", "0", true));
        JobExecution again = run(returnJob, picked(JobType.TAX_24Q_RETURN, quarter(YEAR)));

        assertThat(again.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> regenerated = theReturn(YEAR);
        assertThat(regenerated.get("RETURN_ID")).isEqualTo(r.get("RETURN_ID"));
        assertThat(((Number) regenerated.get("DEDUCTEE_COUNT")).longValue()).isEqualTo(2);
        assertThat((BigDecimal) regenerated.get("TOTAL_AMOUNT")).isEqualByComparingTo("104850");
        assertThat(rows(r.get("RETURN_ID")))
                .as("the former deductee's row is kept, emptied (R-DEL)")
                .isEqualTo(3);
    }

    @Test
    void SRS_NF_7_aFailedRunStaysDraftAndARestartFinishesItWithoutDuplicates() throws Exception {
        finalYear();
        ItTaxPorts.FAILING.add(TaxYearFixtures.member(jdbc, "870003"));
        BatchJobRequest request = picked(JobType.TAX_24Q_RETURN, quarter(YEAR));

        JobExecution first = run(returnJob, request);

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        Map<String, Object> half = theReturn(YEAR);
        assertThat(half.get("RETURN_STATUS"))
                .as("neither validated nor released half done")
                .isEqualTo("DRAFT");
        assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.FAILED);

        ItTaxPorts.FAILING.clear();
        JobExecution second = restart(returnJob, request);

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId())
                .isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(second.getExecutionContext().getLong("fci.tax.returnId"))
                .as("the restart keeps the return it opened")
                .isEqualTo(((Number) half.get("RETURN_ID")).longValue());
        Map<String, Object> done = theReturn(YEAR);
        assertThat(done.get("RETURN_STATUS")).isEqualTo("GENERATED");
        assertThat(((Number) done.get("DEDUCTEE_COUNT")).longValue()).isEqualTo(3);
        assertThat(rows(done.get("RETURN_ID"))).as("DDL uk_return_deductee").isEqualTo(3);
    }

    @Test
    void SRS_7_7_aRunRefusedAtItsOpeningChecksAgainWhenRestarted() throws Exception {
        finalYear();
        BatchJobRequest request = picked(JobType.TAX_24Q_RETURN, quarter(LATER));

        JobExecution refused = run(returnJob, request);

        assertThat(refused.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(refused.getExecutionContext().containsKey("fci.tax.unitId")).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM statutory_return WHERE fin_year = ?",
                                Integer.class,
                                LATER))
                .as("the quarter is not locked: nothing is opened")
                .isZero();

        TaxYearFixtures.locked(jdbc, unit(), TaxYearFixtures.months(LATER), LATER);
        JobExecution second = restart(returnJob, request);

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(theReturn(LATER).get("RETURN_STATUS")).isEqualTo("GENERATED");
        assertThat(
                        second.getStepExecutions().stream()
                                .filter(s -> s.getExecutionContext().containsKey(BatchKeys.SUMMARY))
                                .map(s -> s.getExecutionContext().getString(BatchKeys.SUMMARY))
                                .findFirst()
                                .orElseThrow())
                .contains("deductees written 0");
    }
}
