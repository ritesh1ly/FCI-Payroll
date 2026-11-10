package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.configuration.business.RuleProposals;
import in.gov.fci.payroll.configuration.business.RuleValueShape;
import in.gov.fci.payroll.configuration.business.SlabBands;
import in.gov.fci.payroll.configuration.business.model.RuleDefinition;
import in.gov.fci.payroll.configuration.business.model.RuleVersion;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Everything an M-08 version proposal must satisfy, checked when the Rule Administrator submits it
 * and again, under the rule's lock, when the Rule Approver approves it (the grammar, the circular
 * or the rule's versions may have changed in between; SRS-G-16 "block Checker approval without
 * it"):
 *
 * <ul>
 *   <li>the value fits the rule type's {@link RuleValueShape} (SRS-19-1, SRS-19-5);
 *   <li>a formula uses only the grammar in force and yields what the type needs (SRS-19-15);
 *   <li>the dates are free: after every approved version, on a day no version holds (SRS-19-12, KB
 *       RE-1 / RE-2);
 *   <li>a rounding setting names an active ROUNDING rule (SRS-19-1: rounding is a rule setting);
 *   <li>the linked circular is Active (SRS-19-12, SRS-19-17). The SRS makes the link mandatory for
 *       every version (M-08 "Linked Circular / Order", mandatory); the order gate of rule types
 *       marked "order required" (SRS-G-16) is therefore met by every version.
 * </ul>
 */
@Component
class RuleProposalChecks {

    private final ConfigurationRepositories.Rules rules;
    private final RuleEngine engine;
    private final Circulars circulars;

    RuleProposalChecks(
            ConfigurationRepositories.Rules rules, RuleEngine engine, Circulars circulars) {
        this.rules = rules;
        this.engine = engine;
        this.circulars = circulars;
    }

    /**
     * @throws FciException naming the first fault
     */
    void check(
            RuleDefinition rule,
            List<RuleVersion> history,
            RuleCatalogueService.VersionProposal p) {
        RuleValueShape shape =
                RuleValueShape.of(rule.getTypeCode())
                        .orElseThrow(
                                () ->
                                        new FciException(
                                                ApiErrorCode.RULE_READ_ONLY,
                                                new Object[] {rule.getKey()},
                                                List.of(),
                                                null));
        List<SlabBands.Band> bands = p.bands().stream().map(RuleCatalogueService::pure).toList();
        RuleProposals.requireValue(shape, new RuleProposals.Value(p.value(), p.formula(), bands));
        if (shape.isFormula()) {
            engine.checkFormula(p.formula(), shape == RuleValueShape.CONDITION_FORMULA);
        }
        RuleProposals.requireFreeDates(
                p.effectiveFrom(), p.effectiveTo(), RuleCatalogueService.dated(history));
        if (p.roundingRuleKey() != null
                && rules.findByKey(p.roundingRuleKey())
                        .filter(r -> "ROUNDING".equals(r.getTypeCode()) && r.isActive())
                        .isEmpty()) {
            throw new FciException(
                    ApiErrorCode.RULE_VALUE_INVALID,
                    new Object[] {"no active ROUNDING rule " + p.roundingRuleKey()},
                    List.of(),
                    null);
        }
        if (p.circularId() == null) {
            throw new FciException(
                    ApiErrorCode.RULE_VALUE_INVALID,
                    new Object[] {"the linked circular or order is mandatory"},
                    List.of(),
                    null);
        }
        circulars.requireActive(p.circularId());
    }
}
