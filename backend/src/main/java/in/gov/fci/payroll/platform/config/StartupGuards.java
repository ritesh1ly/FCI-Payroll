package in.gov.fci.payroll.platform.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails start-up when the mode is ambiguous (not exactly one of {@code online}/{@code batch}), and
 * in {@code uat} and {@code prod} when the deployment is unsafe (ADR-0013, ADR-0019): a database
 * other than Oracle on a server, ephemeral keys, a missing secret, or a well-known placeholder
 * value. Messages name the property, never its value.
 */
@Component
public class StartupGuards implements InitializingBean {

    private static final Set<String> STRICT_PROFILES = Set.of("uat", "prod");
    private static final Set<String> PLACEHOLDERS =
            Set.of("changeit", "changeme", "password", "secret", "admin", "test");

    private final Environment environment;
    private final ApiProperties properties;

    public StartupGuards(Environment environment, ApiProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        // One WAR, two modes: exactly one of them, so a misconfigured instance can never run the
        // REST API and the scheduler together (or neither).
        boolean online = profiles.contains("online");
        boolean batch = profiles.contains("batch");
        if (online == batch) {
            throw new IllegalStateException(
                    "Exactly one of the profiles 'online' and 'batch' must be active (got "
                            + profiles
                            + ")");
        }
        if (profiles.contains("prod") && profiles.contains("demo")) {
            throw new IllegalStateException(
                    "Unsafe configuration: the demo profile (fake seed users, shared demo password)"
                            + " is never allowed with prod");
        }
        // The edge-case data set (owner 2026-10-30): with the demo seed on a disposable H2 database
        // only; never with the volume set, never in production or a real UAT.
        if (profiles.contains("edge")
                && (!profiles.contains("demo")
                        || profiles.contains("volume")
                        || profiles.contains("prod"))) {
            throw new IllegalStateException(
                    "Unsafe configuration: the edge profile needs demo and is never allowed with"
                            + " volume or prod");
        }
        // The screen demo data set (OD-141 D-screens, owner 2026-11-09; docs/db/INSTALL_KIT.md):
        // on top of the full demo data, H2 or Oracle; never with volume, never with edge (edge
        // turns the demo-evaluation seeds off and writes the same offices), never in production.
        if (profiles.contains("screens")
                && (!profiles.contains("demo")
                        || profiles.contains("volume")
                        || profiles.contains("edge")
                        || profiles.contains("prod"))) {
            throw new IllegalStateException(
                    "Unsafe configuration: the screens profile needs demo and is never allowed with"
                            + " volume, edge or prod");
        }
        // The worker clones of the demo users (owner 2026-11-07): parallel Playwright lanes on a
        // disposable H2 demo stack only; the scripts exist for H2 alone.
        if (profiles.contains("e2e-parallel")
                && (!profiles.contains("demo")
                        || profiles.contains("volume")
                        || profiles.contains("oracle")
                        || profiles.contains("prod"))) {
            throw new IllegalStateException(
                    "Unsafe configuration: the e2e-parallel profile needs demo and is never allowed"
                            + " with volume, oracle or prod");
        }
        // The 50K volume set (fake employees, the demo seed's users) is a load-test set: never in
        // production. Like edge, it migrates out of order (application.yaml).
        if (profiles.contains("volume") && profiles.contains("prod")) {
            throw new IllegalStateException(
                    "Unsafe configuration: the volume profile is never allowed with prod");
        }
        // Resilience runs only (owner 2026-10-27): fault injection on a local demo stack, never in
        // production or a real UAT.
        if (profiles.contains("chaos-monkey")
                && (profiles.contains("prod")
                        || (profiles.contains("uat") && !profiles.contains("demo")))) {
            throw new IllegalStateException(
                    "Unsafe configuration: the chaos-monkey profile is allowed only in local demo"
                            + " runs");
        }
        // Owner 2026-11-06: Payroll calls the FCI HRMS test double only in development; the profile
        // is
        // never in production or in a real UAT.
        if (profiles.contains("hrms-mock")
                && (profiles.contains("prod")
                        || (profiles.contains("uat") && !profiles.contains("demo")))) {
            throw new IllegalStateException(
                    "Unsafe configuration: the hrms-mock profile is allowed only in local, dev and"
                            + " demo runs");
        }
        boolean logCodes =
                environment.getProperty("fci.api.notification.log-codes", Boolean.class, false);
        if (logCodes
                && (profiles.contains("prod")
                        || (profiles.contains("uat") && !profiles.contains("demo")))) {
            throw new IllegalStateException(
                    "Unsafe configuration: fci.api.notification.log-codes is allowed only in local,"
                            + " dev and demo runs");
        }
        // OD-146: the demo OTP code viewer shows a signed-in evaluator the code sent to the own
        // account; only on a demo stack (never with prod, never in a UAT that is not a local demo
        // stack, never without the demo profile).
        boolean demoViewer =
                environment.getProperty("fci.api.otp.demo-viewer", Boolean.class, false);
        if (demoViewer
                && (!profiles.contains("demo")
                        || profiles.contains("prod")
                        || (profiles.contains("uat") && !profiles.contains("demo")))) {
            throw new IllegalStateException(
                    "Unsafe configuration: fci.api.otp.demo-viewer is allowed only on demo stacks"
                            + " (profile demo), never in production or UAT");
        }
        // Project owner, 2026-10-23: OTP verification of the critical activities is Y in UAT and
        // production; N only on development and local machines (a local stack runs uat,demo).
        if (!properties.otp().verificationRequired()
                && (profiles.contains("prod")
                        || (profiles.contains("uat") && !profiles.contains("demo")))) {
            throw new IllegalStateException(
                    "Unsafe configuration: fci.api.otp.required must be Y in prod and UAT; N is for"
                            + " development and local machines only");
        }
        // SRS-G-48 (owner 2026-10-29, OD-36): every sign-in asks for its OTP in UAT and production.
        if (!properties.otp().atLogin()
                && (profiles.contains("prod")
                        || (profiles.contains("uat") && !profiles.contains("demo")))) {
            throw new IllegalStateException(
                    "Unsafe configuration: fci.api.otp.at-login must be true in prod and UAT"
                            + " (SRS-G-48)");
        }
        database(profiles);
        boolean strict = profiles.stream().anyMatch(STRICT_PROFILES::contains);
        if (!strict) {
            return;
        }
        List<String> problems = new ArrayList<>();
        ApiProperties.Secrets secrets = properties.secrets();
        if (secrets.ephemeralKeys()) {
            problems.add("fci.api.secrets.ephemeral-keys must be false in uat/prod");
        }
        require(problems, "fci.api.secrets.audit-hmac-key", secrets.auditHmacKey());
        require(problems, "fci.api.secrets.data-encryption-key", secrets.dataEncryptionKey());
        if (environment.getProperty("springdoc.api-docs.enabled", Boolean.class, false)) {
            problems.add("springdoc.api-docs.enabled must be false in uat/prod");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe configuration: " + String.join("; ", problems));
        }
    }

