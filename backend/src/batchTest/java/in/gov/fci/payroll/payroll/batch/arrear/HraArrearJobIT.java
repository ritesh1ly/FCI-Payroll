package in.gov.fci.payroll.payroll.batch.arrear;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.jobs.batch.BatchTestSupport;
import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobType;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * HRA_ARREAR end to end on the demo data (plan 13 phase 13c; SRS-8-10, SRS-8-15): the run raises
 * one HRA_REVISION event for the cycle unit and range and marks it COMPUTED; a second run whose
 * range overlaps that open event of the unit fails before reading a member, raising no event.
 */
class HraArrearJobIT extends BatchTestSupport {

    @Autowired
    @Qualifier("hraArrearJob")
    private Job job;

    @Autowired private ArrearEvents arrearEvents;

    @BeforeEach
    void useJob() {
        jobLauncher.setJob(job);
    }

    private Long unit() {
        return jdbc.queryForObject(
                "SELECT location_id FROM org_location WHERE location_code = 'DH001'", Long.class);
    }

    private BatchJobRequest request(String from, String to) {
        return request(from, to, unit());
    }

    private BatchJobRequest request(String from, String to, Long location) {
        return pickedRequest(
                JobType.HRA_ARREAR,
                Map.of(
                        "locationId",
                        String.valueOf(location),
                        "fromPeriod",
                        from,
                        "toPeriod",
                        to,
                        "reason",
                        "HRA arrear IT " + System.nanoTime()),
                location);
    }

    private long events(BatchJobRequest request) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM arrear_event WHERE source_entity ="
                                + " 'batch_job_request' AND source_key = ?",
                        Long.class,
                        String.valueOf(request.getId()));
        return n == null ? 0 : n;
    }

    // SC-13-0009 (HRA arrears only through the arrear mechanism, never keyed), SC-13-0039 (no
    // overlapping open event for the same component and unit).
    @Test
    void SRS_8_10_SRS_8_15_BR_ARR_HRA_001_aRunRaisesItsHraEventAndAnOverlappingRunIsRefused()
            throws Exception {
        BatchJobRequest first = request("2025-04", "2025-06");
        JobExecution execution = jobLauncher.startJob(JobLaunchService.parameters(first));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> event =
                jdbc.queryForMap(
                        "SELECT e.event_status, e.event_reference, t.event_code FROM arrear_event e"
                                + " JOIN arrear_event_type t ON t.event_type_id = e.event_type_id"
                                + " WHERE e.source_entity = 'batch_job_request' AND e.source_key ="
                                + " ?",
                        String.valueOf(first.getId()));
        assertThat(event.get("EVENT_CODE")).isEqualTo("HRA_REVISION");
        assertThat(event.get("EVENT_STATUS")).isEqualTo("COMPUTED");
        assertThat((String) event.get("EVENT_REFERENCE")).startsWith("HRA arrear 2025-04");

        // SRS-8-15: the same unit and an overlapping range while that event is open.
        BatchJobRequest overlapping = request("2025-06", "2025-08");
        JobExecution refused = jobLauncher.startJob(JobLaunchService.parameters(overlapping));
        assertThat(refused.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(refused.getAllFailureExceptions())
                .anySatisfy(ex -> assertThat(ex).hasMessageContaining("SRS-8-15"));
        assertThat(events(overlapping)).isZero();

        // BMAD TR-034 / TR-081: the boundaries of the overlap. An adjacent range (the month after
        // the open event's last) runs; the same range of another unit runs; once the first event is
        // no longer open (POSTED) its range runs again.
        BatchJobRequest adjacent = request("2025-07", "2025-09");
        assertThat(jobLauncher.startJob(JobLaunchService.parameters(adjacent)).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        Long other =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'CH001'",
                        Long.class);
        BatchJobRequest otherUnit = request("2025-04", "2025-06", other);
        assertThat(jobLauncher.startJob(JobLaunchService.parameters(otherUnit)).getStatus())
                .as("another unit is another population")
                .isEqualTo(BatchStatus.COMPLETED);
        jdbc.update(
                "UPDATE arrear_event SET event_status = 'POSTED' WHERE source_entity ="
                        + " 'batch_job_request' AND source_key = ?",
                String.valueOf(first.getId()));
        BatchJobRequest again = request("2025-05", "2025-06");
        assertThat(jobLauncher.startJob(JobLaunchService.parameters(again)).getStatus())
                .as("a posted event is no longer open")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(events(adjacent) + events(otherUnit) + events(again)).isEqualTo(3);
    }

    /**
     * SRS-8-15 (US-GAP-252): two Makers' runs of the same unit with overlapping ranges raised at
     * the same moment - exactly one event is raised, the other run is refused on the overlap.
     */
    @Test
    void SRS_8_15_twoOverlappingRunsRaisedConcurrentlyGetOneEventTheOtherIsRefused()
            throws Exception {
        BatchJobRequest a = request("2024-04", "2024-06");
        BatchJobRequest b = request("2024-05", "2024-07");
        YearMonth open = YearMonth.now(ZoneId.of("Asia/Kolkata"));
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Object> first =
                pool.submit(
                        () -> {
                            go.await();
                            return raise(a, YearMonth.of(2024, 4), YearMonth.of(2024, 6), open);
                        });
        Future<Object> second =
                pool.submit(
                        () -> {
                            go.await();
                            return raise(b, YearMonth.of(2024, 5), YearMonth.of(2024, 7), open);
                        });
        go.countDown();
        List<Object> outcomes =
                List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        try {
            assertThat(outcomes).filteredOn(o -> o instanceof Long).hasSize(1);
            assertThat(outcomes)
                    .filteredOn(o -> o instanceof IllegalStateException)
                    .singleElement()
                    .satisfies(e -> assertThat(((Exception) e).getMessage()).contains("SRS-8-15"));
            assertThat(events(a) + events(b)).isOne();
        } finally {
            // Leave no open event behind for the other suites' ranges.
            jdbc.update(
                    "UPDATE arrear_event SET event_status = 'REVERSED' WHERE source_entity ="
                            + " 'batch_job_request' AND source_key IN (?, ?)",
                    String.valueOf(a.getId()),
                    String.valueOf(b.getId()));
        }
    }

    private Object raise(BatchJobRequest r, YearMonth from, YearMonth to, YearMonth open) {
        try {
            return arrearEvents.raise(r.getId(), unit(), from, to, open, "HRA_REVISION", "HRA");
        } catch (IllegalStateException refused) {
            return refused;
        }
    }
}
