package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.LabourPersons;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.api.OrgMasters;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-02 amendment (SRS-2-30): an amendment carrying no field and no reason changes nothing and is
 * refused as such, before any reason check.
 */
class LabourMasterServiceBranchGapsTest {

    private static final long DEPOT = 7L;
    private static final long MEMBER = 41L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final LabourMasterService service =
            new LabourMasterService(
                    jdbc,
                    mock(NamedParameterJdbcTemplate.class),
                    mock(Authorization.class),
                    mock(OrgDirectory.class),
                    mock(OrgMasters.class),
                    mock(LabourDepots.class),
                    mock(MakerChecker.class),
                    mock(LabourPersons.class),
                    parameters,
                    mock(AuditService.class),
                    JsonMapper.builder().build());

    @Test
    void SRS_2_30_anEmptyAmendmentWithoutAReasonChangesNothing() {
        when(parameters.today()).thenReturn(LocalDate.of(2026, 10, 28));
        Map<String, Object> r = new HashMap<>();
        r.put("LABOUR_NUMBER", "DPT-000041");
        r.put("PAYROLL_TYPE", "DEPT_LABOUR");
        r.put("DEPOT_LOCATION_ID", DEPOT);
        r.put("PF_APPLICABLE_FLAG", "N");
        r.put("DESIGNATION_ID", 3L);
        r.put("FIRST_NAME", "Ram");
        r.put("LAST_NAME", "Kumar");
        r.put("BIRTH_DATE", Date.valueOf("1990-01-01"));
        r.put("COMP_JOINING_DATE", Date.valueOf("2015-07-01"));
        r.put("BANK_ACCOUNT_NUMBER", "000012345678");
        r.put("CASUAL_LABOUR_FLAG", "N");
        r.put("IFSC_CODE", "SBIN0DEMO01");
        when(jdbc.queryForList(
                        contains("FROM labour_profile p JOIN employee"), any(Object[].class)))
                .thenReturn(List.of(r));

        assertThatThrownBy(
                        () ->
                                service.amend(
                                        mock(AccessContext.class),
                                        MEMBER,
                                        new LabourMasterService.Request(
                                                null, null, null, null, null, null, null, null,
                                                null, null, null, null, null, null, null, null,
                                                null, null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(String.valueOf(e.getMessageArgs()[0]))
                                        .isEqualTo("NO_CHANGE"));
    }
}