    /**
     * The database of the environment (owner 2026-11-01, ORA-2): DEV, UAT and production run on the
     * on-premises Oracle (profile {@code oracle}); H2 is only for local test runs and Playwright,
     * and the cloud wallet (profile {@code oracle-adb}) only for a local machine. A local stack is
     * the profile {@code local} or {@code fci.local-stack=true}, which only deploy-local.ps1 sets;
     * a production profile is never local.
     */
    private void database(List<String> profiles) {
        boolean prod = profiles.contains("prod");
        boolean uat = profiles.contains("uat");
        boolean local =
                !prod
                        && (profiles.contains("local")
                                || environment.getProperty(
                                        "fci.local-stack", Boolean.class, false));
        if (local || !(prod || uat || profiles.contains("dev"))) {
            return;
        }
        String url = environment.getProperty("spring.datasource.url", "");
        if (url.startsWith("jdbc:h2:")) {
            throw new IllegalStateException(
                    "Unsafe configuration: spring.datasource.url is an H2 database; DEV, UAT and"
                        + " production run on Oracle (profile oracle); H2 is only for local test"
                        + " runs (ADR-0019)");
        }
        if (profiles.contains("oracle-adb")) {
            throw new IllegalStateException(
                    "Unsafe configuration: the oracle-adb profile (the cloud wallet) is for local"
                            + " machines only; servers use profile oracle (on premises)");
        }
        if ((prod || uat) && !url.startsWith("jdbc:oracle:")) {
            throw new IllegalStateException(
                    "Unsafe configuration: spring.datasource.url must be an Oracle database in UAT"
                            + " and production (profile oracle, ADR-0019)");
        }
    }

    private static void require(List<String> problems, String property, String value) {
        if (value == null || value.isBlank()) {
            problems.add(property + " is required");
        } else if (PLACEHOLDERS.contains(value.strip().toLowerCase(Locale.ROOT))) {
            problems.add(property + " uses a placeholder value");
        }
    }
}
