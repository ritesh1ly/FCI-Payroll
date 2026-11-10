package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.configuration.api.RuleVersionChanged;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.MasterImages;
import in.gov.fci.payroll.configuration.business.RuleProposals;
import in.gov.fci.payroll.configuration.business.SlabBands;
import in.gov.fci.payroll.configuration.business.model.RuleDefinition;
import in.gov.fci.payroll.configuration.business.model.RuleSlabBand;
import in.gov.fci.payroll.configuration.business.model.RuleVersion;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The Rule Approver's decision on an M-08 change (SRS-19-4, SRS-19-6, SRS-19-12, SRS-19-17; KB
 * RE-2, RE-4, RE-8), applied in the decision's transaction under the rule's lock, so a failure
 * rolls the decision back and a proposal and a decision on one rule serialise (RE-2):
 *
 * <ul>
 *   <li><b>Version approved</b>: the proposal is checked again ({@link RuleProposalChecks}: the
 *       grammar, the circular or the versions may have changed since submission); the approved
 *       version it supersedes ends the day before (flushed first: at most one approved version per
 *       date, trg_rule_version_temporal); the version is written from its stated date, a back-dated
 *       one included (SRS-19-16 arrears); the circular is linked (Active, or the approval is
 *       blocked: SRS-G-16); the change is audited field by field.
 *   <li><b>End date approved</b>: the latest approved version, still the same one, ends on that day
 *       with no successor; legitimate, and recorded as RULE_END_NO_SUCCESSOR in its audit rows.
 *   <li><b>Rejected or returned</b>: nothing was written (the proposal lived in the approval item,
 *       plan 06 D-7), so nothing is undone.
 * </ul>
 *
 * Each applied change publishes {@link RuleVersionChanged}.
 */
@Component
public class RuleCatalogueApprovalEffect {

    static final String END_EVENT = "RULE_END_NO_SUCCESSOR";

    private final RuleCatalogueService catalogue;
    private final RuleProposalChecks checks;
    private final ConfigurationRepositories.RuleVersions versions;
    private final ConfigurationRepositories.RuleSlabBands bands;
    private final Circulars circulars;
    private final MasterAudit audit;
    private final SystemParameters settings;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public RuleCatalogueApprovalEffect(
            RuleCatalogueService catalogue,
            RuleProposalChecks checks,
            ConfigurationRepositories.RuleVersions versions,
            ConfigurationRepositories.RuleSlabBands bands,
            Circulars circulars,
            MasterAudit audit,
            SystemParameters settings,
            ApplicationEventPublisher events,
            ObjectMapper json) {
        this.catalogue = catalogue;
        this.checks = checks;
        this.versions = versions;
        this.bands = bands;
        this.circulars = circulars;
        this.audit = audit;
        this.settings = settings;
        this.events = events;
        this.json = json;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        // A-07 settings share the rule_version table: only M-08's own decisions are applied here.
        if (!RuleCatalogueService.MODULE.equals(decision.moduleCode()) || !decision.approved()) {
            return;
        }
        if (decision.concerns(RuleCatalogueService.ENTITY)) {
            applyVersion(
                    decision,
                    json.readValue(decision.payload(), RuleCatalogueService.VersionProposal.class));
        } else if (decision.concerns(RuleCatalogueService.END_ENTITY)) {
            applyEnd(
                    decision,
                    json.readValue(decision.payload(), RuleCatalogueService.EndProposal.class));
        }
    }

