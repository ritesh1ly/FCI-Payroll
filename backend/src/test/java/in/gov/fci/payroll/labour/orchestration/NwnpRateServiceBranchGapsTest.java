package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.labour.business.LabourRateRules.Rate;
import in.gov.fci.payroll.labour.business.NwnpRateRules.Line;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * M-05 ▸ NWNP rate sets (SRS-5-2, SRS-5-3), the sides the other tests leave: an inactive depot is
 * not offered, a proposal whose pending count reads as no value goes on, and an approval with the
 * Rule Approver's reason audits that reason.
 */
class NwnpRateServiceBranchGapsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final NwnpRateService service =
            new NwnpRateService(jdbc, mock(Authorization.class), org, approvals, parameters, audit);

    private static OrgLocation location(
            long id, String code, LocationType type, boolean activeToday) {
        OrgLocation l = mock(OrgLocation.class);
        when(l.getId()).thenReturn(id);
        when(l.getCode()).thenReturn(code);
        when(l.getName()).thenReturn("Office " + code);
        when(l.getType()).thenReturn(type);
        when(l.isActiveOn(TODAY)).thenReturn(activeToday);
        return l;
    }

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(TODAY);
        when(context.loginId()).thenReturn("rule.maker@fci-demo.test");
    }

    @Test
    void SRS_5_2_onlyActiveDepotsAreOfferedForAnNwnpSet() {
        OrgLocation open = location(7L, "DP-DL01", LocationType.DEPOT, true);
        OrgLocation closed = location(8L, "DP-DL02", LocationType.DEPOT, false);
        OrgLocation office = location(3L, "DO-DL", LocationType.DO, true);
        when(org.all()).thenReturn(Set.of(3L, 7L, 8L));
        when(org.locations(any())).thenReturn(List.of(closed, office, open));

        var options = service.options(context);

        assertThat(options.depots())
                .extracting(LabourRateService.Designation::code)
                .containsExactly("DP-DL01");
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_5_3_aProposalWhosePendingCountReadsAsNoValueGoesOnToTheApprover() {
        OrgLocation depot = location(7L, "DP-DL01", LocationType.DEPOT, true);
        when(org.location(7L)).thenReturn(Optional.of(depot));
        doReturn(List.of(new NwnpRateService.Operation("LOAD", "Loading", "W2P")))
                .when(jdbc)
                .query(contains("FROM nwnp_operation n"), any(RowMapper.class));
        doReturn(List.of(new NwnpRateService.Unit("BAG", "Per bag")))
                .when(jdbc)
                .query(contains("'NWNP_UOM' AND"), any(RowMapper.class), any(Object[].class));
        doReturn(List.of(new LabourRateService.Designation(21L, "NWNP-M", "Member", "MEMBER")))
                .when(jdbc)
                .query(contains("FROM org_designation"), any(RowMapper.class), any(Object[].class));
        // The pending count reads as no value (no row counted): not PENDING.
        when(jdbc.queryForObject(
                        contains("approval_state = 'PENDING_APPROVAL'"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(null);
        when(jdbc.queryForObject(
                        contains("SELECT rate_set_id FROM rate_set"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(90L);
        when(approvals.submit(eq(context), any(), isNull()))
                .thenReturn(new SubmittedApproval(61L, ApprovalStatus.PENDING_APPROVAL, null));
        LocalDate from = TODAY.plusMonths(2).withDayOfMonth(1);

        SubmittedApproval item =
                service.propose(
                        context,
                        new NwnpRateService.Proposal(
                                7L,
                                "ASOR",
                                from,
                                List.of(new Line("LOAD", new BigDecimal("1.25"), "BAG")),
                                List.of(new Rate(21L, new BigDecimal("612.00"))),
                                " Circular 12/2026 "));

        assertThat(item.id()).isEqualTo(61L);
        verify(jdbc)
                .update(
                        contains("INSERT INTO rate_set"),
                        eq("NWNP-DP-DL01-ASOR"),
                        anyString(),
                        eq("NWNP_LABOUR"),
                        eq(7L),
                        eq(1),
                        eq(Date.valueOf(from)));
        verify(jdbc).update(contains("UPDATE rate_set SET approval_id"), eq(61L), eq(90L));
    }

    @Test
    void SRS_5_2_anApprovalWithTheApproversReasonAuditsThatReason() {
        Map<String, Object> pending = new HashMap<>();
        pending.put("RATE_SET_CODE", "NWNP-DP-DL01-ASOR");
        pending.put("EFFECTIVE_FROM", Date.valueOf("2027-01-01"));
        pending.put("APPROVAL_STATE", "PENDING_APPROVAL");
        when(jdbc.queryForList(contains("FROM rate_set WHERE"), eq(8L)))
                .thenReturn(List.of(pending));

        service.on(
                new ApprovalDecided(
                        61L,
                        "M-05",
                        NwnpRateService.ENTITY,
                        "8",
                        ApprovalStatus.APPROVED,
                        "As per circular 12/2026",
                        "{}",
                        "rule.approver",
                        Instant.now(),
                        null));

        verify(jdbc).update(contains("SET approval_state"), eq("APPROVED"), eq(8L));
        verify(audit)
                .record(
                        eq("rule.approver"),
                        argThat(
                                e ->
                                        "APPROVED".equals(e.newValue())
                                                && "As per circular 12/2026".equals(e.reason())));
    }
}
