package in.gov.fci.payroll.configuration.orchestration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.RuleVersion;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A-07 decisions (BR-RULE-002, SRS-G-22): an approved version dated for a later day takes effect on
 * that day, not on the decision day, and the version in force ends the day before it; one dated for
 * today or earlier takes effect from the decision day.
 */
class SystemParameterApprovalEffectBranchGapsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    private final ConfigurationRepositories.RuleVersions versions =
            mock(ConfigurationRepositories.RuleVersions.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final SystemParameterApprovalEffect effect =
            new SystemParameterApprovalEffect(versions, parameters, mock(MasterAudit.class));

    SystemParameterApprovalEffectBranchGapsTest() {
        when(parameters.today()).thenReturn(TODAY);
        when(parameters.definitions()).thenReturn(List.of());
    }

    private RuleVersion version(LocalDate from, RuleVersion.State state) {
        RuleVersion v = mock(RuleVersion.class);
        when(v.getRuleId()).thenReturn(31L);
        when(v.getEffectiveFrom()).thenReturn(from);
        when(v.getState()).thenReturn(state);
        return v;
    }

    private void approve(RuleVersion proposed, RuleVersion... open) {
        when(versions.findByApprovalId(7L)).thenReturn(Optional.of(proposed));
        List<RuleVersion> chain = new java.util.ArrayList<>(List.of(open));
        chain.add(0, proposed);
        when(versions.findByRuleIdOrderByVersionNoDesc(31L)).thenReturn(chain);
        effect.on(
                new ApprovalDecided(
                        7L,
                        "A-07",
                        "rule_version",
                        "OTP_COOLING_OFF",
                        ApprovalStatus.APPROVED,
                        null,
                        null,
                        "sys.checker@x",
                        Instant.parse("2026-10-23T06:00:00Z")));
    }

    @Test
    void BR_RULE_002_aVersionDatedForALaterDayTakesEffectOnThatDay() {
        LocalDate later = TODAY.plusDays(10);
        RuleVersion proposed = version(later, RuleVersion.State.PENDING_APPROVAL);
        RuleVersion inForce = version(TODAY.minusMonths(6), RuleVersion.State.APPROVED);
        approve(proposed, inForce);
        verify(inForce).endBefore(later);
        verify(proposed).approve(later);
        verify(parameters).evict();
    }

    @Test
    void BR_RULE_002_aVersionDatedForTodayOrEarlierTakesEffectFromTheDecisionDay() {
        RuleVersion proposed = version(TODAY.minusDays(3), RuleVersion.State.PENDING_APPROVAL);
        approve(proposed);
        verify(proposed).approve(eq(TODAY));
        verify(versions, org.mockito.Mockito.atLeastOnce()).save(any());
    }
}
