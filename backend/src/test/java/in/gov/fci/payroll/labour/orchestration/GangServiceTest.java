package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.labour.business.LabourRules.GangRole;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-09 Gang / Shed Structure refusals and effects (plan 20; SRS-2-1, SRS-2-7, SRS-2-31, SRS-2-46,
 * SRS-2-48; BR-LABM-001, BR-LABM-006; LC-11): every defect is named, and an approval writes only
 * its own kind of change.
 */
class GangServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final long GANG = 5L;
    private static final long DEPOT = 7L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);
    private final ObjectMapper json = JsonMapper.builder().build();
    private final AccessContext context = mock(AccessContext.class);
    private final GangService service =
            new GangService(
                    jdbc,
                    mock(NamedParameterJdbcTemplate.class),
                    policy,
                    org,
                    approvals,
                    parameters,
                    audit,
                    json);
    private final OrgLocation office = mock(OrgLocation.class);

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(TODAY);
        when(office.getId()).thenReturn(3L);
        when(org.cycleUnitOf(DEPOT)).thenReturn(Optional.of(office));
    }

    private void gang(String type, String status) {
        Map<String, Object> g = new HashMap<>();
        g.put("GANG_ID", GANG);
        g.put("DEPOT_LOCATION_ID", DEPOT);
        g.put("GANG_TYPE", type);
        g.put("GANG_STATUS", status);
        g.put("PAYROLL_TYPE", "DEPT_LABOUR");
        when(jdbc.queryForList(contains("FROM labour_gang WHERE gang_id"), any(Object[].class)))
                .thenReturn(List.of(g));
    }

    private void profile(String status, Long depot, String type, Date retirement) {
        Map<String, Object> p = new HashMap<>();
        p.put("LABOUR_STATUS", status);
        p.put("DEPOT_LOCATION_ID", depot);
        p.put("PAYROLL_TYPE", type);
        p.put("RETIREMENT_DATE", retirement);
        when(jdbc.queryForList(
                        contains("FROM labour_profile p JOIN employee"), any(Object[].class)))
                .thenReturn(List.of(p));
    }

    private void count(String sqlPart, Integer n) {
        when(jdbc.queryForObject(contains(sqlPart), eq(Integer.class), any(Object[].class)))
                .thenReturn(n);
    }

    private static void defect(ThrowingCallable call, String expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(String.valueOf(e.getMessageArgs()[0]))
                                        .startsWith(expected));
    }

    // ---------------------------------------------------------------- add a member (SRS-2-31)

    @Test
    void SRS_2_31_aMemberJoinsOnlyAnActiveGangFromTodayWithAReason() {
        gang("HANDLING", "END_DATED");
        defect(() -> service.addMember(context, GANG, 11L, TODAY, "x"), "GANG_NOT_ACTIVE");
        gang("HANDLING", "ACTIVE");
        defect(() -> service.addMember(context, GANG, 11L, TODAY, " "), "REASON");
        defect(() -> service.addMember(context, GANG, 11L, TODAY, null), "REASON");
        defect(() -> service.addMember(context, GANG, 11L, TODAY, "x".repeat(501)), "REASON");
        defect(() -> service.addMember(context, GANG, 11L, null, "Join"), "EFFECTIVE_FROM");
        defect(
                () -> service.addMember(context, GANG, 11L, TODAY.minusDays(1), "Join"),
                "EFFECTIVE_FROM");
        when(org.cycleUnitOf(DEPOT)).thenReturn(Optional.empty());
        defect(() -> service.addMember(context, GANG, 11L, TODAY, "Join"), "DEPOT_OFFICE");
    }

    @Test
    void SRS_2_31_aMemberIsActiveLabourOfTheDepotAndTypeNotRetiredAndInNoGang() {
        gang("HANDLING", "ACTIVE");
        when(jdbc.queryForList(
                        contains("FROM labour_profile p JOIN employee"), any(Object[].class)))
                .thenReturn(List.of());
        defect(() -> service.addMember(context, GANG, 11L, TODAY, "Join"), "MEMBER_UNKNOWN");
        profile("SEPARATED", DEPOT, "DEPT_LABOUR", null);
        defect(() -> service.addMember(context, GANG, 11L, TODAY, "Join"), "MEMBER_NOT_OF_DEPOT");
        profile("ACTIVE", null, "DEPT_LABOUR", null);
        defect(() -> service.addMember(context, GANG, 11L, TODAY, "Join"), "MEMBER_NOT_OF_DEPOT");
        profile("ACTIVE", 99L, "DEPT_LABOUR", null);
        defect(() -> service.addMember(context, GANG, 11L, TODAY, "Join"), "MEMBER_NOT_OF_DEPOT");
        profile("ACTIVE", DEPOT, "NWNP_LABOUR", null);
        defect(() -> service.addMember(context, GANG, 11L, TODAY, "Join"), "MEMBER_NOT_OF_DEPOT");
        profile("ACTIVE", DEPOT, "DEPT_LABOUR", Date.valueOf(TODAY.minusDays(1)));
        defect(() -> service.addMember(context, GANG, 11L, TODAY, "Join"), "MEMBER_RETIRED");
        profile("ACTIVE", DEPOT, "DEPT_LABOUR", Date.valueOf(TODAY.plusYears(5)));
        count("labour_gang_membership WHERE employee_id", 1);
        defect(
                () -> service.addMember(context, GANG, 11L, TODAY, "Join"),
                "MEMBER_IN_ANOTHER_GANG");
    }

    @Test
    void SRS_2_46_aLabourJoiningThePoolIsAncillaryAndGoesToTheChecker() throws Exception {
        gang("ANCILLARY_POOL", "ACTIVE");
        profile("ACTIVE", DEPOT, "DEPT_LABOUR", null);
        count("labour_gang_membership WHERE employee_id", null);
        service.addMember(context, GANG, 11L, TODAY, " Light work ");
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(context), sent.capture(), eq(null));
        assertThat(sent.getValue().entityKey()).isEqualTo(GANG + ":11");
        assertThat(json.readTree(sent.getValue().payload()).path("gang").path("members").get(0))
                .hasToString("{\"employeeId\":11,\"role\":\"ANCILLARY\"}");
    }

    // ---------------------------------------------------------------- leader change (SRS-2-1)

    @Test
    void SRS_2_1_aNewLeaderIsAHandlingMemberOfAnActiveHandlingGang() {
        gang("ANCILLARY_POOL", "ACTIVE");
        defect(
                () -> service.changeLeader(context, GANG, GangRole.SARDAR, 11L, TODAY, "x"),
                "NOT_AN_ACTIVE_HANDLING_GANG");
        gang("HANDLING", "END_DATED");
        defect(
                () -> service.changeLeader(context, GANG, GangRole.SARDAR, 11L, TODAY, "x"),
                "NOT_AN_ACTIVE_HANDLING_GANG");
        gang("HANDLING", "ACTIVE");
        defect(
                () -> service.changeLeader(context, GANG, GangRole.HANDLING, 11L, TODAY, "x"),
                "ROLE");
        defect(
                () -> service.changeLeader(context, GANG, GangRole.MANDAL, 11L, TODAY, ""),
                "REASON");
        defect(
                () -> service.changeLeader(context, GANG, GangRole.MANDAL, 11L, null, "x"),
                "EFFECTIVE_FROM");
        defect(
                () ->
                        service.changeLeader(
                                context, GANG, GangRole.MANDAL, 11L, TODAY.minusDays(1), "x"),
                "EFFECTIVE_FROM");
        count("gang_role = 'HANDLING'", null);
        defect(
                () -> service.changeLeader(context, GANG, GangRole.MANDAL, 11L, TODAY, "x"),
                "NOT_A_HANDLING_MEMBER");
        count("gang_role = 'HANDLING'", 0);
        defect(
                () -> service.changeLeader(context, GANG, GangRole.MANDAL, 11L, TODAY, "x"),
                "NOT_A_HANDLING_MEMBER");
        count("gang_role = 'HANDLING'", 1);
        service.changeLeader(context, GANG, GangRole.MANDAL, 11L, TODAY, "Promoted");
        verify(approvals).submit(eq(context), any(Submission.class), eq(null));
    }

    // ---------------------------------------------------------------- end date (SRS-2-7)

    @Test
    void SRS_2_7_aGangEndsWithoutLaterSlipsOrMembersFromYesterdayOn() {
        gang("ANCILLARY_POOL", "ACTIVE");
        defect(() -> service.endDate(context, GANG, TODAY, "x"), "NOT_AN_ACTIVE_HANDLING_GANG");
        gang("HANDLING", "END_DATED");
        defect(() -> service.endDate(context, GANG, TODAY, "x"), "NOT_AN_ACTIVE_HANDLING_GANG");
        gang("HANDLING", "ACTIVE");
        defect(() -> service.endDate(context, GANG, TODAY, null), "REASON");
        defect(() -> service.endDate(context, GANG, null, "x"), "END_DATE");
        defect(() -> service.endDate(context, GANG, TODAY.minusDays(2), "x"), "END_DATE");
        count("FROM work_slip WHERE gang_id", 2);
        defect(() -> service.endDate(context, GANG, TODAY, "x"), "WORK_SLIPS_AFTER");
        count("FROM work_slip WHERE gang_id", null);
        count("labour_gang_membership WHERE gang_id", 1);
        defect(() -> service.endDate(context, GANG, TODAY, "x"), "MEMBERS_AFTER");
        count("labour_gang_membership WHERE gang_id", null);
        service.endDate(context, GANG, TODAY.minusDays(1), "Shed closed");
        verify(approvals).submit(eq(context), any(Submission.class), eq(null));
    }

    // ---------------------------------------------------------------- strength (SRS-2-48)

    @Test
    void SRS_2_48_aStrengthIsOneTo9999FromTodayOnAnActiveHandlingGang() {
        gang("ANCILLARY_POOL", "ACTIVE");
        defect(() -> service.strength(context, GANG, 5, TODAY, "x"), "NOT_AN_ACTIVE_HANDLING_GANG");
        gang("HANDLING", "END_DATED");
        defect(() -> service.strength(context, GANG, 5, TODAY, "x"), "NOT_AN_ACTIVE_HANDLING_GANG");
        gang("HANDLING", "ACTIVE");
        defect(() -> service.strength(context, GANG, 0, TODAY, "x"), "STRENGTH");
        defect(() -> service.strength(context, GANG, 10_000, TODAY, "x"), "STRENGTH");
        defect(() -> service.strength(context, GANG, 5, null, "x"), "STRENGTH");
        defect(() -> service.strength(context, GANG, 5, TODAY.minusDays(1), "x"), "STRENGTH");
        defect(() -> service.strength(context, GANG, 5, TODAY, " "), "REASON");
        service.strength(context, GANG, 9_999, TODAY, "Rake volume");
        verify(approvals).submit(eq(context), any(Submission.class), eq(null));
    }

    // ---------------------------------------------------------------- a new gang (SRS-2-1)

    private static GangService.NewGang newGang(
            String type, String name, Integer strength, LocalDate from, Long depot) {
        return new GangService.NewGang(
                depot,
                type,
                name,
                strength,
                from,
                List.of(
                        new GangService.Member(11L, GangRole.SARDAR),
                        new GangService.Member(12L, GangRole.MANDAL)),
                "New gang");
    }

    private void depot(LocationType type) {
        OrgLocation d = mock(OrgLocation.class);
        when(d.getId()).thenReturn(DEPOT);
        when(d.getType()).thenReturn(type);
        when(org.location(DEPOT)).thenReturn(Optional.of(d));
    }

    @Test
    void SRS_2_1_aNewGangNeedsItsTypeNameDateStrengthReasonAndADepot() {
        defect(() -> service.create(context, null), "PAYROLL_TYPE");
        defect(
                () -> service.create(context, newGang("CASUAL", "G", null, TODAY, DEPOT)),
                "PAYROLL_TYPE");
        defect(
                () -> service.create(context, newGang("DEPT_LABOUR", " ", null, TODAY, DEPOT)),
                "NAME");
        defect(
                () -> service.create(context, newGang("DEPT_LABOUR", "G1", null, null, DEPOT)),
                "EFFECTIVE_FROM");
        defect(
                () ->
                        service.create(
                                context,
                                newGang("DEPT_LABOUR", "G1", null, TODAY.minusDays(1), DEPOT)),
                "EFFECTIVE_FROM");
        defect(
                () -> service.create(context, newGang("DEPT_LABOUR", "G1", 0, TODAY, DEPOT)),
                "STRENGTH");
        defect(
                () -> service.create(context, newGang("DEPT_LABOUR", "G1", 10_000, TODAY, DEPOT)),
                "STRENGTH");
        when(org.location(anyLong())).thenReturn(Optional.empty());
        defect(
                () -> service.create(context, newGang("DEPT_LABOUR", "G1", 5, TODAY, null)),
                "DEPOT");
        depot(LocationType.DO);
        defect(
                () -> service.create(context, newGang("DEPT_LABOUR", "G1", 5, TODAY, DEPOT)),
                "DEPOT");
        depot(LocationType.DEPOT);
        when(org.cycleUnitOf(DEPOT)).thenReturn(Optional.empty());
        defect(
                () -> service.create(context, newGang("DEPT_LABOUR", "G1", 5, TODAY, DEPOT)),
                "DEPOT_OFFICE");
    }

    @Test
    void SRS_2_1_aNewGangHasOneSardarOneMandalAFreeNameAndEachMemberOnce() {
        depot(LocationType.DEPOT);
        GangService.NewGang noLeaders =
                new GangService.NewGang(DEPOT, "DEPT_LABOUR", "G1", 5, TODAY, null, "New gang");
        defect(() -> service.create(context, noLeaders), "");
        count("UPPER(gang_name)", 1);
        defect(
                () -> service.create(context, newGang("DEPT_LABOUR", "G1", 5, TODAY, DEPOT)),
                "NAME_TAKEN");
        count("UPPER(gang_name)", null);
        GangService.NewGang twice =
                new GangService.NewGang(
                        DEPOT,
                        "DEPT_LABOUR",
                        "G1",
                        5,
                        TODAY,
                        List.of(
                                new GangService.Member(11L, GangRole.SARDAR),
                                new GangService.Member(11L, GangRole.MANDAL)),
                        "New gang");
        defect(() -> service.create(context, twice), "MEMBER_TWICE");
        profile("ACTIVE", DEPOT, "DEPT_LABOUR", null);
        count("labour_gang_membership WHERE employee_id", 0);
        service.create(context, newGang("DEPT_LABOUR", "G1", 6, TODAY, DEPOT));
        verify(approvals).submit(eq(context), any(Submission.class), eq(null));
    }

    // M-09 l.4498: Sanctioned Strength is mandatory for a handling gang (1 to 9999).
    @Test
    void BR_LABM_011_aHandlingGangNeedsItsSanctionedStrength() {
        depot(LocationType.DEPOT);
        defect(
                () -> service.create(context, newGang("DEPT_LABOUR", "G1", null, TODAY, DEPOT)),
                "STRENGTH");
        defect(
                () -> service.create(context, newGang("DEPT_LABOUR", "G1", -1, TODAY, DEPOT)),
                "STRENGTH");
        profile("ACTIVE", DEPOT, "DEPT_LABOUR", null);
        count("labour_gang_membership WHERE employee_id", 0);
        service.create(context, newGang("DEPT_LABOUR", "G1", 1, TODAY, DEPOT));
        service.create(context, newGang("DEPT_LABOUR", "G2", 9999, TODAY, DEPOT));
        verify(approvals, times(2)).submit(eq(context), any(Submission.class), eq(null));
    }

    private void pending(String... payloads) {
        when(jdbc.queryForList(
                        contains("FROM approval_queue WHERE entity_name"),
                        eq(String.class),
                        any(Object[].class)))
                .thenReturn(List.of(payloads));
    }

    // SRS-2-31 "unique within a depot": a name waiting for the Checker is taken too.
    @Test
    void BR_LABM_002_aNameHeldByAnActiveOrPendingGangOfTheDepotIsRefused() throws Exception {
        depot(LocationType.DEPOT);
        profile("ACTIVE", DEPOT, "DEPT_LABOUR", null);
        count("labour_gang_membership WHERE employee_id", 0);
        String waiting =
                json.writeValueAsString(
                        proposal("CREATE", newGang("DEPT_LABOUR", " shed one ", 5, TODAY, DEPOT)));
        pending("{", json.writeValueAsString(proposal("CREATE", null)), waiting);
        defect(
                () -> service.create(context, newGang("DEPT_LABOUR", "SHED ONE", 5, TODAY, DEPOT)),
                "NAME_TAKEN");
        service.create(context, newGang("DEPT_LABOUR", "Shed Two", 5, TODAY, DEPOT));
        verify(approvals).submit(eq(context), any(Submission.class), eq(null));
    }

    @Test
    void BR_LABM_002_theSameNameInAnotherDepotOrWithoutANameIsNotTaken() throws Exception {
        depot(LocationType.DEPOT);
        profile("ACTIVE", DEPOT, "DEPT_LABOUR", null);
        count("labour_gang_membership WHERE employee_id", 0);
        pending(
                json.writeValueAsString(
                        proposal("CREATE", newGang("DEPT_LABOUR", "Shed One", 5, TODAY, 999L))),
                json.writeValueAsString(
                        proposal("CREATE", newGang("DEPT_LABOUR", null, 5, TODAY, DEPOT))));
        service.create(context, newGang("DEPT_LABOUR", "Shed One", 5, TODAY, DEPOT));
        verify(approvals).submit(eq(context), any(Submission.class), eq(null));
    }

    @Test
    void BR_LABM_002_theApprovalChecksTheNameAgain() throws Exception {
        GangService.Proposal create =
                new GangService.Proposal(
                        "CREATE", newGang("DEPT_LABOUR", "G1", 6, TODAY, DEPOT), null);
        count("UPPER(gang_name)", 1);
        defect(
                () -> service.on(decided(GangService.ENTITY, ApprovalStatus.APPROVED, create)),
                "NAME_TAKEN");
        verify(jdbc, never()).update(contains("INSERT INTO labour_gang ("), any(Object[].class));
    }

    // ---------------------------------------------------------------- the Checker's approval

    private ApprovalDecided decided(String entity, ApprovalStatus outcome, Object proposal)
            throws Exception {
        return new ApprovalDecided(
                41L,
                "M-09",
                entity,
                "k",
                outcome,
                null,
                proposal == null ? "{" : json.writeValueAsString(proposal),
                "checker",
                Instant.parse("2026-10-28T05:00:00Z"),
                null);
    }

    private static GangService.Proposal proposal(String kind, GangService.NewGang g) {
        return new GangService.Proposal(kind, g, GANG);
    }

    private static GangService.NewGang change(Integer strength, GangService.Member m) {
        return new GangService.NewGang(
                null, null, null, strength, TODAY, m == null ? List.of() : List.of(m), "Why");
    }

    @Test
    void SRS_2_31_onlyAnApprovedGangDecisionIsAppliedAndAnUnreadableOneFails() throws Exception {
        service.on(decided("labour_transfer", ApprovalStatus.APPROVED, proposal("ADD", null)));
        service.on(decided(GangService.ENTITY, ApprovalStatus.REJECTED, proposal("ADD", null)));
        verify(jdbc, never()).update(any(String.class), any(Object[].class));
        assertThatThrownBy(
                        () ->
                                service.on(
                                        decided(GangService.ENTITY, ApprovalStatus.APPROVED, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void SRS_2_31_anApprovedJoinEndDateAndStrengthAreEachWritten() throws Exception {
        service.on(
                decided(
                        GangService.ENTITY,
                        ApprovalStatus.APPROVED,
                        proposal(
                                "ADD",
                                change(null, new GangService.Member(11L, GangRole.HANDLING)))));
        verify(jdbc).update(contains("INSERT INTO labour_gang_membership"), any(Object[].class));
        service.on(
                decided(
                        GangService.ENTITY,
                        ApprovalStatus.APPROVED,
                        proposal("END", change(null, null))));
        verify(jdbc).update(contains("gang_status = 'END_DATED'"), any(Object[].class));
        service.on(
                decided(
                        GangService.ENTITY,
                        ApprovalStatus.APPROVED,
                        proposal("STRENGTH", change(8, null))));
        verify(jdbc).update(contains("INSERT INTO labour_gang_strength"), any(Object[].class));
    }

    @Test
    void SRS_2_1_anApprovedLeaderChangeMakesTheOutgoingLeaderHandlingLabour() throws Exception {
        when(jdbc.queryForList(
                        contains("gang_role = ? AND effective_from"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of(21L));
        service.on(
                decided(
                        GangService.ENTITY,
                        ApprovalStatus.APPROVED,
                        proposal(
                                "LEADER",
                                change(null, new GangService.Member(11L, GangRole.SARDAR)))));
        verify(jdbc, times(2))
                .update(
                        contains("UPDATE labour_gang_membership SET effective_to"),
                        any(Object[].class));
        verify(jdbc, times(2))
                .update(contains("INSERT INTO labour_gang_membership"), any(Object[].class));
    }

    @Test
    void SRS_2_46_theFirstGangOfADepotCreatesItsPoolAndAMissingRowFails() throws Exception {
        GangService.Proposal create =
                new GangService.Proposal(
                        "CREATE", newGang("DEPT_LABOUR", "G1", null, TODAY, DEPOT), null);
        count("FROM ancillary_pool WHERE depot_location_id", 0);
        when(jdbc.queryForMap(
                        contains("SELECT location_code FROM org_location"), any(Object[].class)))
                .thenReturn(Map.of("LOCATION_CODE", "DP-DL01-A-VERY-LONG-DEPOT"));
        when(jdbc.queryForObject(
                        contains("COUNT(*) FROM labour_gang WHERE depot"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(null);
        when(jdbc.queryForObject(
                        contains("SELECT gang_id FROM labour_gang WHERE gang_code"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(30L, (Long) null);
        assertThatThrownBy(
                        () ->
                                service.on(
                                        decided(
                                                GangService.ENTITY,
                                                ApprovalStatus.APPROVED,
                                                create)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
        verify(jdbc).update(contains("INSERT INTO ancillary_pool"), any(Object[].class));
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).update(contains("'Ancillary Pool'"), args.capture());
        assertThat(String.valueOf(args.getValue()[0])).hasSize(20);
    }

    @Test
    void SRS_2_1_anApprovedGangWithAPoolAlreadyWritesTheGangMembersAndStrength() throws Exception {
        GangService.Proposal create =
                new GangService.Proposal(
                        "CREATE", newGang("DEPT_LABOUR", "G1", 6, TODAY, DEPOT), null);
        count("FROM ancillary_pool WHERE depot_location_id", 1);
        when(jdbc.queryForMap(
                        contains("SELECT location_code FROM org_location"), any(Object[].class)))
                .thenReturn(Map.of("LOCATION_CODE", "DP-DL01"));
        when(jdbc.queryForObject(
                        contains("SELECT gang_id FROM labour_gang WHERE gang_code"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(31L);
        when(jdbc.queryForObject(
                        contains("MAX(version_no)"), eq(Integer.class), any(Object[].class)))
                .thenReturn(2);
        service.on(decided(GangService.ENTITY, ApprovalStatus.APPROVED, create));
        verify(jdbc, never()).update(contains("INSERT INTO ancillary_pool"), any(Object[].class));
        verify(jdbc, times(2))
                .update(contains("INSERT INTO labour_gang_membership"), any(Object[].class));
        verify(jdbc).update(contains("INSERT INTO labour_gang_strength"), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_2_31_theMembersReadTheirNameWithOrWithoutASurnameAndAnUnknownGangIsNotFound()
            throws Exception {
        gang("HANDLING", "ACTIVE");
        ArgumentCaptor<org.springframework.jdbc.core.RowMapper<GangService.MemberRow>> mapper =
                ArgumentCaptor.forClass(org.springframework.jdbc.core.RowMapper.class);
        when(jdbc.query(
                        contains("FROM labour_gang_membership m JOIN employee"),
                        mapper.capture(),
                        any(Object[].class)))
                .thenReturn(List.of());
        service.members(context, GANG);
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getString("first_name")).thenReturn("Ram");
        when(rs.getString("labour_number")).thenReturn("L1");
        when(rs.getString("gang_role")).thenReturn("SARDAR");
        when(rs.getString("last_name")).thenReturn(null, " ", "Kumar");
        assertThat(mapper.getValue().mapRow(rs, 0).name()).isEqualTo("Ram");
        assertThat(mapper.getValue().mapRow(rs, 1).name()).isEqualTo("Ram");
        assertThat(mapper.getValue().mapRow(rs, 2).name()).isEqualTo("Ram Kumar");
        when(jdbc.queryForList(contains("FROM labour_gang WHERE gang_id"), any(Object[].class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.members(context, 404L)).isInstanceOf(FciException.class);
    }

    @Test
    void SRS_2_46_aGangProposedBeforeStrengthWasRequiredAndAPoolCountThatIsNullStillForms()
            throws Exception {
        GangService.Proposal create =
                new GangService.Proposal(
                        "CREATE", newGang("DEPT_LABOUR", "G1", null, TODAY, DEPOT), null);
        count("FROM ancillary_pool WHERE depot_location_id", null);
        when(jdbc.queryForMap(
                        contains("SELECT location_code FROM org_location"), any(Object[].class)))
                .thenReturn(Map.of("LOCATION_CODE", "DP-DL01"));
        when(jdbc.queryForObject(
                        contains("SELECT gang_id FROM labour_gang WHERE gang_code"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(31L);
        service.on(decided(GangService.ENTITY, ApprovalStatus.APPROVED, create));
        verify(jdbc).update(contains("INSERT INTO ancillary_pool"), any(Object[].class));
        verify(jdbc, never())
                .update(contains("INSERT INTO labour_gang_strength"), any(Object[].class));
    }

    @Test
    void aProposalThatCannotBeWrittenIsAnError() {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any()))
                .thenThrow(new tools.jackson.core.exc.StreamWriteException(null, "no"));
        GangService failing =
                new GangService(
                        jdbc,
                        mock(NamedParameterJdbcTemplate.class),
                        policy,
                        org,
                        approvals,
                        parameters,
                        audit,
                        broken);
        gang("HANDLING", "ACTIVE");
        assertThatThrownBy(() -> failing.strength(context, GANG, 5, TODAY, "Why"))
                .isInstanceOf(IllegalStateException.class);
    }
}
