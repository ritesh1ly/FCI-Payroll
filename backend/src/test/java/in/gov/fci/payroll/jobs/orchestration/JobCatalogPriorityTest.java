package in.gov.fci.payroll.jobs.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.jobs.business.model.JobType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SRS-NF-11 (deep review DR-17): each submittable type tells the C-13 form its queue priority, the
 * highest a requester may ask for, so the form's default and choices match what the server keeps.
 */
class JobCatalogPriorityTest {

    private final JobCatalog catalog =
            new JobCatalog(mock(SystemParameters.class), mock(PayrollPeriods.class));

    @Test
    void SRS_NF_11_everySubmittableTypeCarriesItsOwnPriority() {
        List<JobCatalog.JobDef> defs = catalog.submittable();
        assertThat(defs).isNotEmpty();
        assertThat(defs).allSatisfy(d -> assertThat(d.priority()).isEqualTo(d.type().priority()));
        assertThat(defs)
                .filteredOn(d -> d.type() == JobType.PAYROLL_DRY_RUN)
                .singleElement()
                .extracting(JobCatalog.JobDef::priority)
                .isEqualTo(2);
        assertThat(defs)
                .filteredOn(d -> d.type() == JobType.PAYROLL_COMPUTATION)
                .singleElement()
                .extracting(JobCatalog.JobDef::priority)
                .isEqualTo(9);
    }
}
