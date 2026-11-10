package in.gov.fci.payroll.integration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Whether the Administrator's HRMS API console of screen X-03 may call HRMS (SRS-INT-33, R-INT):
 *
 * <pre>
 * fci.integration.hrms.console.enabled: true
 * </pre>
 *
 * Off unless set: a production server never offers a free-form call to HRMS by default.
 */
@ConfigurationProperties("fci.integration.hrms.console")
public record HrmsConsoleProperties(Boolean enabled) {

    /** Whether the console is on (absent = off). */
    public boolean on() {
        return Boolean.TRUE.equals(enabled);
    }
}
