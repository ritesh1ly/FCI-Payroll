package in.gov.fci.payroll.configuration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.PayConfiguration.Snapshot;
import in.gov.fci.payroll.configuration.business.model.PayComponent;
import in.gov.fci.payroll.configuration.business.model.RateSet;
import in.gov.fci.payroll.configuration.business.model.RateSetLine;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The pay configuration a computation reads, as in force on a date (BR-RULE-007: reproducible; DDL
 * {@code rate_set}, {@code rate_set_line}, {@code pay_component}): only versions in force count,
 * the newest one of each rate set wins, and a missing rate is absent, never zero.
 */
class PayConfigurationTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 1);

    private final ConfigurationRepositories.RateSets sets =
            mock(ConfigurationRepositories.RateSets.class);
    private final ConfigurationRepositories.RateSetLines lines =
            mock(ConfigurationRepositories.RateSetLines.class);
    private final ConfigurationRepositories.PayComponents components =
            mock(ConfigurationRepositories.PayComponents.class);

    private final PayConfiguration configuration = new PayConfiguration(sets, lines, components);

    private static RateSet set(long id, String code, boolean inForce) {
        RateSet set = mock(RateSet.class);
        when(set.getRateSetId()).thenReturn(id);
        when(set.getRateSetCode()).thenReturn(code);
        when(set.isInForceOn(AS_OF)).thenReturn(inForce);
        return set;
    }

    private static RateSetLine line(long setId, String cityClass, String value) {
        RateSetLine line = mock(RateSetLine.class);
        when(line.getRateSetId()).thenReturn(setId);
        when(line.getCityClass()).thenReturn(cityClass);
        when(line.getRateValue()).thenReturn(new BigDecimal(value));
        return line;
    }

    private static PayComponent code(
            int payCode, long id, boolean retired, LocalDate from, LocalDate to, String taxable) {
        PayComponent c = mock(PayComponent.class);
        when(c.getLegacyPayCode()).thenReturn(payCode);
        when(c.getComponentId()).thenReturn(id);
        when(c.getComponentName()).thenReturn("Pay code " + payCode);
        when(c.isIsRetired()).thenReturn(retired);
        when(c.getEffectiveFrom()).thenReturn(from);
        when(c.getEffectiveTo()).thenReturn(to);
        when(c.getTaxableFlag()).thenReturn(taxable);
        return c;
    }

    // SRS-1-2: DA at the IDA or CDA rate the rule engine resolves for the period.
    @Test
    void BR_RULE_007_theNewestVersionInForceOfEachRateSetIsUsed() {
        // Two DA (IDA) versions in force: the list comes newest first, so version 2 (id 12) wins.
        RateSet idaV2 = set(12L, "DA_IDA", true);
        RateSet idaV1 = set(11L, "DA_IDA", true);
        RateSet cdaFuture = set(21L, "DA_CDA", false);
        RateSet hra = set(31L, "HRA", true);
        RateSet hraCda = set(32L, "HRA_CDA", true);
        when(sets.findByRateSetCodeInOrderByRateSetCodeAscVersionNoDesc(
                        PayConfiguration.STAFF_RATE_SETS))
                .thenReturn(List.of(cdaFuture, idaV2, idaV1, hra, hraCda));
        RateSetLine ida = line(12L, null, "53");
        RateSetLine hraX = line(31L, "X", "30");
        RateSetLine hraY = line(31L, "Y", "20");
        RateSetLine hraWithoutClass = line(31L, null, "99");
        // SRS-1-3 (US-GAP-41): the CDA pattern's own HRA rule.
        RateSetLine cdaX = line(32L, "X", "27");
        RateSetLine cdaWithoutClass = line(32L, null, "98");
        when(lines.findByRateSetIdIn(any()))
                .thenReturn(List.of(ida, hraX, hraY, hraWithoutClass, cdaX, cdaWithoutClass));
        when(components.findAllByOrderByLegacyPayCode()).thenReturn(List.of());

        Snapshot snapshot = configuration.staffConfiguration(AS_OF);

        assertThat(snapshot.daPercent()).containsExactly(Map.entry("IDA", new BigDecimal("53")));
        // An HRA line without a city class applies to no city class.
        assertThat(snapshot.hraPercent())
                .containsOnly(
                        Map.entry("X", new BigDecimal("30")),
                        Map.entry("Y", new BigDecimal("20")),
                        Map.entry("CDA/X", new BigDecimal("27")));
        assertThat(snapshot.hra("CDA", "X")).contains(new BigDecimal("27"));
        assertThat(snapshot.hra("CDA", "Y")).isEmpty();
        assertThat(snapshot.rateSetVersions())
                .containsOnly(
                        Map.entry("DA_IDA", 12L), Map.entry("HRA", 31L), Map.entry("HRA_CDA", 32L));
        // The CDA version starts later: CDA (and FND, which follows it) has no DA rate yet.
        assertThat(snapshot.da("CDA")).isEmpty();
        assertThat(snapshot.da("FND")).isEmpty();
        assertThat(snapshot.ccaSeeded()).isFalse();
        assertThat(snapshot.ccaAmount()).isEmpty();
    }

    @Test
    void BR_RULE_007_withNoRateSetInForceEveryRateIsAbsentNeverZero() {
        RateSet hraFuture = set(31L, "HRA", false);
        when(sets.findByRateSetCodeInOrderByRateSetCodeAscVersionNoDesc(
                        PayConfiguration.STAFF_RATE_SETS))
                .thenReturn(List.of(hraFuture));
        when(components.findAllByOrderByLegacyPayCode()).thenReturn(List.of());

        Snapshot snapshot = configuration.staffConfiguration(AS_OF);

        assertThat(snapshot.daPercent()).isEmpty();
        assertThat(snapshot.hraPercent()).isEmpty();
        assertThat(snapshot.da("IDA")).isEmpty();
        assertThat(snapshot.rateSetVersions()).isEmpty();
        verify(lines, never()).findByRateSetIdIn(any());
    }

    @Test
    void BR_RULE_007_cityCompensatoryAllowanceIsByCityClassWhereSeeded() {
        RateSet cca = set(41L, "CCA", true);
        RateSet cda = set(22L, "DA_CDA", true);
        when(sets.findByRateSetCodeInOrderByRateSetCodeAscVersionNoDesc(
                        PayConfiguration.STAFF_RATE_SETS))
                .thenReturn(List.of(cca, cda));
        RateSetLine ccaX = line(41L, "X", "1800");
        RateSetLine ccaWithoutClass = line(41L, null, "500");
        RateSetLine cdaLine = line(22L, null, "46");
        when(lines.findByRateSetIdIn(any())).thenReturn(List.of(ccaX, ccaWithoutClass, cdaLine));
        when(components.findAllByOrderByLegacyPayCode()).thenReturn(List.of());

        Snapshot snapshot = configuration.staffConfiguration(AS_OF);

        assertThat(snapshot.ccaSeeded()).isTrue();
        assertThat(snapshot.ccaAmount()).containsExactly(Map.entry("X", new BigDecimal("1800")));
        // FND staff follow the CDA pattern (FRS v0.4 Sec 7).
        assertThat(snapshot.da("FND")).contains(new BigDecimal("46"));
        assertThat(snapshot.da(null)).isEmpty();
    }

    @Test
    void BR_RULE_007_aPayCodeCountsWhileInForceOnTheDateAndNotRetired() {
        PayComponent open = code(101, 1L, false, LocalDate.of(2020, 4, 1), null, "Y");
        PayComponent endsThatDay = code(102, 2L, false, LocalDate.of(2020, 4, 1), AS_OF, "Y");
        PayComponent ended =
                code(103, 3L, false, LocalDate.of(2020, 4, 1), AS_OF.minusDays(1), "Y");
        PayComponent startsThatDay = code(104, 4L, false, AS_OF, null, "Y");
        PayComponent later = code(105, 5L, false, AS_OF.plusDays(1), null, "Y");
        PayComponent retired = code(106, 6L, true, LocalDate.of(2020, 4, 1), null, "Y");
        when(sets.findByRateSetCodeInOrderByRateSetCodeAscVersionNoDesc(
                        PayConfiguration.STAFF_RATE_SETS))
                .thenReturn(List.of());
        when(components.findAllByOrderByLegacyPayCode())
                .thenReturn(List.of(open, endsThatDay, ended, startsThatDay, later, retired));

        assertThat(configuration.staffConfiguration(AS_OF).componentIds())
                .containsOnly(Map.entry(101, 1L), Map.entry(102, 2L), Map.entry(104, 4L));
    }

    @Test
    void aPayCodeAttractsTaxUnlessFlaggedNotTaxable() {
        PayComponent taxable = code(131, 7L, false, AS_OF, null, "Y");
        PayComponent partly = code(140, 8L, false, AS_OF, null, "PARTLY");
        PayComponent exempt = code(150, 9L, false, AS_OF, null, "N");
        when(components.findByLegacyPayCode(131)).thenReturn(Optional.of(taxable));
        when(components.findByLegacyPayCode(140)).thenReturn(Optional.of(partly));
        when(components.findById(9L)).thenReturn(Optional.of(exempt));

        assertThat(configuration.component(131)).get().extracting(c -> c.taxable()).isEqualTo(true);
        // PARTLY still attracts TDS on its taxable part (pay_component.taxable_flag).
        assertThat(configuration.component(140)).get().extracting(c -> c.taxable()).isEqualTo(true);
        assertThat(configuration.componentById(9L))
                .get()
                .satisfies(
                        c -> {
                            assertThat(c.taxable()).isFalse();
                            assertThat(c.legacyPayCode()).isEqualTo(150);
                            assertThat(c.componentId()).isEqualTo(9L);
                        });
        assertThat(configuration.component(999)).isEmpty();
    }
}
