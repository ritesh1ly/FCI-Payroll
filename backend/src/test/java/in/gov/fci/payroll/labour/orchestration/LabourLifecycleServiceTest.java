package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.LabourPersons;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.persistence.GeneratedKeysStub;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-02 separation and Permanent Light Duty refusals and effects (plan 20 phase 20e; SRS-2-30,
 * SRS-2-49, SRS-10-19; D20-16, D20-17).
 */
class LabourLifecycleServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final long LABOUR = 11L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final LabourPersons persons = mock(LabourPersons.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);
    private final ObjectMapper json = JsonMapper.builder().build();
    private final AccessContext context = mock(AccessContext.class);
    private final LabourLifecycleService service =
            new LabourLifecycleService(
                    jdbc,
                    mock(Authorization.class),
                    org,
                    approvals,
                    persons,
                    parameters,
                    audit,
                    json,
                    Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(TODAY);
        Map<String, Object> labour = new HashMap<>();
        labour.put("LABOUR_NUMBER", "DPT-000011");
        labour.put("PAYROLL_TYPE", "DEPT_LABOUR");
        labour.put("DEPOT_LOCATION_ID", 7L);
        labour.put("LABOUR_STATUS", "ACTIVE");
        labour.put("DESIGNATION_ID", 3L);
        when(jdbc.queryForList(contains("AND p.labour_status = 'ACTIVE'"), any(Object[].class)))
                .thenReturn(List.of(labour));
        OrgLocation office = mock(OrgLocation.class);
        when(office.getId()).thenReturn(3L);
        when(org.cycleUnitOf(7L)).thenReturn(Optional.of(office));
        when(approvals.submit(any(), any(Submission.class), any()))
                .thenReturn(mock(SubmittedApproval.class));
    }

    private static void defect(ThrowingCallable call, String expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(String.valueOf(e.getMessageArgs()[0])).isEqualTo(expected));
    }

    private static LabourLifecycleService.Separation separation(
            String type, LocalDate date, String why) {
        return new LabourLifecycleService.Separation(
                type, date, why, "SO/12", TODAY.minusDays(1), "Area Manager");
    }

    private static LabourLifecycleService.Separation ordered(
            String ref, LocalDate orderDate, String authority) {
        return new LabourLifecycleService.Separation(
                "RESIGNATION", TODAY, "Left", ref, orderDate, authority);
    }

    // SRS l.4429 / NWNP l.4971: the separation starts on M-02 with the order attached.
    @Test
    void SRS_2_30_aSeparationNeedsItsOrder() {
        defect(
                () ->
                        service.separate(
                                context,
                                LABOUR,
                                new LabourLifecycleService.Separation("DEATH", TODAY, "x")),
                "ORDER");
        defect(() -> service.separate(context, LABOUR, ordered(" ", TODAY, "AM")), "ORDER");
        defect(() -> service.separate(context, LABOUR, ordered(null, TODAY, "AM")), "ORDER");
        defect(() -> service.separate(context, LABOUR, ordered("SO/1", null, "AM")), "ORDER");
        defect(() -> service.separate(context, LABOUR, ordered("SO/1", TODAY, " ")), "ORDER");
        defect(() -> service.separate(context, LABOUR, ordered("SO/1", TODAY, null)), "ORDER");
        defect(
                () -> service.separate(context, LABOUR, ordered("S".repeat(101), TODAY, "AM")),
                "ORDER");
        defect(
                () -> service.separate(context, LABOUR, ordered("SO/1", TODAY, "A".repeat(151))),
                "ORDER");
        defect(
                () -> service.separate(context, LABOUR, ordered("SO/1", TODAY.plusDays(1), "AM")),
                "ORDER_DATE");
        service.separate(context, LABOUR, ordered("S".repeat(100), TODAY, "A".repeat(150)));
        verify(approvals).submit(eq(context), any(Submission.class), eq(null));
    }

    @Test
    void SRS_2_30_aSeparationNeedsAKnownTypeAReasonADateThisMonthAndNoLaterSlip() {
        defect(() -> service.separate(context, LABOUR, null), "TYPE");
        defect(() -> service.separate(context, LABOUR, separation(null, TODAY, "x")), "TYPE");
        defect(
                () -> service.separate(context, LABOUR, separation("DISMISSAL", TODAY, "x")),
                "TYPE");
        defect(() -> service.separate(context, LABOUR, separation("DEATH", TODAY, " ")), "REASON");
        defect(() -> service.separate(context, LABOUR, separation("DEATH", TODAY, null)), "REASON");
        defect(
                () ->
                        service.separate(
                                context, LABOUR, separation("DEATH", TODAY, "x".repeat(501))),
                "REASON");
        defect(() -> service.separate(context, LABOUR, separation("DEATH", null, "x")), "DATE");
        defect(
                () ->
                        service.separate(
                                context, LABOUR, separation("DEATH", TODAY.minusMonths(1), "x")),
                "DATE");
        when(jdbc.queryForObject(
                        contains("FROM work_slip w JOIN work_slip_participant"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(2);
        defect(
                () -> service.separate(context, LABOUR, separation("DEATH", TODAY, "x")),
                "WORK_SLIP_AFTER");
        when(jdbc.queryForObject(
                        contains("FROM work_slip w JOIN work_slip_participant"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(null);
        service.separate(context, LABOUR, separation("RESIGNATION", TODAY, "Left"));
        verify(approvals).submit(eq(context), any(Submission.class), eq(null));
        when(jdbc.queryForList(contains("AND p.labour_status = 'ACTIVE'"), any(Object[].class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.separate(context, LABOUR, separation("DEATH", TODAY, "x")))
                .isInstanceOf(FciException.class);
    }

    private static LabourLifecycleService.LightDuty duty(
            LocalDate from,
            String option,
            BigDecimal basic,
            String ref,
            LocalDate orderDate,
            String authority) {
        return new LabourLifecycleService.LightDuty(
                5L, from, option, basic, ref, orderDate, authority, "Medical");
    }

    @Test
    void SRS_2_49_lightDutyNeedsADateTheOptionTheProtectedBasicAndTheOrder() {
        when(org.cycleUnitOf(7L)).thenReturn(Optional.empty());
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                duty(TODAY, "ANCILLARY_PAY", null, "MB", TODAY, "Board")),
                "DEPOT_OFFICE");
        wire();
        defect(() -> service.lightDuty(context, LABOUR, null), "DATE");
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                duty(null, "ANCILLARY_PAY", null, "MB", TODAY, "B")),
                "DATE");
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                duty(TODAY.minusDays(1), "ANCILLARY_PAY", null, "MB", TODAY, "B")),
                "DATE");
        defect(
                () -> service.lightDuty(context, LABOUR, duty(TODAY, null, null, "MB", TODAY, "B")),
                "PAY_OPTION");
        defect(
                () ->
                        service.lightDuty(
                                context, LABOUR, duty(TODAY, "HALF_PAY", null, "MB", TODAY, "B")),
                "PAY_OPTION");
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                duty(TODAY, "PROTECTED_PAY", null, "MB", TODAY, "B")),
                "PROTECTED_BASIC");
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                duty(TODAY, "PROTECTED_PAY", BigDecimal.ZERO, "MB", TODAY, "B")),
                "PROTECTED_BASIC");
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                duty(TODAY, "ANCILLARY_PAY", null, " ", TODAY, "B")),
                "ORDER");
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                duty(TODAY, "ANCILLARY_PAY", null, "MB", null, "B")),
                "ORDER");
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                duty(TODAY, "ANCILLARY_PAY", null, "MB", TODAY, null)),
                "ORDER");
    }

    private void handling(boolean member) {
        when(jdbc.queryForList(contains("m.gang_role = 'HANDLING'"), any(Object[].class)))
                .thenReturn(member ? List.of(Map.of("GANG_ID", 5L)) : List.of());
    }

    private void count(String part, Integer n) {
        when(jdbc.queryForObject(contains(part), eq(Integer.class), any(Object[].class)))
                .thenReturn(n);
    }

    @Test
    void SRS_2_49_lightDutyIsForAHandlingMemberToAnAncillaryDesignationWithAPoolAndNoOpenOrder() {
        handling(false);
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                duty(TODAY, "ANCILLARY_PAY", null, "MB", TODAY, "B")),
                "NOT_HANDLING");
        handling(true);
        count("gang_role_code = 'ANCILLARY'", null);
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                duty(TODAY, "ANCILLARY_PAY", null, "MB", TODAY, "B")),
                "DESIGNATION");
        count("gang_role_code = 'ANCILLARY'", 0);
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                duty(TODAY, "ANCILLARY_PAY", null, "MB", TODAY, "B")),
                "DESIGNATION");
        count("gang_role_code = 'ANCILLARY'", 1);
        when(jdbc.queryForList(
                        contains("FROM ancillary_pool"), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of());
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                duty(TODAY, "ANCILLARY_PAY", null, "MB", TODAY, "B")),
                "NO_POOL");
        when(jdbc.queryForList(
                        contains("FROM ancillary_pool"), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(8L));
        count("light_duty_status = 'PENDING_APPROVAL'", 1);
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                duty(TODAY, "ANCILLARY_PAY", null, "MB", TODAY, "B")),
                "PENDING");
        count("light_duty_status = 'PENDING_APPROVAL'", null);
        GeneratedKeysStub.answer(jdbc, "INSERT INTO labour_light_duty", null);
        assertThatThrownBy(
                        () ->
                                service.lightDuty(
                                        context,
                                        LABOUR,
                                        duty(
                                                TODAY,
                                                "PROTECTED_PAY",
                                                new BigDecimal("21500"),
                                                "MB",
                                                TODAY,
                                                "B")))
                .isInstanceOf(IllegalStateException.class);
        GeneratedKeysStub.answer(jdbc, "INSERT INTO labour_light_duty", 61L);
        service.lightDuty(context, LABOUR, duty(TODAY, "ANCILLARY_PAY", null, "MB", TODAY, "B"));
        verify(jdbc).update(contains("UPDATE labour_light_duty SET approval_id"), any(), eq(61L));
    }

    private ApprovalDecided decided(
            String entity, ApprovalStatus outcome, String key, String payload, String reason) {
        return new ApprovalDecided(
                71L, "M-02", entity, key, outcome, reason, payload, "checker", Instant.now(), null);
    }

    @Test
    void SRS_10_19_anApprovedSeparationEndsTheRecordARejectedOneOnlyRecordsTheDecision()
            throws Exception {
        String retire = json.writeValueAsString(separation("RETIREMENT", TODAY, "Age"));
        String resign = json.writeValueAsString(separation("RESIGNATION", TODAY, "Left"));
        service.on(
                decided(
                        LabourLifecycleService.SEPARATION,
                        ApprovalStatus.APPROVED,
                        "11",
                        retire,
                        null));
        verify(jdbc)
                .update(
                        contains("UPDATE labour_profile SET labour_status"),
                        eq("RETIRED"),
                        any(),
                        eq(11L));
        service.on(
                decided(
                        LabourLifecycleService.SEPARATION,
                        ApprovalStatus.APPROVED,
                        "12",
                        resign,
                        "Ok"));
        verify(jdbc)
                .update(
                        contains("UPDATE labour_profile SET labour_status"),
                        eq("SEPARATED"),
                        any(),
                        eq(12L));
        service.on(
                decided(
                        LabourLifecycleService.SEPARATION,
                        ApprovalStatus.REJECTED,
                        "13",
                        resign,
                        null));
        verify(persons, never()).separate(eq(13L), anyString(), any(), anyString(), anyString());
        assertThatThrownBy(
                        () ->
                                service.on(
                                        decided(
                                                LabourLifecycleService.SEPARATION,
                                                ApprovalStatus.APPROVED,
                                                "14",
                                                "{",
                                                null)))
                .isInstanceOf(IllegalStateException.class);
        service.on(decided("labour_transfer", ApprovalStatus.APPROVED, "15", resign, null));
        verify(persons, never()).separate(eq(15L), anyString(), any(), anyString(), anyString());
    }

    @Test
    void SRS_2_49_anApprovedOrderMovesTheMemberToThePoolARejectedOneDoesNot() {
        Map<String, Object> order = new HashMap<>();
        order.put("EMPLOYEE_ID", LABOUR);
        order.put("TO_DESIGNATION_ID", 5L);
        order.put("FROM_GANG_ID", 2L);
        order.put("EFFECTIVE_DATE", java.sql.Date.valueOf(TODAY));
        order.put("POOL_GANG_ID", 9L);
        when(jdbc.queryForMap(contains("FROM labour_light_duty l JOIN"), any(Object[].class)))
                .thenReturn(order);
        service.on(
                decided(
                        LabourLifecycleService.LIGHT_DUTY,
                        ApprovalStatus.REJECTED,
                        "61",
                        null,
                        null));
        verify(persons, never()).move(anyLong(), any(), any(), any(), any());
        service.on(
                decided(
                        LabourLifecycleService.LIGHT_DUTY,
                        ApprovalStatus.APPROVED,
                        "61",
                        null,
                        "Fit"));
        verify(persons).move(LABOUR, null, null, null, 5L);
    }

    @Test
    void aSeparationThatCannotBeWrittenIsAnError() {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any()))
                .thenThrow(new tools.jackson.core.exc.StreamWriteException(null, "no"));
        LabourLifecycleService failing =
                new LabourLifecycleService(
                        jdbc,
                        mock(Authorization.class),
                        org,
                        approvals,
                        persons,
                        parameters,
                        audit,
                        broken,
                        Clock.systemUTC());
        assertThatThrownBy(() -> failing.separate(context, LABOUR, separation("DEATH", TODAY, "x")))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * SRS-10-19 / SRS-2-30 (OD-100, CLR-154; found by nwnp-lifecycle at the 2026-11-04 checkpoint):
     * a gang membership that starts on or after the separation date would end before it began (DDL
     * chk_gang_membership_span), so the separation is refused MEMBERSHIP_FROM - when it is proposed
     * and again when the Checker approves it (a membership approved in between) - and nothing is
     * ended; a membership that started the day before is ended as usual.
     */
    @Test
    void SRS_10_19_aSeparationOnOrBeforeTheStartOfAGangMembershipIsRefused() throws Exception {
        when(jdbc.queryForObject(
                        contains(
                                "FROM labour_gang_membership WHERE employee_id = ? AND"
                                        + " effective_from >= ?"),
                        eq(Integer.class),
                        eq(LABOUR),
                        eq(java.sql.Date.valueOf(TODAY))))
                .thenReturn(1);
        defect(
                () -> service.separate(context, LABOUR, separation("RESIGNATION", TODAY, "Left")),
                "MEMBERSHIP_FROM");
        verify(approvals, never()).submit(any(), any(), any());
        String resign = json.writeValueAsString(separation("RESIGNATION", TODAY, "Left"));
        assertThatThrownBy(
                        () ->
                                service.on(
                                        decided(
                                                LabourLifecycleService.SEPARATION,
                                                ApprovalStatus.APPROVED,
                                                String.valueOf(LABOUR),
                                                resign,
                                                null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getMessageArgs()).containsExactly("MEMBERSHIP_FROM"));
        verify(jdbc, never())
                .update(contains("UPDATE labour_gang_membership"), any(Object[].class));
        verify(persons, never()).separate(eq(LABOUR), anyString(), any(), anyString(), anyString());

        // The day after the membership began: no membership starts on or after it.
        String later =
                json.writeValueAsString(separation("RESIGNATION", TODAY.plusDays(1), "Left"));
        service.on(
                decided(
                        LabourLifecycleService.SEPARATION,
                        ApprovalStatus.APPROVED,
                        String.valueOf(LABOUR),
                        later,
                        null));
        verify(jdbc)
                .update(
                        contains("UPDATE labour_gang_membership SET effective_to = ?"),
                        eq(java.sql.Date.valueOf(TODAY)),
                        eq(LABOUR),
                        eq(java.sql.Date.valueOf(TODAY)));
    }
}
