package in.gov.fci.payroll.jobs.business;

import in.gov.fci.payroll.platform.security.SecureTokens;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Job parameters as canonical JSON (keys sorted, string values) and its SHA-256. The hash is the
 * identity of "the same job with the same parameters": the API's duplicate guard, the job lock key
 * and the resume rule of the target DDL ({@code computation_run.parameter_hash}: a run is resumed
 * only with unchanged parameters).
 */
public final class JobParameterCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JobParameterCodec() {}

    public static String toJson(Map<String, String> params) {
        return JSON.writeValueAsString(new TreeMap<>(params));
    }

    public static Map<String, String> fromJson(String json) {
        try {
            return new TreeMap<>(
                    JSON.readValue(
                            json == null || json.isBlank() ? "{}" : json,
                            new TypeReference<Map<String, String>>() {}));
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored job parameters are not valid JSON", e);
        }
    }

    public static String hash(String jobName, Map<String, String> params) {
        return SecureTokens.sha256Hex(jobName + "|" + toJson(params));
    }
}
