package in.gov.fci.payroll.iam.config;

import in.gov.fci.payroll.platform.config.SecurityOffenceProperties;
import in.gov.fci.payroll.platform.logging.CriticalLog;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * OD-130 (owner): {@code fci.security.offence.enabled} is configurable in every environment, so
 * StartupGuards do not refuse N. Instead N outside development leaves a critical entry at each
 * start, which an alert can catch. Development means the {@code local} and {@code dev} profiles and
 * a local demo stack ({@code uat} with {@code demo}), as the other switches read it.
 */
@Component
@Profile("online")
public class SecurityOffenceStartupCheck {

    private final SecurityOffenceProperties settings;
    private final Environment environment;

    public SecurityOffenceStartupCheck(
            SecurityOffenceProperties settings, Environment environment) {
        this.settings = settings;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        boolean development =
                profiles.contains("local") || profiles.contains("dev") || profiles.contains("demo");
        if (!settings.on() && !development) {
            CriticalLog.log(
                    CriticalLog.Event.SECURITY_OFFENCE_DISABLED,
                    CriticalLog.fields("key", "fci.security.offence.enabled", "value", "N"));
        }
    }
}
