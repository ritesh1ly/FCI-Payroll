package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.business.MasterImages;
import in.gov.fci.payroll.configuration.business.model.GlAccountMapping;
import in.gov.fci.payroll.configuration.business.model.OtherParameter;
import in.gov.fci.payroll.configuration.business.model.RateSet;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The final decision on a master change (M-04 / M-22 rate set, M-23 other parameter, M-24 GL
 * mapping), applied in the decision's transaction: an approved version takes effect from its date
 * and every earlier approved version still open ends the day before (versions never overlap); a
 * rejected or returned proposal never takes effect (a rate set is marked REJECTED; the other two
 * have no state column and stay inert under their rejected approval).
 */
@Component
public class MasterApprovalEffects {

    private final ConfigurationRepositories.RateSets sets;
    private final ConfigurationRepositories.OtherParameters parameters;
    private final ConfigurationRepositories.GlMappings mappings;
    private final ConfigurationRepositories.RateSetLines lines;
    private final ConfigurationRepositories.PayComponents components;
    private final ApprovalStatuses statuses;
    private final MasterAudit audit;
    private final RateMasterService rates;

    public MasterApprovalEffects(
            ConfigurationRepositories.RateSets sets,
            ConfigurationRepositories.OtherParameters parameters,
            ConfigurationRepositories.GlMappings mappings,
            ConfigurationRepositories.RateSetLines lines,
            ConfigurationRepositories.PayComponents components,
            ApprovalStatuses statuses,
            MasterAudit audit,
            RateMasterService rates) {
        this.sets = sets;
        this.parameters = parameters;
        this.mappings = mappings;
        this.lines = lines;
        this.components = components;
        this.statuses = statuses;
        this.audit = audit;
        this.rates = rates;
    }

    private Map<String, String> rateImage(RateSet set) {
        return MasterImages.rateSet(set, lines.findByRateSetIdIn(List.of(set.getRateSetId())));
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (decision.concerns(RateMasterService.ENTITY)) {
            rateSet(decision);
        } else if (decision.concerns(OtherParameterService.ENTITY)) {
            otherParameter(decision);
        } else if (decision.concerns(GlMappingService.ENTITY)) {
            glMapping(decision);
        }
    }

    private void rateSet(ApprovalDecided decision) {
        sets.findByApprovalId(decision.approvalId())
                .ifPresent(
                        proposed -> {
                            if (!decision.approved()) {
                                proposed.reject();
                                sets.save(proposed);
                                return;
                            }
                            Map<String, String> before = null;
                            List<LocalDate> ended = new ArrayList<>();
                            for (RateSet previous :
                                    sets.findByRateSetCodeOrderByVersionNoDesc(
                                            proposed.getRateSetCode())) {
                                if (previous.isApproved()
                                        && previous.getEffectiveTo() == null
                                        && previous.getEffectiveFrom()
                                                .isBefore(proposed.getEffectiveFrom())) {
                                    if (before == null) {
                                        before = rateImage(previous);
                                    }
                                    previous.endBefore(proposed.getEffectiveFrom());
                                    sets.save(previous);
                                    ended.add(previous.getEffectiveTo());
                                }
                            }
                            sets.flush();
                            proposed.approve();
                            sets.save(proposed);
                            audit.applied(
                                    decision,
                                    RateMasterService.ENTITY,
                                    proposed.getRateSetCode(),
                                    rates.screenOf(proposed.getRateSetCode())
                                            .map(RateMasterService.Screen::code)
                                            .orElse(RateMasterService.Screen.SPECIAL.code()),
                                    proposed.getEffectiveFrom(),
                                    before,
                                    rateImage(proposed),
                                    ended,
                                    decision.reason());
                        });
    }

    private void otherParameter(ApprovalDecided decision) {
        if (!decision.approved()) {
            return;
        }
        parameters
                .findByApprovalId(decision.approvalId())
                .ifPresent(
                        proposed -> {
                            List<OtherParameter> versions =
                                    parameters.findByParameterCodeOrderByVersionNoDesc(
                                            proposed.getParameterCode());
                            Map<Long, ApprovalStatus> status =
                                    statuses.of(
                                            versions.stream()
                                                    .map(OtherParameter::getApprovalId)
                                                    .filter(Objects::nonNull)
                                                    .toList());
                            Map<String, String> before = null;
                            List<LocalDate> ended = new ArrayList<>();
                            for (OtherParameter previous : versions) {
                                if (!previous.getParameterId().equals(proposed.getParameterId())
                                        && status.get(previous.getApprovalId())
                                                == ApprovalStatus.APPROVED
                                        && previous.getEffectiveTo() == null
                                        && previous.getEffectiveFrom()
                                                .isBefore(proposed.getEffectiveFrom())) {
                                    if (before == null) {
                                        before = MasterImages.otherParameter(previous);
                                    }
                                    previous.endBefore(proposed.getEffectiveFrom());
                                    parameters.save(previous);
                                    ended.add(previous.getEffectiveTo());
                                }
                            }
                            parameters.flush();
                            audit.applied(
                                    decision,
                                    OtherParameterService.ENTITY,
                                    proposed.getParameterCode(),
                                    OtherParameterService.SCREEN,
                                    proposed.getEffectiveFrom(),
                                    before,
                                    MasterImages.otherParameter(proposed),
                                    ended,
                                    decision.reason());
                        });
    }

    private void glMapping(ApprovalDecided decision) {
        if (!decision.approved()) {
            return;
        }
        mappings.findByApprovalId(decision.approvalId())
                .ifPresent(
                        proposed -> {
                            List<GlAccountMapping> versions =
                                    mappings.findByComponentIdOrderByVersionNoDesc(
                                            proposed.getComponentId());
                            Map<Long, ApprovalStatus> status =
                                    statuses.of(
                                            versions.stream()
                                                    .map(GlAccountMapping::getApprovalId)
                                                    .filter(Objects::nonNull)
                                                    .toList());
                            Map<String, String> before = null;
                            List<LocalDate> ended = new ArrayList<>();
                            for (GlAccountMapping previous : versions) {
                                if (!previous.getMappingId().equals(proposed.getMappingId())
                                        && Objects.equals(
                                                previous.getOrgUnitId(), proposed.getOrgUnitId())
                                        && status.get(previous.getApprovalId())
                                                == ApprovalStatus.APPROVED
                                        && previous.getEffectiveTo() == null
                                        && previous.getEffectiveFrom()
                                                .isBefore(proposed.getEffectiveFrom())) {
                                    if (before == null) {
                                        before = MasterImages.glMapping(previous);
                                    }
                                    previous.endBefore(proposed.getEffectiveFrom());
                                    mappings.save(previous);
                                    ended.add(previous.getEffectiveTo());
                                }
                            }
                            mappings.flush();
                            audit.applied(
                                    decision,
                                    GlMappingService.ENTITY,
                                    payCodeOf(proposed.getComponentId()),
                                    GlMappingService.SCREEN,
                                    proposed.getEffectiveFrom(),
                                    before,
                                    MasterImages.glMapping(proposed),
                                    ended,
                                    decision.reason());
                        });
    }

    /** The legacy pay code of a component (the M-24 key, as on the screen). */
    private String payCodeOf(Long componentId) {
        return components
                .findById(componentId)
                .map(c -> String.valueOf(c.getLegacyPayCode()))
                .orElse(String.valueOf(componentId));
    }
}
