package in.gov.fci.payroll.jobs.batch.scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.config.BatchProperties;
import org.junit.jupiter.api.Test;

/**
 * Until the HRMS interface exists (OQ-8) the nightly pull records that nothing was pulled, and why,
 * instead of pretending (SRS-NF-2: the run is visible on C-13 with its outcome).
 */
class HrmsInterfaceClientTest {

    private static HrmsInterfaceClient client(String source) {
        BatchProperties properties = mock(BatchProperties.class);
        BatchProperties.Interfaces interfaces = mock(BatchProperties.Interfaces.class);
        when(interfaces.hrmsSource()).thenReturn(source);
        when(properties.interfaces()).thenReturn(interfaces);
        return new ScheduledJobsConfig().hrmsInterfaceClient(properties);
    }

    @Test
    void SRS_NF_2_withoutAConfiguredInterfaceThePullSaysNothingWasPulled() {
        HrmsInterfaceClient.PullResult result = client("").pull("2026-10");
        assertThat(result.received()).isZero();
        assertThat(result.accepted()).isZero();
        assertThat(result.note())
                .isEqualTo(
                        "No HRMS interface configured (fci.batch.interfaces.hrms-source, OQ-8);"
                                + " nothing pulled");
    }

    @Test
    void SRS_NF_2_aConfiguredButUnbuiltInterfaceIsNamedAndNothingIsPulled() {
        HrmsInterfaceClient.PullResult result = client("HRMS-SOAP").pull("2026-10");
        assertThat(result.received()).isZero();
        assertThat(result.note())
                .isEqualTo("HRMS interface HRMS-SOAP not yet implemented (OQ-8); nothing pulled");
    }
}
