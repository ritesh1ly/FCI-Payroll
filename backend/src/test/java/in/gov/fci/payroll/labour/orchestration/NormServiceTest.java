package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.labour.business.NormRules.Norms;
import in.gov.fci.payroll.labour.business.NormRules.Slab;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * M-06 Depot Incentive Norms refusals and effects (plan 20 phase 20d; SRS-3-12, SRS-3-6): a set for
 * a depot, its norms and slab bands, a reason, one pending at a time, after the latest set.
 */
class NormServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final long DEPOT = 7L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final LabourDepots depots = mock(LabourDepots.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final in.gov.fci.payroll.configuration.api.Circulars circulars =
            mock(in.gov.fci.payroll.configuration.api.Circulars.class);
    private final NormService service =
            new NormService(
                    jdbc,
                    mock(NamedParameterJdbcTemplate.class),
                    mock(Authorization.class),
                    org,
                    depots,
                    approvals,
                    parameters,
                    audit,
                    circulars);

    private static final Norms NORMS = new Norms(250L, 20L, 0L, 10L, new BigDecimal("8.5"));

    private static List<Slab> slabs() {
        return List.of(
                new Slab("BAND_1", 1, 1, 30L, new BigDecimal("8")),
                new Slab("BAND_1", 2, 31, null, new BigDecimal("15")));
    }

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(TODAY);
        depot(LocationType.DEPOT);
        SubmittedApproval item = mock(SubmittedApproval.class);
        when(item.id()).thenReturn(21L);
        when(approvals.submit(any(), any(Submission.class), any())).thenReturn(item);
    }

    private void depot(LocationType type) {
        OrgLocation d = mock(OrgLocation.class);
        when(d.getId()).thenReturn(DEPOT);
        when(d.getCode()).thenReturn("DP-DL01");
        when(d.getName()).thenReturn("Okhla");
        when(d.getType()).thenReturn(type);
        when(org.location(DEPOT)).thenReturn(Optional.of(d));
    }

    private static NormService.Proposal set(
            Long depot, Norms norms, List<Slab> slabs, String reason, String mou) {
        return new NormService.Proposal(depot, TODAY.plusDays(5), norms, mou, slabs, reason, 7L);
    }

    private static void defect(ThrowingCallable call, String expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(String.valueOf(e.getMessageArgs()[0])).isEqualTo(expected));
    }

    @Test
    void SRS_3_12_aSetNeedsADepotItsNormsSlabsAndAReason() {
        defect(() -> service.propose(context, null), "DEPOT");
        defect(() -> service.propose(context, set(null, NORMS, slabs(), "x", null)), "DEPOT");
        when(org.location(DEPOT)).thenReturn(Optional.empty());
        defect(() -> service.propose(context, set(DEPOT, NORMS, slabs(), "x", null)), "DEPOT");
        depot(LocationType.DO);
        defect(() -> service.propose(context, set(DEPOT, NORMS, slabs(), "x", null)), "DEPOT");
        depot(LocationType.DEPOT);
        defect(() -> service.propose(context, set(DEPOT, null, slabs(), "x", null)), "NORM");
        defect(
                () ->
                        service.propose(
                                context,
                                set(
                                        DEPOT,
                                        new Norms(null, 1L, 0L, 1L, BigDecimal.ONE),
                                        slabs(),
                                        "x",
                                        null)),
                "NORM");
        assertThatThrownBy(() -> service.propose(context, set(DEPOT, NORMS, List.of(), "x", null)))
                .isInstanceOf(FciException.class);
        defect(() -> service.propose(context, set(DEPOT, NORMS, slabs(), null, null)), "REASON");
        defect(() -> service.propose(context, set(DEPOT, NORMS, slabs(), " ", null)), "REASON");
        defect(
                () -> service.propose(context, set(DEPOT, NORMS, slabs(), "x".repeat(501), null)),
                "REASON");
    }

    @Test
    void SRS_3_12_oneSetWaitsAtATimeAndANewOneStartsAfterTheLatest() {
        when(jdbc.queryForObject(
                        contains("q.status = 'PENDING_APPROVAL'"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(1);
        defect(() -> service.propose(context, set(DEPOT, NORMS, slabs(), "x", null)), "PENDING");
        when(jdbc.queryForObject(
                        contains("q.status = 'PENDING_APPROVAL'"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(null);
        when(jdbc.queryForObject(
                        contains("MAX(n.effective_from)"),
                        eq(LocalDate.class),
                        any(Object[].class)))
                .thenReturn(TODAY.plusDays(10));
        assertThatThrownBy(() -> service.propose(context, set(DEPOT, NORMS, slabs(), "x", null)))
                .isInstanceOf(FciException.class);
        when(jdbc.queryForObject(
                        contains("MAX(n.effective_from)"),
                        eq(LocalDate.class),
                        any(Object[].class)))
                .thenReturn(null);
        when(jdbc.queryForObject(
                        contains("SELECT norm_id FROM labour_norm"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(null);
        assertThatThrownBy(() -> service.propose(context, set(DEPOT, NORMS, slabs(), "x", " ")))
                .isInstanceOf(IllegalStateException.class);
        when(jdbc.queryForObject(
                        contains("SELECT norm_id FROM labour_norm"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(31L);
        when(jdbc.queryForObject(
                        contains("MAX(version_no)"), eq(Integer.class), any(Object[].class)))
                .thenReturn(2);
        service.propose(context, set(DEPOT, NORMS, slabs(), "MoU 2026", " MOU/26 "));
        verify(jdbc).update(contains("UPDATE labour_norm SET approval_id"), eq(21L), eq(31L));
    }

    @Test
    void SRS_3_12_theNormSetLinksTheDepotsMouFromM25() {
        NormService.Proposal none =
                new NormService.Proposal(DEPOT, TODAY.plusDays(5), NORMS, null, slabs(), "x", null);
        defect(() -> service.propose(context, none), "MOU");
        doThrow(new FciException(in.gov.fci.payroll.platform.error.CommonErrorCode.NOT_FOUND))
                .when(circulars)
                .requireActive(7L);
        assertThatThrownBy(() -> service.propose(context, set(DEPOT, NORMS, slabs(), "x", null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(
                                                in.gov.fci.payroll.platform.error.CommonErrorCode
                                                        .NOT_FOUND));
        verify(jdbc, never()).update(contains("INSERT INTO labour_norm"), any(Object[].class));
    }

    @Test
    void SRS_3_12_anApprovedSetIsLinkedToItsMouAndARejectedOneIsNot() {
        when(jdbc.queryForMap(contains("FROM labour_norm WHERE norm_id"), any(Object[].class)))
                .thenReturn(
                        Map.of("DEPOT_LOCATION_ID", DEPOT, "EFFECTIVE_FROM", Date.valueOf(TODAY)));
        service.on(
                new ApprovalDecided(
                        41L,
                        "M-06",
                        NormService.ENTITY,
                        "31",
                        ApprovalStatus.REJECTED,
                        "Wrong MoU",
                        "{\"circularId\":7}",
                        "approver",
                        Instant.now(),
                        null));
        verifyNoInteractions(circulars);
        service.on(
                new ApprovalDecided(
                        42L,
                        "M-06",
                        NormService.ENTITY,
                        "31",
                        ApprovalStatus.APPROVED,
                        null,
                        "{\"circularId\":7}",
                        "approver",
                        Instant.now(),
                        null));
        verify(circulars)
                .link(
                        "approver",
                        7L,
                        in.gov.fci.payroll.configuration.api.Circulars.LinkType.MASTER_ENTRY,
                        NormService.ENTITY,
                        "31",
                        "M-06");
    }

    @Test
    void SRS_3_12_theDepotsAreTheSessionsOwn() {
        OrgLocation d = mock(OrgLocation.class);
        when(d.getId()).thenReturn(DEPOT);
        when(d.getCode()).thenReturn("DP-DL01");
        when(d.getName()).thenReturn("Okhla");
        when(depots.of(context, false, TODAY)).thenReturn(List.of(d));
        assertThat(service.depots(context))
                .containsExactly(new NormService.Depot(DEPOT, "DP-DL01", "Okhla"));
    }

    private ApprovalDecided decided(String entity, ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                41L, "M-06", entity, "31", outcome, reason, null, "approver", Instant.now(), null);
    }

    @Test
    void SRS_3_12_anApprovedSetEndsTheOneBeforeARejectedOneChangesNothing() {
        service.on(decided("labour_transfer", ApprovalStatus.APPROVED, null));
        verify(jdbc, never()).queryForMap(any(String.class), anyLong());
        when(jdbc.queryForMap(contains("FROM labour_norm WHERE norm_id"), any(Object[].class)))
                .thenReturn(
                        Map.of("DEPOT_LOCATION_ID", DEPOT, "EFFECTIVE_FROM", Date.valueOf(TODAY)));
        service.on(decided(NormService.ENTITY, ApprovalStatus.REJECTED, "Wrong MoU"));
        verify(jdbc, never())
                .update(contains("UPDATE labour_norm SET effective_to"), any(Object[].class));
        service.on(decided(NormService.ENTITY, ApprovalStatus.APPROVED, null));
        verify(jdbc).update(contains("UPDATE labour_norm SET effective_to"), any(Object[].class));
    }
}
