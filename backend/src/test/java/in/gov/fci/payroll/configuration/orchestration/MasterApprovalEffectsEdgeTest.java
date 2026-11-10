package in.gov.fci.payroll.configuration.orchestration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.business.model.GlAccountMapping;
import in.gov.fci.payroll.configuration.business.model.OtherParameter;
import in.gov.fci.payroll.configuration.business.model.RateSet;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * SRS-G-1 / SRS-G-6 (a master change takes effect only on approval, as a new dated version): when
 * several earlier versions are still open each is ended and the image recorded is the newest one; a
 * version starting on or after the approved one is left alone; a version awaiting approval or of
 * another office (a GL mapping) is not ended; a refusal applies nothing.
 */
class MasterApprovalEffectsEdgeTest {

    private static final LocalDate FROM = LocalDate.of(2027, 4, 1);
    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    private final ConfigurationRepositories.RateSets sets =
            mock(ConfigurationRepositories.RateSets.class);
    private final ConfigurationRepositories.OtherParameters parameters =
            mock(ConfigurationRepositories.OtherParameters.class);
    private final ConfigurationRepositories.GlMappings mappings =
            mock(ConfigurationRepositories.GlMappings.class);
    private final ConfigurationRepositories.RateSetLines lines =
            mock(ConfigurationRepositories.RateSetLines.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final MasterAudit audit = mock(MasterAudit.class);
    private final RateMasterService rates = mock(RateMasterService.class);

    private final MasterApprovalEffects effects =
            new MasterApprovalEffects(
                    sets,
                    parameters,
                    mappings,
                    lines,
                    mock(ConfigurationRepositories.PayComponents.class),
                    statuses,
                    audit,
                    rates);

    MasterApprovalEffectsEdgeTest() {
        when(lines.findByRateSetIdIn(any())).thenReturn(List.of());
        when(rates.screenOf(any())).thenReturn(Optional.empty());
    }

    private static ApprovalDecided decided(String entity, ApprovalStatus outcome) {
        return new ApprovalDecided(
                90L, "M-04", entity, "K", outcome, "ok", "{}", "checker.hq", NOW, 7L);
    }

    private static RateSet rateSet(LocalDate from, LocalDate to, boolean approved) {
        RateSet s = mock(RateSet.class);
        when(s.getRateSetCode()).thenReturn("DA_IDA");
        when(s.getEffectiveFrom()).thenReturn(from);
        when(s.getEffectiveTo()).thenReturn(to);
        when(s.isApproved()).thenReturn(approved);
        return s;
    }

    @Test
    void SRS_G_6_everyEarlierOpenRateVersionEndsAndOnlyEarlierOnesAndARefusalRejectsTheProposal() {
        RateSet proposed = rateSet(FROM, null, false);
        RateSet newest = rateSet(LocalDate.of(2026, 10, 1), null, true);
        RateSet older = rateSet(LocalDate.of(2026, 4, 1), null, true);
        RateSet later = rateSet(LocalDate.of(2027, 10, 1), null, true);
        RateSet unapproved = rateSet(LocalDate.of(2026, 1, 1), null, false);
        when(sets.findByApprovalId(90L)).thenReturn(Optional.of(proposed));
        when(sets.findByRateSetCodeOrderByVersionNoDesc("DA_IDA"))
                .thenReturn(List.of(later, proposed, newest, older, unapproved));

        effects.on(decided(RateMasterService.ENTITY, ApprovalStatus.APPROVED));

        verify(newest).endBefore(FROM);
        verify(older).endBefore(FROM);
        verify(later, never()).endBefore(any());
        verify(unapproved, never()).endBefore(any());
        verify(proposed).approve();
        // Without a screen of its own a rate set is the special-rates master (M-22).
        verify(audit)
                .applied(
                        any(),
                        eq(RateMasterService.ENTITY),
                        eq("DA_IDA"),
                        eq(RateMasterService.Screen.SPECIAL.code()),
                        eq(FROM),
                        notNull(),
                        notNull(),
                        anyList(),
                        eq("ok"));

        effects.on(decided(RateMasterService.ENTITY, ApprovalStatus.REJECTED));
        verify(proposed).reject();
    }

    @Test
    void
            SRS_G_1_anOtherParameterVersionAwaitingApprovalOrStartingLaterIsNotEndedAndARefusalAppliesNothing() {
        OtherParameter proposed = parameter(2L, 92L, FROM);
        OtherParameter approvedOpen = parameter(1L, 91L, LocalDate.of(2026, 4, 1));
        OtherParameter pending = parameter(3L, 93L, LocalDate.of(2026, 6, 1));
        OtherParameter approvedLater = parameter(4L, 94L, LocalDate.of(2027, 10, 1));
        OtherParameter approvedOpenToo = parameter(5L, 95L, LocalDate.of(2025, 4, 1));
        when(parameters.findByApprovalId(90L)).thenReturn(Optional.of(proposed));
        when(parameters.findByParameterCodeOrderByVersionNoDesc("NIGHT_SHIFT"))
                .thenReturn(
                        List.of(approvedLater, proposed, pending, approvedOpen, approvedOpenToo));
        when(statuses.of(any()))
                .thenReturn(
                        Map.of(
                                91L, ApprovalStatus.APPROVED,
                                93L, ApprovalStatus.PENDING_APPROVAL,
                                94L, ApprovalStatus.APPROVED,
                                95L, ApprovalStatus.APPROVED));

        effects.on(decided(OtherParameterService.ENTITY, ApprovalStatus.REJECTED));
        verify(parameters, never()).findByApprovalId(any());

        effects.on(decided(OtherParameterService.ENTITY, ApprovalStatus.APPROVED));
        verify(approvedOpen).endBefore(FROM);
        verify(approvedOpenToo).endBefore(FROM);
        verify(pending, never()).endBefore(any());
        verify(approvedLater, never()).endBefore(any());
    }

    @Test
    void SRS_G_7_aGlMappingOfAnotherOfficeIsNotEndedAndARefusalAppliesNothing() {
        GlAccountMapping proposed = mapping(2L, 92L, 300L, FROM);
        GlAccountMapping sameOffice = mapping(1L, 91L, 300L, LocalDate.of(2026, 4, 1));
        GlAccountMapping sameOfficeOlder = mapping(5L, 95L, 300L, LocalDate.of(2025, 4, 1));
        GlAccountMapping otherOffice = mapping(3L, 93L, 301L, LocalDate.of(2026, 4, 1));
        GlAccountMapping notApproved = mapping(4L, 94L, 300L, LocalDate.of(2026, 1, 1));
        GlAccountMapping later = mapping(6L, 96L, 300L, LocalDate.of(2027, 10, 1));
        when(mappings.findByApprovalId(90L)).thenReturn(Optional.of(proposed));
        when(mappings.findByComponentIdOrderByVersionNoDesc(7L))
                .thenReturn(
                        List.of(
                                later,
                                proposed,
                                sameOffice,
                                otherOffice,
                                notApproved,
                                sameOfficeOlder));
        when(statuses.of(any()))
                .thenReturn(
                        Map.of(
                                91L, ApprovalStatus.APPROVED,
                                93L, ApprovalStatus.APPROVED,
                                94L, ApprovalStatus.REJECTED,
                                95L, ApprovalStatus.APPROVED,
                                96L, ApprovalStatus.APPROVED));

        effects.on(decided(GlMappingService.ENTITY, ApprovalStatus.REJECTED));
        verify(mappings, never()).findByApprovalId(any());

        effects.on(decided(GlMappingService.ENTITY, ApprovalStatus.APPROVED));
        verify(sameOffice).endBefore(FROM);
        verify(sameOfficeOlder).endBefore(FROM);
        verify(otherOffice, never()).endBefore(any());
        verify(notApproved, never()).endBefore(any());
        verify(later, never()).endBefore(any());

        // Another master's decision is not this listener's.
        effects.on(decided("net_pay_floor_rule", ApprovalStatus.APPROVED));
        verify(sets, never()).findByApprovalId(any());
    }

    private static OtherParameter parameter(long id, long approvalId, LocalDate from) {
        OtherParameter p = mock(OtherParameter.class);
        when(p.getParameterId()).thenReturn(id);
        when(p.getApprovalId()).thenReturn(approvalId);
        when(p.getParameterCode()).thenReturn("NIGHT_SHIFT");
        when(p.getEffectiveFrom()).thenReturn(from);
        return p;
    }

    private static GlAccountMapping mapping(long id, long approvalId, Long office, LocalDate from) {
        GlAccountMapping m = mock(GlAccountMapping.class);
        when(m.getMappingId()).thenReturn(id);
        when(m.getApprovalId()).thenReturn(approvalId);
        when(m.getOrgUnitId()).thenReturn(office);
        when(m.getComponentId()).thenReturn(7L);
        when(m.getEffectiveFrom()).thenReturn(from);
        return m;
    }
}
