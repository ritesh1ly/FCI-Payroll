package in.gov.fci.payroll.jobs.batch.support;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersValidator;

/**
 * Validates a job's parameters before every launch (first run and restart): the identifying {@code
 * jobRequestId}, the parameter hash, and the job's own keys ({@code *Period} as YYYY-MM, {@code
 * locationId} as an id). The API validated them already; this guards the job itself against a
 * launch from anywhere else.
 *
 * <p>No {@code JobParametersIncrementer}: the request id is the job's identity. An incrementer
 * would give every launch a new JobInstance and make restart-from-checkpoint impossible.
 */
public class RequestParametersValidator implements JobParametersValidator {

    private static final Pattern PERIOD = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");
    private static final Pattern ID = Pattern.compile("^[1-9]\\d{0,17}$");

    private final List<String> required;

    public RequestParametersValidator(List<String> required) {
        this.required = List.copyOf(required);
    }

    @Override
    public void validate(JobParameters parameters) throws InvalidJobParametersException {
        if (parameters == null) {
            throw new InvalidJobParametersException("Job parameters are required");
        }
        Long requestId = parameters.getLong(BatchKeys.REQUEST_ID);
        if (requestId == null || requestId <= 0) {
            throw new InvalidJobParametersException(BatchKeys.REQUEST_ID + " is required");
        }
        if (parameters.getString(BatchKeys.PARAM_HASH) == null) {
            throw new InvalidJobParametersException(BatchKeys.PARAM_HASH + " is required");
        }
        for (String key : required) {
            String value = parameters.getString(key);
            if (value == null || value.isBlank()) {
                throw new InvalidJobParametersException(key + " is required");
            }
            if (key.endsWith("Period") && !PERIOD.matcher(value).matches()) {
                throw new InvalidJobParametersException(key + " must be YYYY-MM");
            }
            if (key.endsWith("Id") && !ID.matcher(value).matches()) {
                throw new InvalidJobParametersException(key + " must be an id");
            }
        }
    }

    /** The request id of a validated launch (listeners run after {@link #validate}). */
    public static long requestId(JobParameters parameters) {
        Long value = parameters.getLong(BatchKeys.REQUEST_ID);
        if (value == null) {
            throw new IllegalStateException(
                    "Job parameter " + BatchKeys.REQUEST_ID + " is missing");
        }
        return value;
    }

    /** A required parameter of a validated launch. */
    public static String required(JobParameters parameters, String key) {
        String value = parameters.getString(key);
        if (value == null) {
            throw new IllegalStateException("Job parameter " + key + " is missing");
        }
        return value;
    }
}
