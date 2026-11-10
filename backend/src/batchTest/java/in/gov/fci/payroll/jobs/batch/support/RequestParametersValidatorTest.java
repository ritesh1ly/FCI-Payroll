package in.gov.fci.payroll.jobs.batch.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;

class RequestParametersValidatorTest {

    private final RequestParametersValidator validator =
            new RequestParametersValidator(List.of("locationId", "payPeriod"));

    private static JobParametersBuilder valid() {
        return new JobParametersBuilder()
                .addLong(BatchKeys.REQUEST_ID, 5L)
                .addString(BatchKeys.PARAM_HASH, "abc", false)
                .addString("locationId", "12", false)
                .addString("payPeriod", "2026-07", false);
    }

    @Test
    void acceptsCompleteParameters() {
        assertThatCode(() -> validator.validate(valid().toJobParameters()))
                .doesNotThrowAnyException();
    }

    @Test
    void requiresTheRequestId() {
        JobParameters missing =
                new JobParametersBuilder(valid().toJobParameters())
                        .addLong(BatchKeys.REQUEST_ID, 0L)
                        .toJobParameters();
        assertThatThrownBy(() -> validator.validate(missing))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessageContaining(BatchKeys.REQUEST_ID);
    }

    @Test
    void rejectsMalformedPeriodsAndIds() {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        valid().addString("payPeriod", "2026-13", false)
                                                .toJobParameters()))
                .isInstanceOf(InvalidJobParametersException.class);
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        valid().addString("locationId", "x1", false)
                                                .toJobParameters()))
                .isInstanceOf(InvalidJobParametersException.class);
    }

    @Test
    void requiresTheJobsOwnKeys() {
        JobParameters parameters =
                new JobParametersBuilder()
                        .addLong(BatchKeys.REQUEST_ID, 5L)
                        .addString(BatchKeys.PARAM_HASH, "abc", false)
                        .addString("payPeriod", "2026-07", false)
                        .toJobParameters();
        assertThatThrownBy(() -> validator.validate(parameters)).hasMessageContaining("locationId");
    }

    @org.junit.jupiter.api.Test
    void typedAccessorsReturnValidatedValuesAndFailClearlyWhenMissing() {
        org.springframework.batch.core.job.parameters.JobParameters ok =
                new org.springframework.batch.core.job.parameters.JobParametersBuilder()
                        .addLong(BatchKeys.REQUEST_ID, 42L)
                        .addString("locationId", "7")
                        .toJobParameters();
        org.assertj.core.api.Assertions.assertThat(RequestParametersValidator.requestId(ok))
                .isEqualTo(42L);
        org.assertj.core.api.Assertions.assertThat(
                        RequestParametersValidator.required(ok, "locationId"))
                .isEqualTo("7");
        org.springframework.batch.core.job.parameters.JobParameters empty =
                new org.springframework.batch.core.job.parameters.JobParameters();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> RequestParametersValidator.requestId(empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BatchKeys.REQUEST_ID);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> RequestParametersValidator.required(empty, "payPeriod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payPeriod");
    }
}
