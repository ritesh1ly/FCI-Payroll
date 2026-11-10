package in.gov.fci.payroll.configuration.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * BR-RULE-006, FRS V0.5 §24.4 Step 2 (M-08 simulation): what a proposed rule value would do to pay,
 * in amounts, on the sampled members. Implemented by the payroll module, which owns the
 * computation; nothing is stored or posted, and no second rate is held: the proposed value is only
 * a what-if.
 */
public interface RuleImpactPricing {

    /** The amounts on the sample, before and after, for one month. */
    record Priced(
            long priced,
            long notComputed,
            BigDecimal currentComponent,
            BigDecimal proposedComponent,
            BigDecimal currentNet,
            BigDecimal proposedNet,
            Map<String, BigDecimal> statutoryDelta,
            long negativeNet,
            long retroPeriods) {}

    /**
     * @return the amounts, or empty for a rule the engines cannot price (the screen then shows the
     *     rule's values only, as before)
     */
    Optional<Priced> price(
            String ruleKey,
            BigDecimal proposed,
            LocalDate effectiveFrom,
            List<SimulationPopulation.Member> members);
}
