package in.gov.fci.payroll.platform.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * OD-130: what the application does when a signed-in user's API call is refused for lack of right
 * or office scope ({@code fci.security.offence.*}; application.yaml documents each key). Owner's
 * values, FCI security to verify (not in the SRS): configuration of the deployment, overridable per
 * profile and by environment variable as every {@code fci.*} property, deliberately not a rule
 * engine setting.
 *
 * <p>The refused call always writes {@code security_event} and the critical log entry: no key turns
 * that off.
 *
 * @param enabled master switch, Y or N: N keeps the event and the critical log only
 * @param logoutOnOffence Y or N: end the user's sessions on an offence
 * @param suspendAfter offences since the last reinstatement that suspend the account; 0 never does
 */
@Validated
@ConfigurationProperties(prefix = "fci.security.offence")
public record SecurityOffenceProperties(
        @DefaultValue("Y") @Pattern(regexp = "[YN]") String enabled,
        @DefaultValue("Y") @Pattern(regexp = "[YN]") String logoutOnOffence,
        @DefaultValue("3") @Min(0) int suspendAfter) {

    /** The master switch: the refused call is acted on, not only recorded. */
    public boolean on() {
        return "Y".equals(enabled);
    }

    /** An offence ends the user's sessions. */
    public boolean logsOut() {
        return on() && "Y".equals(logoutOnOffence);
    }

    /** {@code count} offences since the last reinstatement suspend the account. */
    public boolean suspendsAt(long count) {
        return on() && suspendAfter > 0 && count >= suspendAfter;
    }
}