    private void applyVersion(ApprovalDecided decision, RuleCatalogueService.VersionProposal p) {
        RuleDefinition rule = catalogue.lockMaintained(p.ruleKey());
        List<RuleVersion> history = versions.findByRuleIdOrderByVersionNoDesc(rule.getId());
        checks.check(rule, history, p);
        LocalDate from = p.effectiveFrom();
        // The approved versions still in force on or after the new start: each ends the day before.
        List<RuleVersion> superseded = new ArrayList<>();
        for (RuleVersion v : history) {
            if (v.getState() == RuleVersion.State.APPROVED
                    && (v.getEffectiveTo() == null || !v.getEffectiveTo().isBefore(from))) {
                superseded.add(v);
            }
        }
        Map<String, String> before =
                superseded.isEmpty()
                        ? null
                        : MasterImages.ruleVersion(superseded.get(0), bandsOf(superseded.get(0)));
        for (RuleVersion previous : superseded) {
            previous.endBefore(from);
            versions.save(previous);
        }
        versions.flush();
        RuleVersion approved =
                versions.save(
                        RuleVersion.approved(
                                rule.getId(),
                                RuleCatalogueService.nextVersionNo(history),
                                from,
                                p.effectiveTo(),
                                p.value(),
                                p.formula(),
                                p.roundingRuleKey(),
                                p.circularId(),
                                p.proposedBy(),
                                decision.approvalId(),
                                p.proposedAt()));
        List<SlabBands.Band> own = p.bands().stream().map(RuleCatalogueService::pure).toList();
        for (SlabBands.Band b : own) {
            bands.save(new RuleSlabBand(approved.getId(), b));
        }
        circulars.link(
                decision.checkerLoginId(),
                p.circularId(),
                Circulars.LinkType.RULE_VERSION,
                RuleCatalogueService.ENTITY,
                String.valueOf(approved.getId()),
                RuleCatalogueService.MODULE);
        audit.applied(
                decision,
                RuleCatalogueService.ENTITY,
                rule.getKey(),
                RuleCatalogueService.MODULE,
                from,
                before,
                MasterImages.ruleVersion(approved, own),
                superseded.stream().map(RuleVersion::getEffectiveTo).toList(),
                decision.reason());
        events.publishEvent(changed(RuleVersionChanged.Kind.APPROVED, rule, approved));
    }

    private void applyEnd(ApprovalDecided decision, RuleCatalogueService.EndProposal p) {
        RuleDefinition rule = catalogue.lockMaintained(p.ruleKey());
        List<RuleVersion> history = versions.findByRuleIdOrderByVersionNoDesc(rule.getId());
        // Checked again under the lock: still the latest approved version, still a valid day.
        RuleProposals.Dated latest =
                RuleProposals.requireEnd(p.endOn(), RuleCatalogueService.dated(history));
        if (!latest.id().equals(p.versionId())) {
            // Another version became the latest since the end was proposed: refused, not merged.
            throw new FciException(
                    ApiErrorCode.RULE_VERSION_CONFLICT,
                    new Object[] {latest.versionNo(), latest.from()},
                    List.of(),
                    null);
        }
        RuleVersion ended =
                history.stream()
                        .filter(v -> v.getId().equals(latest.id()))
                        .findFirst()
                        .orElseThrow();
        List<SlabBands.Band> own = bandsOf(ended);
        Map<String, String> before = withEvent(MasterImages.ruleVersion(ended, own), null);
        ended.endOn(p.endOn());
        versions.save(ended);
        audit.applied(
                decision,
                RuleCatalogueService.ENTITY,
                rule.getKey(),
                RuleCatalogueService.MODULE,
                ended.getEffectiveFrom(),
                before,
                withEvent(MasterImages.ruleVersion(ended, own), END_EVENT),
                List.of(),
                decision.reason());
        events.publishEvent(changed(RuleVersionChanged.Kind.ENDED, rule, ended));
    }

    private RuleVersionChanged changed(
            RuleVersionChanged.Kind kind, RuleDefinition rule, RuleVersion v) {
        return new RuleVersionChanged(
                kind,
                rule.getKey(),
                rule.getTypeCode(),
                v.getId(),
                v.getVersionNo(),
                v.getEffectiveFrom(),
                v.getEffectiveTo(),
                settings.today());
    }

    private static Map<String, String> withEvent(Map<String, String> image, String event) {
        Map<String, String> out = new LinkedHashMap<>(image);
        out.put("end_event", event);
        return out;
    }

    private List<SlabBands.Band> bandsOf(RuleVersion v) {
        return bands.findByRuleVersionIdIn(List.of(v.getId())).stream()
                .map(RuleSlabBand::band)
                .toList();
    }
}
