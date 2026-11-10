package in.gov.fci.payroll.configuration.api;

import in.gov.fci.payroll.configuration.business.model.PayComponent;
import in.gov.fci.payroll.configuration.business.model.RateSet;
import in.gov.fci.payroll.configuration.business.model.RateSetLine;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pay configuration a computation uses, AS IN FORCE ON A DATE (target DDL v4.0, BR-RULE-007:
 * reproducible): the approved rate-set versions of M-04 (DA per pay pattern, HRA % and CCA amount
 * by city class) and the pay components of M-07. Only approved versions count; a missing rate is
 * absent, never zero, so the computation can fail cleanly instead of silently paying nothing.
 */
@Component
public class PayConfiguration {

    /**
     * The rate sets the staff computation reads (M-04 catalogue STAFF_RATE_MASTER). HRA is the IDA
     * pattern's; HRA_CDA holds the CDA pattern's own rule (SRS-1-3, US-GAP-41; V071_1).
     */
    public static final List<String> STAFF_RATE_SETS =
            List.of("DA_IDA", "DA_CDA", "HRA", "HRA_CDA", "CCA");

    /**
     * The key prefix of a CDA HRA rate in {@link Snapshot#hraPercent()}: {@code CDA/X} is the CDA
     * rate of city class X (the plain class is the IDA rate).
     */
    public static final String CDA_HRA = "CDA/";

    /**
     * One snapshot of the configuration in force on {@code asOf}.
     *
     * @param daPercent DA % by pay pattern (IDA, CDA; FND follows CDA)
     * @param hraPercent HRA % by city class for the IDA pattern, and under {@code CDA/<class>} for
     *     the CDA pattern's own rule (SRS-1-3); read through {@link #hra(String, String)}
     * @param ccaAmount CCA amount by city class; empty map when the CCA rate set has no version in
     *     force
     * @param ccaSeeded whether a CCA version is in force at all
     * @param componentIds pay_component id by legacy pay code (not retired, in force)
     * @param rateSetVersions rate_set_id of each rate set in force, by code (provenance)
     * @param versionSetHash SHA-256 of the versions used (computation_run.rule_version_set_hash)
     */
    public record Snapshot(
            LocalDate asOf,
            Map<String, BigDecimal> daPercent,
            Map<String, BigDecimal> hraPercent,
            Map<String, BigDecimal> ccaAmount,
            boolean ccaSeeded,
            Map<Integer, Long> componentIds,
            Map<String, Long> rateSetVersions,
            String versionSetHash,
            AllowanceConfiguration.Allowances allowances) {

        public Snapshot {
            allowances = allowances == null ? AllowanceConfiguration.Allowances.none() : allowances;
        }

        /** A snapshot without the flag-driven allowances configured (plan 16 phase 16d). */
        public Snapshot(
                LocalDate asOf,
                Map<String, BigDecimal> daPercent,
                Map<String, BigDecimal> hraPercent,
                Map<String, BigDecimal> ccaAmount,
                boolean ccaSeeded,
                Map<Integer, Long> componentIds,
                Map<String, Long> rateSetVersions,
                String versionSetHash) {
            this(
                    asOf,
                    daPercent,
                    hraPercent,
                    ccaAmount,
                    ccaSeeded,
                    componentIds,
                    rateSetVersions,
                    versionSetHash,
                    AllowanceConfiguration.Allowances.none());
        }

        /**
         * SRS-1-3 (US-GAP-41): the HRA % of a city class for the pay pattern. CDA-pattern staff
         * have their own rule (HRA_CDA); FND staff follow the CDA pattern, as for DA (FRS v0.4 Sec
         * 7, DDL rate_set_line). Empty when the rule has no rate for the class: never another
         * pattern's rate.
         */
        public Optional<BigDecimal> hra(String payPattern, String cityClass) {
            if (cityClass == null) {
                return Optional.empty();
            }
            boolean cda = "CDA".equals(payPattern) || "FND".equals(payPattern);
            return Optional.ofNullable(hraPercent.get(cda ? CDA_HRA + cityClass : cityClass));
        }

        /** DA % of a pay pattern: FND staff follow the CDA pattern (FRS v0.4 Sec 7). */
        public Optional<BigDecimal> da(String payPattern) {
            if (payPattern == null) {
                return Optional.empty();
            }
            String pattern = "FND".equals(payPattern) ? "CDA" : payPattern;
            return Optional.ofNullable(daPercent.get(pattern));
        }
    }

    /**
     * One M-07 pay code as other modules need it.
     *
     * @param suppExcluded {@code supp_excluded_flag}: not payable through I-23 (BR-PEN2-003)
     */
    public record Component(
            Long componentId,
            int legacyPayCode,
            String name,
            boolean suppExcluded,
            boolean taxable) {}

