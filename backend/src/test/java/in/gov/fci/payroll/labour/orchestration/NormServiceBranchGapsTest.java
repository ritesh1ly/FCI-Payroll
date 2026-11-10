package in.gov.fci.payroll.labour.orchestration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.labour.business.NormRules.Norms;
import in.gov.fci.payroll.labour.business.NormRules.Slab;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * M-06 Depot Incentive Norms (SRS-3-12): a set proposed without a free-text MoU reference (the MoU
 * itself is the linked M-25 circular) stores no reference.
 */
class NormServiceBranchGapsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final long DEPOT = 7L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AccessContext context = mock(AccessContext.class);
    private final NormService service =
            new NormService(
                    jdbc,
                    mock(NamedParameterJdbcTemplate.class),
                    mock(Authorization.class),
                    org,
                    mock(LabourDepots.class),
                    approvals,
                    parameters,
                    mock(AuditService.class),
                    mock(Circulars.class));

    @Test
    void SRS_3_12_aSetWithoutAMouReferenceStoresNone() {
        when(parameters.today()).thenReturn(TODAY);
        OrgLocation d = mock(OrgLocation.class);
        when(d.getId()).thenReturn(DEPOT);
        when(d.getCode()).thenReturn("DP-DL01");
        when(d.getName()).thenReturn("Okhla");
        when(d.getType()).thenReturn(LocationType.DEPOT);
        when(org.location(DEPOT)).thenReturn(Optional.of(d));
        SubmittedApproval item = mock(SubmittedApproval.class);
        when(item.id()).thenReturn(21L);
        when(approvals.submit(any(), any(Submission.class), any())).thenReturn(item);
        when(jdbc.queryForObject(
                        contains("SELECT norm_id FROM labour_norm"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(31L);
        LocalDate from = TODAY.plusDays(5);

        service.propose(
                context,
                new NormService.Proposal(
                        DEPOT,
                        from,
                        new Norms(250L, 20L, 0L, 10L, new BigDecimal("8.5")),
                        null,
                        List.of(
                                new Slab("BAND_1", 1, 1, 30L, new BigDecimal("8")),
                                new Slab("BAND_1", 2, 31, null, new BigDecimal("15"))),
                        "MoU 2026",
                        7L));

        verify(jdbc)
                .update(
                        contains("INSERT INTO labour_norm"),
                        eq(DEPOT),
                        isNull(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(1),
                        eq(Date.valueOf(from)));
        verify(jdbc).update(contains("UPDATE labour_norm SET approval_id"), eq(21L), eq(31L));
    }
}
