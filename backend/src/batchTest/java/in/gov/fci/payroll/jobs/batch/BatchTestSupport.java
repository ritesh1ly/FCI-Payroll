package in.gov.fci.payroll.jobs.batch;

import in.gov.fci.payroll.FciPayrollApplication;
import in.gov.fci.payroll.jobs.business.JobParameterCodec;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.batch.computation.MemberReader;
import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import java.time.Clock;
import java.util.Map;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The whole application in the batch profile, on a private in-memory H2 database migrated by Flyway
 * with the demo seed (profiles local, demo, batch). The scheduler is off: tests drive the poller's
 * parts and the jobs directly.
 */
@SpringBootTest(classes = FciPayrollApplication.class)
@SpringBatchTest
@ActiveProfiles({"local", "demo", "batch"})
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:batch-${random.uuid};MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.flyway.enabled=true",
            "fci.batch.poller.enabled=false",
            "fci.common.management-port=0",
            // Small partitions and chunks for every batch test (one shared Spring context; a test
            // that needs other values overrides them and gets a context of its own).
            "fci.batch.partition.grid-size=3",
            "fci.batch.chunk.size=5",
            "fci.batch.chunk.page-size=5"
        })
public abstract class BatchTestSupport {

    @Autowired protected JobOperatorTestUtils jobLauncher;
    @Autowired protected org.springframework.batch.core.repository.JobRepository jobRepository;
    @Autowired protected JobRepositories.JobRequests requests;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected NamedParameterJdbcTemplate named;
    @Autowired protected OrgDirectory org;
    @Autowired protected Clock clock;
    @Autowired protected CycleRuns cycles;

    /**
     * A cycle unit of the demo seed and the members a computation of it reads (MemberReader
     * eligibility).
     */
    protected record Unit(Long locationId, long activeEmployees) {}

    /** A given cycle unit and the members a computation of it reads. */
    protected Unit unitOf(Long cycleUnitId) {
        return new Unit(cycleUnitId, members(cycleUnitId));
    }

    /** The demo cycle unit whose subtree holds the most members eligible for a computation. */
    protected Unit busiestUnit() {
        Unit best = null;
        for (Long id : jdbc.queryForList("SELECT location_id FROM org_location", Long.class)) {
            Long unit = cycles.cycleUnit(id).getId();
            if (!unit.equals(id)) {
                continue;
            }
            long count = members(unit);
            if (best == null || count > best.activeEmployees()) {
                best = new Unit(id, count);
            }
        }
        return best;
    }

    /**
     * Members of the cycle unit's subtree that a computation of 2026-07 (the tests' period) reads.
     */
    protected long members(Long cycleUnitId) {
        return named
                .queryForList(
                        MemberReader.countSql(),
                        Map.of("locations", org.subtree(cycleUnitId), "yymm", "202607"))
                .stream()
                .mapToLong(row -> ((Number) row.values().toArray()[1]).longValue())
                .sum();
    }

    /** A request as the online API would have queued it, already PICKED by "this node". */
    protected BatchJobRequest pickedRequest(
            JobType type, Map<String, String> params, Long locationId) {
        BatchJobRequest request =
                BatchJobRequest.submit(
                        type,
                        JobParameterCodec.toJson(params),
                        JobParameterCodec.hash(type.name(), params),
                        5,
                        BatchJobRequest.Source.UI,
                        null,
                        "test.maker",
                        locationId,
                        params.get("payPeriod"),
                        clock.instant());
        request = requests.save(request);
        request.pick("test-node", clock.instant());
        return requests.save(request);
    }

    /**
     * payroll_result rows of the computation_run the request opened (parameters_json names the
     * request).
     */
    protected long results(Long requestId) {
        Long count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_result r JOIN computation_run c ON c.run_id ="
                                + " r.run_id WHERE c.parameters_json LIKE ?",
                        Long.class,
                        "{\"jobRequestId\":" + requestId + ",%");
        return count == null ? 0 : count;
    }

    /**
     * Executions created as another node's launch would leave them (see {@link TestExecutions}).
     */
    protected TestExecutions executions() {
        return new TestExecutions(jobRepository);
    }
}
