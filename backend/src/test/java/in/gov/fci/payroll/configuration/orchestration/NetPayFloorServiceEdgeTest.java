package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.NetPayFloorRule;
import in.gov.fci.payroll.configuration.business.model.PayComponent;
import in.gov.fci.payroll.configuration.orchestration.NetPayFloorService.ExclusionProposal;
import in.gov.fci.payroll.configuration.orchestration.NetPayFloorService.FloorVersion;
import in.gov.fci.payroll.configuration.orchestration.NetPayFloorService.ProposedFloor;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-14 net-pay floors and supplementary exclusions beyond the happy path (SRS-16-13): a payroll
 * type outside the catalogue, a proposal without its date, where it is routed from outside
 * Headquarters, a version without an approval, an exclusion list with a hole or a code that is not
 * catalogued or no longer active, and the approved exclusion change that flips only the components
 * that differ and names "Approved" when the checker gave no reason.
 */
class NetPayFloorServiceEdgeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private static final LocalDate FROM = LocalDate.of(2027, 4, 1);
    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    private final ConfigurationRepositories.NetPayFloors floors =
            mock(ConfigurationRepositories.NetPayFloors.class);
    private final ConfigurationRepositories.PayComponents components =
            mock(ConfigurationRepositories.PayComponents.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Authorization policy = mock(Authorization.class);
    private final SystemParameters settings = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);
    private final JsonMapper json = JsonMapper.builder().build();

    private final NetPayFloorService service =
            new NetPayFloorService(
                    floors,
                    components,
                    statuses,
                    approvals,
                    policy,
                    settings,
                    mock(MasterAudit.class),
                    audit,
                    json,
                    mock(
                            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
                                    .class));

    private final AccessContext headquarters = context(1L);
    private final AccessContext zonal = context(300L);

    NetPayFloorServiceEdgeTest() {
        when(settings.today()).thenReturn(TODAY);
        when(policy.isAtHeadquarters(headquarters)).thenReturn(true);
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(77L, ApprovalStatus.PENDING_APPROVAL, null));
        when(floors.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(statuses.of(any())).thenReturn(Map.of());
    }

    private static AccessContext context(Long locationId) {
        return new AccessContext(
                2L,
                "admin@x",
                "Admin",
                "s",
                "P",
                locationId,
                null,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of(locationId));
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static PayComponent component(int code, boolean excluded, boolean active) {
        PayComponent c = mock(PayComponent.class);
        when(c.getLegacyPayCode()).thenReturn(code);
        when(c.isSuppExcludedFlag()).thenReturn(excluded);
        when(c.isActiveOn(TODAY)).thenReturn(active);
        return c;
    }

    private static ProposedFloor floor(LocalDate from) {
        return new ProposedFloor(
                from, "ABSOLUTE_AMOUNT", new BigDecimal("1500"), null, "Circular 1", true, "r");
    }

    @Test
    void SRS_16_13_aProposalNeedsACataloguedTypeARequestAndADateAndIsRoutedFromItsOffice() {
        refused(
                () -> service.propose(headquarters, "BOGUS", floor(FROM), null),
                ApiErrorCode.MASTER_NOT_IN_CATALOGUE);
        refused(() -> service.history(headquarters, "BOGUS"), ApiErrorCode.MASTER_NOT_IN_CATALOGUE);
        refused(
                () -> service.propose(headquarters, "STAFF", null, null),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.propose(headquarters, "STAFF", floor(null), null),
                CommonErrorCode.VALIDATION_FAILED);

        FloorVersion fromHq = service.propose(headquarters, "STAFF", floor(FROM), null);
        FloorVersion fromZone = service.propose(zonal, "DEPT_LABOUR", floor(FROM), null);
        assertThat(fromHq.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(fromZone.floorValue()).isEqualByComparingTo("1500");
        ArgumentCaptor<Submission> submitted = ArgumentCaptor.captor();
        verify(approvals, times(2)).submit(any(), submitted.capture(), isNull());
        assertThat(submitted.getAllValues())
                .extracting(Submission::locationId)
                .containsExactly(1L, null);

        // A version without an approval (seeded before approvals existed) has no status.
        when(floors.findByPayrollTypeOrderByEffectiveFromDesc("STAFF"))
                .thenReturn(
                        List.of(
                                NetPayFloorRule.propose(
                                        "STAFF",
                                        NetPayFloorRule.Basis.NONE,
                                        null,
                                        null,
                                        null,
                                        true,
                                        FROM,
                                        null)));
        assertThat(service.history(headquarters, "STAFF"))
                .extracting(FloorVersion::status)
                .containsExactly("NONE");
    }

    @Test
    void SRS_16_13_anExclusionListNeedsEveryCodeCataloguedAndActive() {
        refused(
                () -> service.proposeExclusions(headquarters, null, "r", null),
                CommonErrorCode.VALIDATION_FAILED);
        List<Integer> withHole = new ArrayList<>();
        withHole.add(101);
        withHole.add(null);
        refused(
                () -> service.proposeExclusions(headquarters, withHole, "r", null),
                CommonErrorCode.VALIDATION_FAILED);
        PayComponent inactive = component(101, false, false);
        when(components.findByLegacyPayCodeIn(any())).thenReturn(List.of(inactive));
        refused(
                () -> service.proposeExclusions(headquarters, List.of(101, 348), "r", null),
                ApiErrorCode.MASTER_COMPONENT_INVALID);
        refused(
                () -> service.proposeExclusions(headquarters, List.of(101), "r", null),
                ApiErrorCode.MASTER_COMPONENT_INVALID);
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_16_13_anApprovedExclusionChangeFlipsOnlyWhatDiffersAndNamesApprovedWithoutAReason() {
        String payload = json.writeValueAsString(new ExclusionProposal(List.of(101)));
        PayComponent basic = component(101, false, true);
        PayComponent benevolent = component(348, true, true);
        PayComponent unchanged = component(200, false, true);
        when(components.findAllByOrderByLegacyPayCode())
                .thenReturn(List.of(basic, benevolent, unchanged));

        service.on(
                decided(
                        NetPayFloorService.EXCLUSIONS,
                        ApprovalStatus.APPROVED,
                        90L,
                        null,
                        payload));

        verify(basic).setSuppExcluded(true);
        verify(benevolent).setSuppExcluded(false);
        verify(unchanged, never()).setSuppExcluded(anyBoolean());
        ArgumentCaptor<Collection<AuditService.Entry>> entries = ArgumentCaptor.captor();
        verify(audit).recordAll(eq("checker.hq"), entries.capture());
        assertThat(entries.getValue()).hasSize(2);
        assertThat(entries.getValue())
                .extracting(AuditService.Entry::reason)
                .containsOnly("Approved");
        assertThat(entries.getValue()).extracting(AuditService.Entry::approvalId).containsOnly(90L);

        // A refused exclusion change, or a floor decision for an unknown proposal, changes nothing.
        service.on(
                decided(
                        NetPayFloorService.EXCLUSIONS,
                        ApprovalStatus.REJECTED,
                        91L,
                        "no",
                        payload));
        service.on(decided(NetPayFloorService.ENTITY, ApprovalStatus.APPROVED, 92L, "ok", "{}"));
        verify(components, times(1)).findAllByOrderByLegacyPayCode();
        verify(floors).findByApprovalId(92L);
    }

    @Test
    void SRS_16_13_anApprovedExclusionChangeWithTheCheckersReasonIsAuditedWithIt() {
        String payload = json.writeValueAsString(new ExclusionProposal(List.of(348)));
        PayComponent benevolent = component(348, false, true);
        when(components.findAllByOrderByLegacyPayCode()).thenReturn(List.of(benevolent));

        service.on(
                decided(
                        NetPayFloorService.EXCLUSIONS,
                        ApprovalStatus.APPROVED,
                        93L,
                        "Benevolent fund is paid only in the regular run",
                        payload));

        verify(benevolent).setSuppExcluded(true);
        ArgumentCaptor<Collection<AuditService.Entry>> entries = ArgumentCaptor.captor();
        verify(audit).recordAll(eq("checker.hq"), entries.capture());
        assertThat(entries.getValue())
                .extracting(AuditService.Entry::reason)
                .containsOnly("Benevolent fund is paid only in the regular run");
    }

    private static ApprovalDecided decided(
            String entity, ApprovalStatus outcome, long id, String reason, String payload) {
        return new ApprovalDecided(
                id,
                "M-14",
                entity,
                "SUPPLEMENTARY",
                outcome,
                reason,
                payload,
                "checker.hq",
                NOW,
                7L);
    }

    /** SRS-16-13: an approved exclusion list that cannot be read changes no component. */
    @Test
    void SRS_16_13_anUnreadableApprovedExclusionListChangesNothing() {
        ApprovalDecided broken =
                decided(NetPayFloorService.EXCLUSIONS, ApprovalStatus.APPROVED, 94L, "ok", "{bad");

        assertThatThrownBy(() -> service.on(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("unreadable exclusion proposal")
                .hasCauseInstanceOf(tools.jackson.core.JacksonException.class);
        org.mockito.Mockito.verifyNoInteractions(audit);
        verify(components, never()).findAllByOrderByLegacyPayCode();
    }

    /** SRS-16-13: a rejected or returned exclusion list is never applied. */
    @Test
    void SRS_16_13_aNotApprovedExclusionListIsNeverApplied() {
        for (ApprovalStatus outcome :
                List.of(
                        ApprovalStatus.REJECTED,
                        ApprovalStatus.RETURNED,
                        ApprovalStatus.WITHDRAWN)) {
            service.on(
                    decided(
                            NetPayFloorService.EXCLUSIONS,
                            outcome,
                            95L,
                            "no",
                            "{\"payCodes\":[93]}"));
        }
        org.mockito.Mockito.verifyNoInteractions(audit);
        verify(components, never()).findAllByOrderByLegacyPayCode();
    }
}
