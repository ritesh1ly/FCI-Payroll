package in.gov.fci.payroll.configuration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.business.model.RuleDefinition;
import in.gov.fci.payroll.configuration.business.model.RuleExpressionGrammar;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.cache.CacheProperties;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The engine's edges that a database with seeded rules never shows (BR-CYC-011): a day with no
 * COMPUTATION rule at all; and its cache (HLD §3.12): read once per day and rule, reloaded after an
 * approved change, read afresh at the start of a run, never holding a refused rule. The flows are
 * {@code RuleEngineIT}.
 */
class RuleEngineTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    private final ConfigurationRepositories.Rules rules =
            mock(ConfigurationRepositories.Rules.class);
    private final ConfigurationRepositories.RuleVersions versions =
            mock(ConfigurationRepositories.RuleVersions.class);
    private final ConfigurationRepositories.RuleSlabBands bands =
            mock(ConfigurationRepositories.RuleSlabBands.class);
    private final ConfigurationRepositories.Grammar grammar =
            mock(ConfigurationRepositories.Grammar.class);
    private final AtomicLong version = new AtomicLong(1);
    private final CacheRegions regions = mock(CacheRegions.class);
    private final RuleEngine engine;

    RuleEngineTest() {
        when(regions.properties())
                .thenReturn(new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)));
        when(regions.version(RuleEngine.REGION)).thenAnswer(call -> version.get());
        when(rules.findByDomain(RuleDefinition.Domain.COMPUTATION)).thenReturn(List.of());
        when(grammar.findByPermitted(Boolean.TRUE)).thenReturn(List.of());
        engine = new RuleEngine(rules, versions, bands, grammar, mock(OrgDirectory.class), regions);
    }

    @Test
    void BR_CYC_011_aDayWithoutComputationRulesPinsAnEmptySetWithoutAnEmptyQuery() {
        RuleSet set = engine.snapshot(DAY);
        assertThat(set.versions()).isEmpty();
        assertThat(set.hash()).isEqualTo(SecureTokens.sha256Hex(""));
        assertThat(engine.pin(DAY)).isEqualTo(set.versionSet());
        // An IN () list is invalid SQL on Oracle: the engine never asks with one.
        verify(versions, never()).findByRuleIdInAndState(any(), any());
        verify(bands, never()).findByRuleVersionIdIn(any());
        assertThatThrownBy(() -> engine.snapshot(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> engine.pin(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void HLD_3_12_aDaysSnapshotIsReadOnceAndAgainOnlyAfterTheRegionChanged() {
        RuleSet first = engine.snapshot(DAY);
        assertThat(engine.snapshot(DAY)).isSameAs(first);
        verify(rules, times(1)).findByDomain(RuleDefinition.Domain.COMPUTATION);
        // Another day is its own entry (period-keyed: a closed period keeps its own rules).
        engine.snapshot(DAY.minusMonths(1));
        verify(rules, times(2)).findByDomain(RuleDefinition.Domain.COMPUTATION);
        // An approved version on any instance moves the region's version: reloaded.
        version.incrementAndGet();
        assertThat(engine.snapshot(DAY)).isNotSameAs(first);
        verify(rules, times(3)).findByDomain(RuleDefinition.Domain.COMPUTATION);
    }

    @Test
    void BR_CYC_011_theStartOfARunReadsAfreshAndItsMembersComputeWithThePinnedSet() {
        engine.snapshot(DAY);
        engine.pin(DAY);
        // pin never trusts the cache (another instance's approval may not have been seen yet)...
        verify(rules, times(2)).findByDomain(RuleDefinition.Domain.COMPUTATION);
        // ... and keeps what it read: the members of the run use it without another read.
        RuleSet members = engine.snapshot(DAY);
        assertThat(members.versionSet()).isEqualTo(engine.pin(DAY));
        verify(rules, times(3)).findByDomain(RuleDefinition.Domain.COMPUTATION);
    }

    @Test
    void HLD_3_12_anApprovedOrEndedVersionMarksTheRegionChanged() {
        engine.on(
                new RuleVersionChanged(
                        RuleVersionChanged.Kind.APPROVED,
                        "DA.RATE",
                        "RATE",
                        7L,
                        2,
                        DAY,
                        null,
                        DAY));
        verify(regions).changed(RuleEngine.REGION, "rule:DA.RATE");
    }

    @Test
    void RULE_422_aRefusedRuleIsNotCachedAndAKnownOneIsReadOncePerDay() {
        when(rules.findByKey("NO.SUCH")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> engine.resolve("NO.SUCH", DAY, RuleEngine.Context.unscoped()))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> engine.resolve("NO.SUCH", DAY, RuleEngine.Context.unscoped()))
                .isInstanceOf(FciException.class);
        // Asked again each time: a rule configured a moment later is found at once.
        verify(rules, times(2)).findByKey("NO.SUCH");

        RuleDefinition known = mock(RuleDefinition.class);
        when(known.getId()).thenReturn(5L);
        when(known.getKey()).thenReturn("DA.RATE");
        when(known.isActive()).thenReturn(true);
        when(known.getDomain()).thenReturn(RuleDefinition.Domain.COMPUTATION);
        // Mockito answers 0 for a Long: no office scope is null.
        when(known.getOrgScopeLocationId()).thenReturn(null);
        when(rules.findByKey("DA.RATE")).thenReturn(Optional.of(known));
        when(versions.findByRuleIdInAndState(any(), any())).thenReturn(List.of());
        // No version in force: "not configured" is the rule's answer, cached like any other.
        assertThatThrownBy(() -> engine.resolve("DA.RATE", DAY, RuleEngine.Context.unscoped()))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> engine.resolve("DA.RATE", DAY, RuleEngine.Context.unscoped()))
                .isInstanceOf(FciException.class);
        verify(rules, times(1)).findByKey("DA.RATE");
        assertThatThrownBy(() -> engine.resolve(null, DAY, RuleEngine.Context.unscoped()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void SRS_19_15_theGrammarIsReadOnceKeepsAnAnyArityFunctionAndCannotBeChanged() {
        RuleExpressionGrammar max = mock(RuleExpressionGrammar.class);
        when(max.getTokenType()).thenReturn(RuleExpressionGrammar.TokenType.FUNCTION);
        when(max.getTokenValue()).thenReturn("MAX");
        when(max.getArity()).thenReturn(null);
        when(grammar.findByPermitted(Boolean.TRUE)).thenReturn(List.of(max));
        RuleEngine.Vocabulary first = engine.vocabulary();
        assertThat(first.functions()).containsEntry("MAX", null);
        engine.vocabulary();
        verify(grammar, times(1)).findByPermitted(Boolean.TRUE);
        version.incrementAndGet();
        engine.vocabulary();
        verify(grammar, times(2)).findByPermitted(Boolean.TRUE);
    }
}
