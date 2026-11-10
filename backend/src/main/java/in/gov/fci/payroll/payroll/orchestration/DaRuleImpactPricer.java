package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.configuration.api.RuleImpactPricing;
import in.gov.fci.payroll.configuration.api.SimulationPopulation;
import in.gov.fci.payroll.payroll.business.PayRules;
import in.gov.fci.payroll.payroll.business.SimulationRules;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * BR-RULE-006 (M-08 simulation), SRS-1-2: prices a proposed DA rate with the engine C-09 uses
 * ({@link SimulationEngine}, the same read and rules as a run), on the sampled members, in the
 * month the proposal starts. The rate is a what-if only: pay keeps reading the M-04 rate set
 * (PayConfiguration), which is why this writes nothing and why a run's result never changes
 * (BR-CYC-011..017). Other rule keys are not priced: PENDING 06-impact.
 *
 * <p>Runs in its own read-only transaction, so a member's missing setting cannot mark the caller's
 * transaction (which keeps the simulation row) for rollback.
 */
@Component
public class DaRuleImpactPricer implements RuleImpactPricing {

    private static final Map<String, String> DA_KEYS =
            Map.of("DA.IDA.RATE", "IDA", "DA.CDA.RATE", "CDA");

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final SimulationEngine engine;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public DaRuleImpactPricer(
            SimulationEngine engine, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.engine = engine;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Priced> price(
            String ruleKey,
            BigDecimal proposed,
            LocalDate effectiveFrom,
            List<SimulationPopulation.Member> members) {
        String pattern = DA_KEYS.get(ruleKey);
        if (pattern == null || proposed == null || members.isEmpty()) {
            return Optional.empty();
        }
        YearMonth month = YearMonth.from(effectiveFrom);
        List<Long> ids =
                members.stream().map(SimulationPopulation.Member::employeeId).sorted().toList();
        List<Long> locations =
                members.stream()
                        .map(SimulationPopulation.Member::locationId)
                        .distinct()
                        .sorted()
                        .toList();
        SimulationEngine.Outcome outcome =
                engine.runAt(
                        locations,
                        new SimulationEngine.Request(
                                0L,
                                month,
                                ids,
                                SimulationRules.WhatIf.DA_RATE,
                                "IDA".equals(pattern) ? proposed : null,
                                "CDA".equals(pattern) ? proposed : null));
        SimulationEngine.Side before = outcome.asIs();
        SimulationEngine.Side after = outcome.whatIf();
        long negative =
                outcome.members().stream()
                        .map(SimulationEngine.MemberResult::whatIfNet)
                        .filter(Objects::nonNull)
                        .filter(net -> net.signum() < 0)
                        .count();
        long retro =
                Math.max(
                        0,
                        ChronoUnit.MONTHS.between(
                                month, YearMonth.from(clock.instant().atZone(INDIA))));
        return Optional.of(
                new Priced(
                        before.computed(),
                        before.skipped().values().stream().mapToLong(Long::longValue).sum(),
                        amount(before, PayRules.DA),
                        amount(after, PayRules.DA),
                        before.net(),
                        after.net(),
                        statutory(before, after),
                        negative,
                        retro));
    }

    private static BigDecimal amount(SimulationEngine.Side side, int payCode) {
        return side.components().getOrDefault(payCode, BigDecimal.ZERO);
    }

    /**
     * The change of each statutory deduction, by its code in the STATUTORY_DEDUCTION list: the list
     * names the pay code of each (the same read as the tax registers).
     */
    private Map<String, BigDecimal> statutory(
            SimulationEngine.Side before, SimulationEngine.Side after) {
        Map<String, BigDecimal> out = new TreeMap<>();
        jdbc.query(
                "SELECT v.value_code, v.numeric_attribute FROM reference_value v JOIN"
                        + " reference_list l ON l.list_id = v.list_id WHERE l.list_code ="
                        + " 'STATUTORY_DEDUCTION' AND v.numeric_attribute IS NOT NULL",
                new MapSqlParameterSource(),
                rs -> {
                    int payCode = rs.getInt(2);
                    out.put(
                            rs.getString(1),
                            amount(after, payCode).subtract(amount(before, payCode)));
                });
        return out;
    }
}
