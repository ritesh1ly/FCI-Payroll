package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import java.util.List;
import org.junit.jupiter.api.Test;

/** docs/plan/X-pagination.md: the sizes offered and the starting size, from the A-07 settings. */
class ConfiguredPageSizesTest {

    @Test
    void SRS_NF_3_theOwnersSeriesUpToTheConfiguredMaximum() {
        assertThat(ConfiguredPageSizes.offered(100)).containsExactly(10, 20, 50, 100);
        assertThat(ConfiguredPageSizes.offered(99)).containsExactly(10, 20, 50);
        assertThat(ConfiguredPageSizes.offered(50)).containsExactly(10, 20, 50);
        assertThat(ConfiguredPageSizes.offered(10)).containsExactly(10);
        assertThat(ConfiguredPageSizes.offered(5)).containsExactly(10);
    }

    @Test
    void SRS_NF_3_theDefaultIsTheConfiguredSizeIfOfferedElseTheLargestBelowIt() {
        List<Integer> all = List.of(10, 20, 50, 100);
        assertThat(ConfiguredPageSizes.defaultOf(20, all)).isEqualTo(20);
        assertThat(ConfiguredPageSizes.defaultOf(30, all)).isEqualTo(20);
        assertThat(ConfiguredPageSizes.defaultOf(100, List.of(10, 20, 50))).isEqualTo(50);
        assertThat(ConfiguredPageSizes.defaultOf(5, all)).isEqualTo(10);
    }

    @Test
    void SRS_NF_3_theBeanReadsBothSettings() {
        SystemParameters parameters = mock(SystemParameters.class);
        when(parameters.pageSizeMax()).thenReturn(50);
        when(parameters.pageSizeDefault()).thenReturn(100);
        ConfiguredPageSizes sizes = new ConfiguredPageSizes(parameters);
        assertThat(sizes.offered()).containsExactly(10, 20, 50);
        assertThat(sizes.defaultSize()).isEqualTo(50);
    }
}
