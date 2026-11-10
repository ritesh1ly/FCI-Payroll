package in.gov.fci.payroll.configuration.api;

import in.gov.fci.payroll.configuration.business.model.RuleDefinition;
import in.gov.fci.payroll.configuration.business.model.RuleSettingBound;
import in.gov.fci.payroll.configuration.business.model.RuleVersion;
import in.gov.fci.payroll.configuration.business.model.SystemParameter;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.iam.business.PasswordPolicy;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.cache.VersionedCache;
import in.gov.fci.payroll.platform.config.ApiProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The security and session policy in force (ADM-SYS-001), read by every enforcement point instead
 * of configuration. Since V015 a parameter is a SETTING rule of the rule engine (target DDL v4.0):
 * its value today is the APPROVED {@code rule_version} whose dates cover today (business time
 * zone). Values are cached per instance (cache region SYSTEM_PARAMETER): an approved change marks
 * the region changed in its transaction, so every instance reloads within {@code
 * fci.cache.check-interval}; a version that starts on a date applies from the first read of that
 * day. If a parameter is missing, the configured value applies, so a deployment never runs without
 * a policy.
 */
@Service
public class SystemParameters {

    /** Parameter codes ({@code rule_definition.rule_key} of the SETTING rules). */
    public enum Code {
        SESSION_IDLE_TIMEOUT,
        /** SRS-SEC-14: the idle timeout of a session in the eSeva scope (10 minutes by default). */
        ESEVA_SESSION_IDLE_TIMEOUT,
        SESSION_ABSOLUTE_TIMEOUT,
        SINGLE_SESSION,
        LOGIN_MAX_ATTEMPTS,
        LOCKOUT_DURATION,
        PASSWORD_MIN_LENGTH,
        PASSWORD_EXPIRY,
        PASSWORD_HISTORY,
        OTP_VALIDITY,
        /**
         * SRS-G-22: wrong codes allowed on one challenge; unseeded, the deployment value applies
         * (OD-67).
         */
        OTP_MAX_ATTEMPTS,
        /** SRS-G-22: minutes an action stays blocked after its OTP retry limit. Unseeded. */
        OTP_COOLING_OFF,
        /** SRS-SEC-13, SRS-G-49: kinds of characters a password must mix (four by default). */
        PASSWORD_CHARACTER_CLASSES,
        STEP_UP_VALIDITY,
        FCI_PERIOD_FOR_PAYROLL_PROCESS,
        MAX_RETRO_PERIOD,
        /** SRS-G-50: days without sign-in before the Active User Report flags a user. Unseeded. */
        INACTIVE_USER_DAYS,
        /** SRS-22-11: minimum staff of a Divisional Office running its own payroll. Unseeded. */
        DO_MINIMUM_STAFF,
        /** Owner 2026-10-24: rows a list screen starts with (one of the offered sizes). */
        UI_PAGE_SIZE_DEFAULT,
        /** Owner 2026-10-24: the largest page size a list screen offers (at most 100). */
        UI_PAGE_SIZE_MAX,
        /** SRS-15-7: the largest population a C-09 simulation may select. Unseeded (OQ-17). */
        SIMULATION_MAX_SELECTION,
        /** SRS-15-7: above this population a C-09 simulation runs in the background. Unseeded. */
        SIMULATION_INTERACTIVE_LIMIT,
        /** SRS-1-22: the age at which EPS stops (58, stated by the SRS). */
        EPS_CUTOFF_AGE,
        /** SRS-1-22: the superannuation age of the I-21 retirement-date recalculation. Unseeded. */
        RETIREMENT_AGE,
        /** SRS-1-68: days an I-21 task may stay pending before the alert. Unseeded. */
        I21_AGEING_DAYS,
        /**
         * UC Onboard New Employee: days a new record may stay in Payroll Setup Pending before
         * Establishment / HR is alerted. Unseeded (FRS V0.5 §7.10.1 Scenario 1, Assumption F-4).
         */
        ONBOARDING_AGEING_DAYS
    }

    /**
     * A setting defined for A-07 (rule and hard bounds), whether or not a value is in force: a
     * setting the sources give no value for is defined and left unseeded ("not configured").
     */
    public record Definition(
            Long ruleId,
            String code,
            String group,
            String valueType,
            long minValue,
            long maxValue,
            String unit,
            boolean enforced) {}

    public static final String REGION = "SYSTEM_PARAMETER";

