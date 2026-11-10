package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.LabourPersons;
import in.gov.fci.payroll.labour.business.LabourRules;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-02 name correction (SRS-2-59, BR-LABM-003; M-02 l.4449 Labour Name, 150): a name and surname
 * each valid but together longer than the 150 the record shows is refused.
 */
class LabourCorrectionServiceBranchGapsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final long LABOUR = 11L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final LabourCorrectionService service =
            new LabourCorrectionService(
                    jdbc,
                    mock(Authorization.class),
                    org,
                    mock(MakerChecker.class),
                    mock(LabourPersons.class),
                    mock(DocumentStore.class),
                    parameters,
                    mock(AuditService.class),
                    JsonMapper.builder().build(),
                    Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC));

    @Test
    void SRS_2_59_aNameAndSurnameTogetherOverTheFullNameLengthAreRefused() {
        when(parameters.today()).thenReturn(TODAY);
        Map<String, Object> labour = new HashMap<>();
        labour.put("LABOUR_NUMBER", "DPT-000011");
        labour.put("DEPOT_LOCATION_ID", 7L);
        labour.put("FIRST_NAME", "Ram");
        labour.put("LAST_NAME", null);
        labour.put("BIRTH_DATE", Date.valueOf("1990-01-15"));
        labour.put("COMP_JOINING_DATE", Date.valueOf("2015-07-01"));
        when(jdbc.queryForList(contains("AND p.labour_status = 'ACTIVE'"), any(Object[].class)))
                .thenReturn(List.of(labour));
        OrgLocation office = mock(OrgLocation.class);
        when(office.getId()).thenReturn(3L);
        when(org.cycleUnitOf(7L)).thenReturn(Optional.of(office));
        when(jdbc.queryForList(
                        contains("list_code = 'CORRECTION_REASON'"),
                        eq(String.class),
                        any(Object[].class)))
                .thenReturn(List.of("AS_PER_SERVICE_BOOK", "AS_PER_ORDER"));
        String first = "R".repeat(100);
        String last = "Y".repeat(LabourRules.FULL_NAME_MAX - 100);
        assertThat(LabourRules.name(first)).isTrue();
        assertThat(LabourRules.name(last)).isTrue();

        assertThatThrownBy(
                        () ->
                                service.correct(
                                        mock(AccessContext.class),
                                        LABOUR,
                                        new LabourCorrectionService.Correction(
                                                "NAME",
                                                first,
                                                last,
                                                null,
                                                "AS_PER_SERVICE_BOOK",
                                                null,
                                                null,
                                                TODAY,
                                                "Area Manager",
                                                TODAY),
                                        new LabourCorrectionService.Document(
                                                "page.pdf", new byte[] {1})))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(String.valueOf(e.getMessageArgs()[0])).isEqualTo("NAME"));
    }
}