    private final ConfigurationRepositories.RateSets sets;
    private final ConfigurationRepositories.RateSetLines lines;
    private final ConfigurationRepositories.PayComponents components;
    private final AllowanceConfiguration allowances;

    @org.springframework.beans.factory.annotation.Autowired
    public PayConfiguration(
            ConfigurationRepositories.RateSets sets,
            ConfigurationRepositories.RateSetLines lines,
            ConfigurationRepositories.PayComponents components,
            AllowanceConfiguration allowances) {
        this.sets = sets;
        this.lines = lines;
        this.components = components;
        this.allowances = allowances;
    }

    /** Without the allowance masters (unit tests of the M-04 snapshot). */
    public PayConfiguration(
            ConfigurationRepositories.RateSets sets,
            ConfigurationRepositories.RateSetLines lines,
            ConfigurationRepositories.PayComponents components) {
        this(sets, lines, components, null);
    }

    /** The pay code, whatever its effective dates (a code is never re-issued). */
    @Transactional(readOnly = true)
    public Optional<Component> component(int legacyPayCode) {
        return components.findByLegacyPayCode(legacyPayCode).map(PayConfiguration::component);
    }

    /** The pay code with this {@code component_id}. */
    @Transactional(readOnly = true)
    public Optional<Component> componentById(Long componentId) {
        return components.findById(componentId).map(PayConfiguration::component);
    }

    private static Component component(PayComponent c) {
        return new Component(
                c.getComponentId(),
                c.getLegacyPayCode(),
                c.getComponentName(),
                c.isSuppExcludedFlag(),
                // PARTLY still attracts TDS on its taxable part.
                !"N".equals(c.getTaxableFlag()));
    }

    @Transactional(readOnly = true)
    public Snapshot staffConfiguration(LocalDate asOf) {
        Map<String, RateSet> inForce = new TreeMap<>();
        for (RateSet set :
                sets.findByRateSetCodeInOrderByRateSetCodeAscVersionNoDesc(STAFF_RATE_SETS)) {
            if (set.isInForceOn(asOf) && !inForce.containsKey(set.getRateSetCode())) {
                inForce.put(set.getRateSetCode(), set);
            }
        }
        Map<Long, String> codeById = new HashMap<>();
        inForce.values().forEach(s -> codeById.put(s.getRateSetId(), s.getRateSetCode()));
        Map<String, BigDecimal> da = new HashMap<>();
        Map<String, BigDecimal> hra = new HashMap<>();
        Map<String, BigDecimal> cca = new HashMap<>();
        if (!codeById.isEmpty()) {
            for (RateSetLine line : lines.findByRateSetIdIn(codeById.keySet())) {
                String set = codeById.get(line.getRateSetId());
                switch (set) {
                    case "DA_IDA" -> da.put("IDA", line.getRateValue());
                    case "DA_CDA" -> da.put("CDA", line.getRateValue());
                    case "HRA" -> {
                        if (line.getCityClass() != null)
                            hra.put(line.getCityClass(), line.getRateValue());
                    }
                    case "HRA_CDA" -> {
                        if (line.getCityClass() != null)
                            hra.put(CDA_HRA + line.getCityClass(), line.getRateValue());
                    }
                    case "CCA" -> {
                        if (line.getCityClass() != null)
                            cca.put(line.getCityClass(), line.getRateValue());
                    }
                    default -> {}
                }
            }
        }
        Map<Integer, Long> ids = new HashMap<>();
        for (PayComponent c : components.findAllByOrderByLegacyPayCode()) {
            boolean current =
                    !c.isIsRetired()
                            && !asOf.isBefore(c.getEffectiveFrom())
                            && (c.getEffectiveTo() == null || !asOf.isAfter(c.getEffectiveTo()));
            if (current) {
                ids.put(c.getLegacyPayCode(), c.getComponentId());
            }
        }
        Map<String, Long> versions = new TreeMap<>();
        inForce.forEach((code, set) -> versions.put(code, set.getRateSetId()));
        // Plan 16 phase 16d (SRS-1-4): the flag-driven allowances pinned with the rates; their
        // versions enter the hash, so a change of any of them stops a resume.
        AllowanceConfiguration.Allowances flagged =
                allowances == null ? AllowanceConfiguration.Allowances.none() : allowances.on(asOf);
        return new Snapshot(
                asOf,
                Map.copyOf(da),
                Map.copyOf(hra),
                Map.copyOf(cca),
                inForce.containsKey("CCA"),
                Map.copyOf(ids),
                Map.copyOf(versions),
                hash(
                        versions
                                + "|"
                                + new TreeMap<>(ids)
                                + (flagged.hash().isEmpty() ? "" : "|" + flagged.hash())),
                flagged);
    }

    private static String hash(String value) {
        return SecureTokens.sha256Hex(value);
    }
}