    /** {@code rule_type_reference} code of the A-07 settings. */
    public static final String SETTING = "SETTING";

    private final ConfigurationRepositories.Rules rules;
    private final ConfigurationRepositories.SettingBounds bounds;
    private final ConfigurationRepositories.RuleVersions versions;
    private final ApiProperties properties;
    private final Clock clock;
    private final VersionedCache<LocalDate, Map<String, SystemParameter>> cache;

    public SystemParameters(
            ConfigurationRepositories.Rules rules,
            ConfigurationRepositories.SettingBounds bounds,
            ConfigurationRepositories.RuleVersions versions,
            ApiProperties properties,
            Clock clock,
            CacheRegions regions) {
        this.cache = VersionedCache.of(REGION, regions, 2);
        this.rules = rules;
        this.bounds = bounds;
        this.versions = versions;
        this.properties = properties;
        this.clock = clock;
    }

    /** Today in the business time zone: the date a setting's version must cover. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(properties.businessZone()));
    }

    public Duration sessionIdleTimeout() {
        return Duration.ofMinutes(
                value(Code.SESSION_IDLE_TIMEOUT, properties.session().idleTimeout().toMinutes()));
    }

    /**
     * SRS-SEC-14: idle logout after 15 minutes for payroll users and 10 minutes for eSeva members.
     * The timeout of a session in the eSeva scope (the member's own record, no office); which one
     * applies is the IAM session policy's decision.
     */
    public Duration esevaSessionIdleTimeout() {
        return Duration.ofMinutes(
                value(
                        Code.ESEVA_SESSION_IDLE_TIMEOUT,
                        properties.session().esevaIdleTimeout().toMinutes()));
    }

    public Duration sessionAbsoluteTimeout() {
        return Duration.ofHours(
                value(
                        Code.SESSION_ABSOLUTE_TIMEOUT,
                        properties.session().absoluteTimeout().toHours()));
    }

    public boolean singleSession() {
        return value(Code.SINGLE_SESSION, properties.singleSession() ? 1 : 0) == 1;
    }

    public int loginMaxAttempts() {
        return (int) value(Code.LOGIN_MAX_ATTEMPTS, properties.lockout().maxAttempts());
    }

    public Duration lockoutDuration() {
        return Duration.ofMinutes(
                value(Code.LOCKOUT_DURATION, properties.lockout().duration().toMinutes()));
    }

    /** Never below the policy's own floor, whatever is stored. */
    public int passwordMinLength() {
        return (int)
                Math.max(
                        PasswordPolicy.MIN_LENGTH,
                        value(Code.PASSWORD_MIN_LENGTH, PasswordPolicy.MIN_LENGTH));
    }

    public Duration passwordMaxAge() {
        return Duration.ofDays(
                value(Code.PASSWORD_EXPIRY, properties.password().maxAge().toDays()));
    }

    public int passwordHistory() {
        return (int) value(Code.PASSWORD_HISTORY, properties.password().historySize());
    }

    public Duration otpValidity() {
        return Duration.ofMinutes(value(Code.OTP_VALIDITY, properties.otp().ttl().toMinutes()));
    }

    /**
     * SRS-G-22: the OTP retry limit in force (A-07 OTP_MAX_ATTEMPTS); until FCI sets one, the
     * deployment value {@code fci.api.otp.max-attempts} (OD-67).
     */
    public int otpMaxAttempts() {
        return (int) value(Code.OTP_MAX_ATTEMPTS, properties.otp().maxAttempts());
    }

    /**
     * SRS-G-22: the cooling-off period after the OTP retry limit (A-07 OTP_COOLING_OFF), or empty
     * while FCI has not set one (no source gives a period; OD-67: no block beyond the locked
     * challenge until it is set).
     */
    public java.util.Optional<Duration> otpCoolingOff() {
        OptionalLong minutes = configured(Code.OTP_COOLING_OFF);
        return minutes.isPresent()
                ? java.util.Optional.of(Duration.ofMinutes(minutes.getAsLong()))
                : java.util.Optional.empty();
    }

    /**
     * SRS-SEC-13, SRS-G-49: the kinds of characters a password must mix (A-07
     * PASSWORD_CHARACTER_CLASSES, seeded with the SRS default of all four; a value outside 1 to 4
     * is read by the policy as all four).
     */
    public int passwordCharacterClasses() {
        return (int) value(Code.PASSWORD_CHARACTER_CLASSES, PasswordPolicy.CHARACTER_CLASSES);
    }

