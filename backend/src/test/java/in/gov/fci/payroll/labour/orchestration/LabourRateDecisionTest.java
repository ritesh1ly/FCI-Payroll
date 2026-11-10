package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M-05 rate sets (Departmental / DPS and NWNP): a proposal without its parts, a depot that is not a
 * depot, the Rule Approver's decision either way, on a set no longer pending or of another screen
 * (plans 21 / 22; SRS-4-11, SRS-5-2; D21-4, D22-5).
 */
class LabourRateDecisionTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final LabourRateService rates =
            new LabourRateService(
                    jdbc, mock(Authorization.class), org, mock(MakerChecker.class), audit);
    private final NwnpRateService nwnp =
            new NwnpRateService(
                    jdbc,
                    mock(Authorization.class),
                    org,
                    mock(MakerChecker.class),
                    parameters,
                    audit);

    private static void defect(ThrowingCallable call, String expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(String.valueOf(e.getMessageArgs()[0])).isEqualTo(expected));
    }

    private static ApprovalDecided decided(
            String entity, String key, ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                61L,
                "M-05",
                entity,
                key,
                outcome,
                reason,
                "{}",
                "rule.approver",
                Instant.now(),
                null);
    }

    @Test
    void SRS_4_11_aProposalNeedsItsPartsAndARealDepot() {
        defect(() -> rates.propose(context, null), "PROPOSAL");
        OrgLocation hq = mock(OrgLocation.class);
        when(hq.getId()).thenReturn(1L);
        when(org.headquarters()).thenReturn(Optional.of(hq));
        OrgLocation office = mock(OrgLocation.class);
        when(office.getType()).thenReturn(LocationType.DO);
        when(org.location(9L)).thenReturn(Optional.of(office));
        defect(
                () ->
                        rates.propose(
                                context,
                                new LabourRateService.Proposal(
                                        "DPS_LABOUR",
                                        9L,
                                        LocalDate.of(2027, 1, 1),
                                        List.of(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        "r")),
                "DEPOT");
        defect(
                () ->
                        rates.propose(
                                context,
                                new LabourRateService.Proposal(
                                        "CASUAL", null, null, null, null, null, null, null, "r")),
                "PAYROLL_TYPE");
        defect(() -> nwnp.propose(context, null), "DEPOT");
        defect(
                () ->
                        nwnp.propose(
                                context,
                                new NwnpRateService.Proposal(null, "ASOR", null, null, null, "r")),
                "DEPOT");
    }

    @Test
    void SRS_4_11_theApproverDecidesASetEitherWayOnceAndOnlyItsOwn() {
        rates.on(decided("rate_set", "5", ApprovalStatus.APPROVED, null));
        rates.on(decided(LabourRateService.ENTITY, "5", ApprovalStatus.APPROVED, null));
        verify(audit, never()).record(anyString(), any());
        Map<String, Object> set = new HashMap<>();
        set.put("ENGINE_CODE", "DPS_LABOUR");
        set.put("LOCATION_ID", 7L);
        set.put("EFFECTIVE_FROM", Date.valueOf("2027-01-01"));
        when(jdbc.queryForList(contains("FROM labour_min_wage"), eq(61L))).thenReturn(List.of(set));
        rates.on(decided(LabourRateService.ENTITY, "5", ApprovalStatus.APPROVED, null));
        verify(jdbc)
                .update(
                        contains("SET effective_to"),
                        eq(Date.valueOf("2026-12-31")),
                        eq("DPS_LABOUR"),
                        eq(7L),
                        any());
        rates.on(decided(LabourRateService.ENTITY, "5", ApprovalStatus.REJECTED, "Wrong rates"));
        verify(audit)
                .record(
                        eq("rule.approver"),
                        argThat(
                                e ->
                                        "REJECTED".equals(e.newValue())
                                                && "Wrong rates".equals(e.reason())));
    }

    @Test
    void SRS_5_2_anNwnpSetIsDecidedOnlyWhilePending() {
        nwnp.on(decided("rate_set", "8", ApprovalStatus.APPROVED, null));
        nwnp.on(decided(NwnpRateService.ENTITY, "8", ApprovalStatus.APPROVED, null));
        Map<String, Object> done = new HashMap<>();
        done.put("RATE_SET_CODE", "NWNP-7-ASOR");
        done.put("EFFECTIVE_FROM", Date.valueOf("2027-01-01"));
        done.put("APPROVAL_STATE", "APPROVED");
        when(jdbc.queryForList(contains("FROM rate_set WHERE"), eq(8L))).thenReturn(List.of(done));
        nwnp.on(decided(NwnpRateService.ENTITY, "8", ApprovalStatus.APPROVED, null));
        verify(audit, never()).record(anyString(), any());
        done.put("APPROVAL_STATE", "PENDING_APPROVAL");
        nwnp.on(decided(NwnpRateService.ENTITY, "8", ApprovalStatus.REJECTED, null));
        verify(jdbc).update(contains("SET approval_state"), eq("REJECTED"), eq(8L));
        verify(jdbc, never())
                .queryForList(
                        contains("rate_set_id <> ?"), eq(Long.class), any(), anyLong(), any());
        verify(audit)
                .record(
                        eq("rule.approver"),
                        argThat(
                                e ->
                                        "REJECTED".equals(e.newValue())
                                                && "REJECTED".equals(e.reason())));
    }
}
