package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SimulationPopulation;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** The M-08 simulation sample (plan 06 phase 06e; SRS-19-4): staff only, offices in chunks. */
class RulePopulationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 4, 15);

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final RulePopulation population = new RulePopulation(jdbc, org);

    @Test
    void SRS_19_4_labourTypesHaveNoMembersYet() {
        assertThat(population.sample(DAY, "DPS_LABOUR", null, 10).total()).isZero();
        verify(jdbc, never())
                .queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_19_4_theSampleStopsAtTheLimitButCountsEveryMember() {
        when(org.subtree(5L)).thenReturn(Set.of(5L, 6L));
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(null);
        SimulationPopulation.Sample none = population.sample(DAY, "STAFF", 5L, 0);
        assertThat(none.total()).isZero();
        assertThat(none.members()).isEmpty();
        verify(jdbc, never())
                .query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(3L);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(new SimulationPopulation.Member(1L, "STAFF", 5L)));
        SimulationPopulation.Sample all = population.sample(DAY, null, null, 2);
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.members()).hasSize(1);
    }

    @Test
    void SRS_19_4_officesGoInChunksOfAThousand() {
        assertThat(RulePopulation.chunks(null)).containsExactly((List<Long>) null);
        assertThat(RulePopulation.chunks(List.of())).containsExactly(List.of(-1L));
        List<Long> many = new ArrayList<>(LongStream.rangeClosed(1, 2001).boxed().toList());
        assertThat(RulePopulation.chunks(many))
                .extracting(List::size)
                .containsExactly(1000, 1000, 1);
    }
}