    /** How long an OTP step-up of a sensitive screen lasts (Module 02 D-2; minutes). */
    public Duration stepUpValidity() {
        return Duration.ofMinutes(
                value(Code.STEP_UP_VALIDITY, properties.session().stepUpValidity().toMinutes()));
    }

    /**
     * SRS-8-6: the Headquarters back-date limit in months (A-07 MAX_RETRO_PERIOD; payroll jobs:
     * oldest period a run or arrear may reach back to), or empty while FCI has not set it. No
     * source gives a value (CLR-147): the baseline 60 is demo data only since V077_41, and a caller
     * whose answer depends on the limit refuses "not configured" instead of a guess (R-RULE, KB
     * CF-49).
     */
    public OptionalLong maxRetroMonths() {
        return configured(Code.MAX_RETRO_PERIOD);
    }

    /** Rows a list screen starts with (docs/plan/X-pagination.md; 20 until configured). */
    public int pageSizeDefault() {
        return (int) value(Code.UI_PAGE_SIZE_DEFAULT, 20);
    }

    /** The largest page size a list screen offers (100 until configured). */
    public int pageSizeMax() {
        return (int) value(Code.UI_PAGE_SIZE_MAX, 100);
    }

    /**
     * The value in force of a setting that has no fallback, or empty while FCI has not configured
     * it (the caller then says "not configured" instead of guessing).
     */
    public OptionalLong configured(Code code) {
        SystemParameter parameter = current().get(code.name());
        return parameter == null ? OptionalLong.empty() : OptionalLong.of(parameter.value());
    }

    /** Every active A-07 setting with its bounds, seeded or not, by code. */
    @Transactional(readOnly = true)
    public List<Definition> definitions() {
        List<RuleDefinition> settings = rules.findByTypeCodeOrderByKey(SETTING);
        Map<Long, RuleSettingBound> boundById = new HashMap<>();
        bounds.findByRuleIdIn(settings.stream().map(RuleDefinition::getId).toList())
                .forEach(b -> boundById.put(b.getRuleId(), b));
        return settings.stream()
                .filter(RuleDefinition::isActive)
                .filter(rule -> boundById.containsKey(rule.getId()))
                .map(
                        rule -> {
                            RuleSettingBound bound = boundById.get(rule.getId());
                            return new Definition(
                                    rule.getId(),
                                    rule.getKey(),
                                    bound.getGroup(),
                                    bound.getValueType().name(),
                                    bound.getMinValue(),
                                    bound.getMaxValue(),
                                    bound.getUnit(),
                                    bound.isEnforced());
                        })
                .toList();
    }

    /** The current version of every parameter, by code. */
    @Transactional(readOnly = true)
    public Map<String, SystemParameter> current() {
        // Keyed by the business date: a version that starts at midnight applies from the first read
        // of the day.
        return cache.get(today(), k -> load());
    }

    /** After an approved change: every instance reloads (in the caller's transaction). */
    public void evict() {
        cache.changed("system-parameter");
    }

    private long value(Code code, long fallback) {
        SystemParameter parameter = current().get(code.name());
        return parameter == null ? fallback : parameter.value();
    }

    private Map<String, SystemParameter> load() {
        LocalDate today = today();
        List<RuleDefinition> settings = rules.findByTypeCodeOrderByKey(SETTING);
        List<Long> ids = settings.stream().map(RuleDefinition::getId).toList();
        Map<Long, RuleSettingBound> boundById = new HashMap<>();
        bounds.findByRuleIdIn(ids).forEach(b -> boundById.put(b.getRuleId(), b));
        Map<Long, RuleVersion> inForce = new HashMap<>();
        for (RuleVersion v : versions.findByRuleIdInAndState(ids, RuleVersion.State.APPROVED)) {
            if (v.isInForceOn(today)) {
                inForce.put(v.getRuleId(), v);
            }
        }
        Map<String, SystemParameter> result = new HashMap<>();
        for (RuleDefinition rule : settings) {
            RuleSettingBound bound = boundById.get(rule.getId());
            RuleVersion version = inForce.get(rule.getId());
            if (rule.isActive() && bound != null && version != null) {
                result.put(rule.getKey(), SystemParameter.of(rule, bound, version));
            }
        }
        return Map.copyOf(result);
    }
}
