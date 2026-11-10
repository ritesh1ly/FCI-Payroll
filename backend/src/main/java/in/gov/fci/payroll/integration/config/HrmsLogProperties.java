package in.gov.fci.payroll.integration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What the HRMS exchange log (logger {@code FCI_HRMS_EXCHANGE}) carries (SRS-INT-6, R-PII). Every
 * attempt of every call is logged with its API, method, path, attempt number, HTTP status, duration
 * and sizes; the request and response BODIES are added only when {@code bodies} is on, always
 * masked (passwords, tokens, api-keys, Aadhaar, PAN, mobile numbers, e-mail addresses) and cut to
 * {@code max-body-chars}. The token request and its answer are never logged, whatever the setting.
 *
 * <pre>
 * fci.integration.hrms.log.bodies: true            default true; false = metadata only
 * fci.integration.hrms.log.max-body-chars: 1500    default 1500, kept within 100 .. 8000
 * </pre>
 */
@ConfigurationProperties("fci.integration.hrms.log")
public record HrmsLogProperties(Boolean bodies, Integer maxBodyChars) {

    /** Whether masked bodies are logged. */
    public boolean logBodies() {
        return bodies == null || bodies;
    }

    /** The most characters of one body that are logged. */
    public int maxBody() {
        if (maxBodyChars == null) {
            return 1500;
        }
        return Math.max(100, Math.min(8000, maxBodyChars));
    }
}
