package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** M-05 (SRS-4-11): the designations are asked of a labour payroll type; none named is refused. */
class LabourRateServiceBranchGapsTest {

    private final LabourRateService rates =
            new LabourRateService(
                    mock(JdbcTemplate.class),
                    mock(Authorization.class),
                    mock(OrgDirectory.class),
                    mock(MakerChecker.class),
                    mock(AuditService.class));

    @Test
    void SRS_4_11_designationsWithoutAPayrollTypeAreRefused() {
        AccessContext context = mock(AccessContext.class);
        assertThatThrownBy(() -> rates.designations(context, null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(String.valueOf(e.getMessageArgs()[0]))
                                        .isEqualTo("PAYROLL_TYPE"));
        assertThat(rates.designations(context, "DEPT_LABOUR")).isEmpty();
    }
}
