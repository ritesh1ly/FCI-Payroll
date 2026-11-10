package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.batch.computation.MemberReader;
import in.gov.fci.payroll.payroll.business.SimulationRules;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * C-09 Simulation Run (SRS-15-7): an office larger than one page is read page after page until a
 * page comes back short, every member counted once; an engine built without the deduction sources
 * hashes the configuration alone and applies no deduction. A member without basic pay is counted as
 * skipped, never computed as zero.
 */
class SimulationEngineBranchGapsTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PayConfiguration configuration = mock(PayConfiguration.class);

    @Test
    @SuppressWarnings("unchecked")
    void SRS_15_7_anOfficeLargerThanAPageIsReadPageAfterPage() {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(40L);
        when(org.subtree(40L)).thenReturn(Set.of(40L));
        when(org.cycleUnitOf(40L)).thenReturn(Optional.of(unit));
        PayConfiguration.Snapshot base = mock(PayConfiguration.Snapshot.class);
        when(base.versionSetHash()).thenReturn("config-hash");
        when(configuration.staffConfiguration(LocalDate.of(2026, 10, 1))).thenReturn(base);
        AtomicInteger pages = new AtomicInteger();
        when(named.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(
                        call -> {
                            int page = pages.getAndIncrement();
                            List<MemberReader.MemberRow> rows = new ArrayList<>();
                            int size = page == 0 ? SimulationEngine.PAGE : 3;
                            for (int i = 0; i < size; i++) {
                                long id = page * 1000L + i + 1;
                                rows.add(
                                        new MemberReader.MemberRow(
                                                id, 40L, null, "IDA", "X", "ACTIVE", null, null,
                                                null, null, true));
                            }
                            return rows;
                        });
        SimulationEngine engine = new SimulationEngine(named, org, configuration);
        List<Long> progress = new ArrayList<>();
        SimulationEngine.Outcome outcome =
                engine.run(
                        new SimulationEngine.Request(
                                40L,
                                YearMonth.of(2026, 10),
                                null,
                                SimulationRules.WhatIf.AS_IS,
                                null,
                                null),
                        true,
                        progress::add);
        assertThat(pages.get()).isEqualTo(2);
        assertThat(progress)
                .containsExactly((long) SimulationEngine.PAGE, SimulationEngine.PAGE + 3L);
        assertThat(outcome.population()).isEqualTo(SimulationEngine.PAGE + 3L);
        assertThat(outcome.whatIf()).isNull();
        assertThat(outcome.versionSetHash()).isEqualTo("config-hash");
        assertThat(outcome.members()).hasSize(SimulationEngine.PAGE + 3);
        assertThat(outcome.members().getFirst().status()).isEqualTo("SKIPPED");
        assertThat(outcome.asIs().computed()).isZero();
        assertThat(outcome.asIs().stopped()).isEqualTo(SimulationEngine.PAGE + 3L);
    }
}
