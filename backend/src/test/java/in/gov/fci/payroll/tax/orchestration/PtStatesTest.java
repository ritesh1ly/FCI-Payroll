package in.gov.fci.payroll.tax.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.cache.CacheProperties;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.tax.business.model.PtState;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * SRS-7-4, BR-TAX-006, HLD §3.12: the PT state master read once for every member, reloaded after an
 * approved M-18 change; an unknown state is absent, never assumed; the in-force window includes
 * both ends.
 */
class PtStatesTest {

    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);

    private final TaxRepositories.PtStates master = mock(TaxRepositories.PtStates.class);
    private final CacheRegions regions = mock(CacheRegions.class);
    private final AtomicLong version = new AtomicLong(1);
    private final PtStates states;

    PtStatesTest() {
        when(regions.properties())
                .thenReturn(new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)));
        when(regions.version(PtStates.REGION)).thenAnswer(call -> version.get());
        states = new PtStates(master, regions);
    }

    private static PtState state(String code, boolean applies, LocalDate to) {
        PtState s = PtState.of(code);
        s.apply("State " + code, applies, "R1", applies ? "PT.SLAB" : null, "MONTHLY", APRIL, to);
        return s;
    }

    @Test
    void BR_TAX_006_theMasterIsReadOnceAndAgainOnlyAfterAnApprovedChange() {
        when(master.findAllByOrderByStateCodeAsc())
                .thenReturn(List.of(state("MH", true, null), state("GA", false, null)));
        assertThat(states.state("MH"))
                .hasValueSatisfying(v -> assertThat(v.slabRuleKey()).isEqualTo("PT.SLAB"));
        assertThat(states.state("GA"))
                .hasValueSatisfying(v -> assertThat(v.ptApplicable()).isFalse());
        // A state not in the master is absent: the computation names it, nothing is assumed.
        assertThat(states.state("XX")).isEmpty();
        verify(master, times(1)).findAllByOrderByStateCodeAsc();
        when(master.findAllByOrderByStateCodeAsc()).thenReturn(List.of(state("XX", true, null)));
        version.incrementAndGet();
        assertThat(states.state("XX")).isPresent();
        assertThat(states.state("MH")).isEmpty();
        states.changed("approver.hq");
        verify(regions).changed(PtStates.REGION, "approver.hq");
    }

    @Test
    void SRS_7_4_theInForceWindowIncludesBothEnds() {
        LocalDate end = APRIL.plusMonths(6);
        PtStates.View v = PtStates.View.of(state("MH", true, end));
        assertThat(v.inForceOn(APRIL.minusDays(1))).isFalse();
        assertThat(v.inForceOn(APRIL)).isTrue();
        assertThat(v.inForceOn(end)).isTrue();
        assertThat(v.inForceOn(end.plusDays(1))).isFalse();
        assertThat(PtStates.View.of(state("MH", true, null)).inForceOn(end.plusYears(5))).isTrue();
    }
}
