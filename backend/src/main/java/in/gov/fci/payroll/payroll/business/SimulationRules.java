package in.gov.fci.payroll.payroll.business;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The rules of a C-09 General (Simulation) Run (plan 05 phase 05i), as pure functions.
 *
 * <ul>
 *   <li>SRS-15-7 / SRS C-09: the population is the whole office instance, one member or a custom
 *       member list, up to the configured maximum selection; above the configured interactive limit
 *       the run is queued as a background job.
 *   <li>SRS C-09 what-if (plan D-17): as-is preview, or a DA rate change applied to that simulation
 *       only (the rate set is never changed); pay-scale revision is not offered yet (PENDING
 *       05i-scale).
 *   <li>BR-CYC-007: the result is a preview, never posted; only the run record and its summary are
 *       kept (results_retained 'N').
 * </ul>
 */
public final class SimulationRules {

    /** SRS C-09 Population filter. */
    public enum Population {
        OFFICE,
        MEMBER,
        MEMBER_LIST
    }

    /** SRS C-09 What-if filter (plan D-17). */
    public enum WhatIf {
        AS_IS,
        DA_RATE
    }

    /** SRS C-09 Run Mode tile. */
    public enum Mode {
        INTERACTIVE,
        BACKGROUND
    }

    /** DDL {@code rate_set_line.rate_value} NUMBER(14,4). */
    private static final BigDecimal RATE_LIMIT = new BigDecimal("9999999999.9999");

    private static final int EMPLOYEE_NUMBER_LENGTH = 20;

    private SimulationRules() {}

    /**
     * What is wrong with a member selection (OFFICE takes none): EMPTY, ONE_ONLY (a single member
     * names exactly one), TOO_MANY (beyond the configured maximum), DUPLICATE, NOT_VALID (an
     * employee number blank, longer than 20 or not printable ASCII).
     */
    public static Optional<String> selectionDefect(
            Population population, List<String> members, long maxSelection) {
        if (population == Population.OFFICE) {
            return members == null || members.isEmpty()
                    ? Optional.empty()
                    : Optional.of("NOT_VALID");
        }
        if (members == null || members.isEmpty()) {
            return Optional.of("EMPTY");
        }
        if (population == Population.MEMBER && members.size() != 1) {
            return Optional.of("ONE_ONLY");
        }
        if (members.size() > maxSelection) {
            return Optional.of("TOO_MANY");
        }
        Set<String> seen = new HashSet<>();
        for (String member : members) {
            if (!validEmployeeNumber(member)) {
                return Optional.of("NOT_VALID");
            }
            if (!seen.add(member.strip())) {
                return Optional.of("DUPLICATE");
            }
        }
        return Optional.empty();
    }

    /** The office population counts against the maximum too (SRS C-09 Population Selection). */
    public static boolean withinMaximum(long population, long maxSelection) {
        return population <= maxSelection;
    }

    /**
     * What is wrong with a DA what-if: MISSING (neither pattern given), NOT_POSITIVE, TOO_PRECISE
     * (more than four decimals), TOO_LARGE (beyond Numeric 14,4). AS_IS takes none: NOT_VALID.
     */
    public static Optional<String> whatIfDefect(WhatIf whatIf, BigDecimal ida, BigDecimal cda) {
        if (whatIf == WhatIf.AS_IS) {
            return ida == null && cda == null ? Optional.empty() : Optional.of("NOT_VALID");
        }
        if (ida == null && cda == null) {
            return Optional.of("MISSING");
        }
        for (BigDecimal rate : new BigDecimal[] {ida, cda}) {
            if (rate == null) {
                continue;
            }
            if (rate.signum() <= 0) {
                return Optional.of("NOT_POSITIVE");
            }
            if (rate.stripTrailingZeros().scale() > 4) {
                return Optional.of("TOO_PRECISE");
            }
            if (rate.compareTo(RATE_LIMIT) > 0) {
                return Optional.of("TOO_LARGE");
            }
        }
        return Optional.empty();
    }

    /** SRS-15-7: above the interactive limit the run goes to the background. */
    public static Mode mode(long population, long interactiveLimit) {
        return population > interactiveLimit ? Mode.BACKGROUND : Mode.INTERACTIVE;
    }

    /**
     * The configuration with the what-if DA rates in place of those in force (a pattern not given
     * keeps its rate). The version-set hash is marked, so the snapshot can never pass for the one
     * in force.
     */
    public static PayConfiguration.Snapshot override(
            PayConfiguration.Snapshot base, BigDecimal ida, BigDecimal cda) {
        Map<String, BigDecimal> da = new HashMap<>(base.daPercent());
        if (ida != null) {
            da.put("IDA", ida);
        }
        if (cda != null) {
            da.put("CDA", cda);
        }
        return new PayConfiguration.Snapshot(
                base.asOf(),
                Map.copyOf(da),
                base.hraPercent(),
                base.ccaAmount(),
                base.ccaSeeded(),
                base.componentIds(),
                base.rateSetVersions(),
                base.versionSetHash() + "|WHATIF");
    }

    private static boolean validEmployeeNumber(String value) {
        if (value == null || value.isBlank() || value.strip().length() > EMPLOYEE_NUMBER_LENGTH) {
            return false;
        }
        return value.chars().allMatch(c -> c >= 0x20 && c <= 0x7E);
    }

    /**
     * The running totals of one side (as-is or what-if) of a simulation: members computed, the
     * stopped (no pay) and the skipped (a data or configuration gap, by reason), the totals and the
     * totals per pay code. Not thread safe; one per run.
     */
    public static final class Totals {
        private long computed;
        private long stopped;
        private final Map<String, Long> skipped = new TreeMap<>();
        private BigDecimal gross = BigDecimal.ZERO;
        private BigDecimal deductions = BigDecimal.ZERO;
        private BigDecimal net = BigDecimal.ZERO;
        private final Map<Integer, BigDecimal> byPayCode = new TreeMap<>();

        public void add(PayRules.Outcome outcome) {
            if (!PayRules.COMPUTED.equals(outcome.status())) {
                stopped++;
                return;
            }
            computed++;
            gross = gross.add(outcome.gross());
            deductions = deductions.add(outcome.deductions());
            net = net.add(outcome.net());
            for (PayRules.Line line : outcome.lines()) {
                byPayCode.merge(line.payCode(), line.amount(), BigDecimal::add);
            }
        }

        /** A member the rules could not compute (PayDataException): counted, never invented. */
        public void skip(String reason) {
            skipped.merge(reason, 1L, Long::sum);
        }

        public long computed() {
            return computed;
        }

        public long stopped() {
            return stopped;
        }

        public Map<String, Long> skipped() {
            return Map.copyOf(skipped);
        }

        public BigDecimal gross() {
            return gross;
        }

        public BigDecimal deductions() {
            return deductions;
        }

        public BigDecimal net() {
            return net;
        }

        public Map<Integer, BigDecimal> byPayCode() {
            return Map.copyOf(byPayCode);
        }
    }
}
