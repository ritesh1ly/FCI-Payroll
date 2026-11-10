package in.gov.fci.payroll.labour.orchestration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M-09 temporary attachment (SRS-2-47, D20-12): the order's reference and issuing authority are
 * optional; left out or blank they are stored as no value.
 */
class LabourAttachmentServiceBranchGapsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final long LABOUR = 11L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LabourGangs gangs = mock(LabourGangs.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final LabourAttachmentService service =
            new LabourAttachmentService(
                    jdbc,
                    gangs,
                    mock(Authorization.class),
                    org,
                    approvals,
                    parameters,
                    mock(AuditService.class),
                    Clock.systemUTC());

    private static LabourGangs.Gang gang(long id) {
        return new LabourGangs.Gang(
                id,
                "G" + id,
                "Gang " + id,
                7L,
                "DP-DL01",
                "DEPT_LABOUR",
                "ACTIVE",
                TODAY.minusYears(1),
                null);
    }

    @Test
    void SRS_2_47_anOrderWithoutAReferenceOrABlankAuthorityStoresNoValue() {
        when(parameters.today()).thenReturn(TODAY);
        OrgLocation office = mock(OrgLocation.class);
        when(office.getId()).thenReturn(3L);
        when(org.cycleUnitOf(7L)).thenReturn(Optional.of(office));
        Map<String, Object> m = new HashMap<>();
        m.put("GANG_ID", 1L);
        m.put("GANG_ROLE", "HANDLING");
        m.put("EFFECTIVE_TO", null);
        when(jdbc.queryForList(
                        contains("m.employee_id = ? AND p.labour_status"), any(Object[].class)))
                .thenReturn(List.of(m));
        when(gangs.gang(1L)).thenReturn(gang(1L));
        when(gangs.gang(2L)).thenReturn(gang(2L));
        SubmittedApproval item = mock(SubmittedApproval.class);
        when(item.id()).thenReturn(55L);
        when(approvals.submit(any(), any(Submission.class), any())).thenReturn(item);
        when(jdbc.queryForObject(
                        contains("SELECT temp_attachment_id"), eq(Long.class), any(Object[].class)))
                .thenReturn(71L);

        service.propose(
                mock(AccessContext.class),
                LABOUR,
                new LabourAttachmentService.Request(
                        2L, TODAY, TODAY.plusDays(5), "Rake", null, null, "   "));

        verify(jdbc)
                .update(
                        contains("INSERT INTO labour_temp_attachment"),
                        anyString(),
                        eq(LABOUR),
                        eq("HANDLING"),
                        eq(1L),
                        eq(2L),
                        eq(Date.valueOf(TODAY)),
                        eq(Date.valueOf(TODAY.plusDays(5))),
                        eq(7L),
                        eq("Rake"),
                        isNull(),
                        isNull(),
                        isNull(),
                        any(),
                        any());
        verify(jdbc).update(contains("SET approval_id"), eq(55L), eq(71L));
    }
}
