package in.gov.fci.payroll.jobs.batch.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;

/**
 * SRS-NF-2 (batch jobs, C-13): a job never starts on a missing or malformed parameter, and an empty
 * partition reads nothing (location -1), never every location.
 */
class BatchSupportTest {

    private final RequestParametersValidator validator =
            new RequestParametersValidator(List.of("payPeriod", "locationId"));

    private static JobParametersBuilder base() {
        return new JobParametersBuilder()
                .addLong(BatchKeys.REQUEST_ID, 7L)
                .addString(BatchKeys.PARAM_HASH, "h");
    }

    @Test
    void SRS_NF_2_aLaunchCarriesItsRequestHashAndEveryRequiredParameterWellFormed() {
        assertThatCode(
                        () ->
                                validator.validate(
                                        base().addString("payPeriod", "2026-07")
                                                .addString("locationId", "3")
                                                .toJobParameters()))
                .doesNotThrowAnyException();
        JobParameters[] refused = {
            null,
            new JobParametersBuilder().addString(BatchKeys.PARAM_HASH, "h").toJobParameters(),
            new JobParametersBuilder()
                    .addLong(BatchKeys.REQUEST_ID, 0L)
                    .addString(BatchKeys.PARAM_HASH, "h")
                    .toJobParameters(),
            new JobParametersBuilder().addLong(BatchKeys.REQUEST_ID, 7L).toJobParameters(),
            base().addString("locationId", "3").toJobParameters(),
            base().addString("payPeriod", " ").addString("locationId", "3").toJobParameters(),
            base().addString("payPeriod", "2026-13").addString("locationId", "3").toJobParameters(),
            base().addString("payPeriod", "2026-07").addString("locationId", "0").toJobParameters(),
            base().addString("payPeriod", "2026-07")
                    .addString("locationId", "x1")
                    .toJobParameters(),
        };
        for (JobParameters p : refused) {
            assertThatThrownBy(() -> validator.validate(p))
                    .as(String.valueOf(p))
                    .isInstanceOf(InvalidJobParametersException.class);
        }
        assertThat(RequestParametersValidator.requestId(base().toJobParameters())).isEqualTo(7L);
        assertThatThrownBy(
                        () ->
                                RequestParametersValidator.requestId(
                                        new JobParametersBuilder().toJobParameters()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void SRS_NF_2_anEmptyPartitionReadsNoLocation() {
        assertThat(LocationPartitioner.parse(null)).containsExactly(-1L);
        assertThat(LocationPartitioner.parse("  ")).containsExactly(-1L);
        assertThat(LocationPartitioner.parse("3, 9")).containsExactly(3L, 9L);
    }

    /**
     * SRS-NF-2: only a location of the unit with employees is planned (a null, zero or foreign
     * count is not); a count row whose location is not a number is skipped.
     */
    @Test
    void SRS_NF_2_onlyPopulatedLocationsOfTheUnitArePlannedAndBadCountRowsSkipped() {
        java.util.Map<Long, Long> counts = new java.util.HashMap<>();
        counts.put(1L, null);
        counts.put(2L, 0L);
        counts.put(3L, 4L);
        counts.put(99L, 7L);
        java.util.Map<String, org.springframework.batch.infrastructure.item.ExecutionContext>
                parts =
                        new LocationPartitioner(java.util.List.of(1L, 2L, 3L), ids -> counts)
                                .partition(4);
        assertThat(parts).hasSize(1);
        assertThat(parts.get("partition0").getString(BatchKeys.LOCATIONS)).isEqualTo("3");

        java.util.Map<String, Object> good = new java.util.LinkedHashMap<>();
        good.put("LOCATION_ID", 3L);
        good.put("CNT", 4L);
        java.util.Map<String, Object> textual = new java.util.LinkedHashMap<>();
        textual.put("LOCATION_ID", "3");
        textual.put("CNT", 4L);
        assertThat(LocationPartitioner.toMap(java.util.List.of(good, textual)))
                .containsExactly(java.util.Map.entry(3L, 4L));
    }
}
