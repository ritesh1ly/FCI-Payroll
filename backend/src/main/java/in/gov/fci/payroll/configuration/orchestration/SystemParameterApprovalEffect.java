package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.MasterImages;
import in.gov.fci.payroll.configuration.business.model.RuleVersion;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The checker's decision on an A-07 change: the approved version takes effect from the decision day
 * (or the free day it was dated for) and the version in force ends the day before; a rejected or
 * returned version is REJECTED and never takes effect. At most one approved version per date
 * (BR-RULE-002, also trg_rule_version_temporal): the old version is ended and flushed first.
 */
@Component
public class SystemParameterApprovalEffect {

    private final ConfigurationRepositories.RuleVersions versions;
    private final SystemParameters systemParameters;
    private final MasterAudit audit;

    public SystemParameterApprovalEffect(
            ConfigurationRepositories.RuleVersions versions,
            SystemParameters systemParameters,
            MasterAudit audit) {
        this.versions = versions;
        this.systemParameters = systemParameters;
        this.audit = audit;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        // M-08 rule versions share the table: only A-07's own decisions are applied here.
        if (!decision.concerns(SystemParameterService.ENTITY)
                || !SystemParameterService.MODULE.equals(decision.moduleCode())) {
            return;
        }
        versions.findByApprovalId(decision.approvalId())
                .ifPresent(
                        proposed -> {
                            if (!decision.approved()) {
                                proposed.reject();
                                versions.save(proposed);
                                systemParameters.evict();
                                return;
                            }
                            LocalDate from = systemParameters.today();
                            List<RuleVersion> open =
                                    versions
                                            .findByRuleIdOrderByVersionNoDesc(proposed.getRuleId())
                                            .stream()
                                            .filter(
                                                    v ->
                                                            v.getState()
                                                                            == RuleVersion.State
                                                                                    .APPROVED
                                                                    && v.getEffectiveTo() == null)
                                            .toList();
                            for (RuleVersion previous : open) {
                                if (!previous.getEffectiveFrom().isBefore(from)) {
                                    from = previous.getEffectiveFrom().plusDays(1);
                                }
                            }
                            if (proposed.getEffectiveFrom().isAfter(from)) {
                                from = proposed.getEffectiveFrom();
                            }
                            Map<String, String> before =
                                    open.isEmpty() ? null : MasterImages.ruleVersion(open.get(0));
                            for (RuleVersion previous : open) {
                                previous.endBefore(from);
                                versions.save(previous);
                            }
                            versions.flush();
                            proposed.approve(from);
                            versions.save(proposed);
                            // SRS-20-2: the applied change, field by field, in this transaction.
                            audit.applied(
                                    decision,
                                    SystemParameterService.ENTITY,
                                    codeOf(proposed.getRuleId()),
                                    SystemParameterService.MODULE,
                                    from,
                                    before,
                                    MasterImages.ruleVersion(proposed),
                                    open.stream().map(RuleVersion::getEffectiveTo).toList(),
                                    decision.reason());
                            systemParameters.evict();
                        });
    }

    /** The setting's code (the audit key of its rows, as on A-07). */
    private String codeOf(Long ruleId) {
        return systemParameters.definitions().stream()
                .filter(d -> d.ruleId().equals(ruleId))
                .map(SystemParameters.Definition::code)
                .findFirst()
                .orElse(String.valueOf(ruleId));
    }
}
