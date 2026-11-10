package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The I-21 task service's helpers (plan 16 phase 16b; SRS-1-22, SRS-1-68, SRS-1-30): the scope a
 * user sees (no office, HQ, a unit, an office without a cycle unit); the DOB preview with and
 * without the ages; a task's view (days pending only while pending and received); every date type
 * read.
 */
class HrmsChangeServiceHelpersTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-26T06:00:00Z"), ZoneId.of("UTC"));

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final Authorization policy = mock(Authorization.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final HrmsChangeApplier applier = mock(HrmsChangeApplier.class);
    private final HrmsChangeService service =
            new HrmsChangeService(
                    jdbc,
                    mock(NamedParameterJdbcTemplate.class),
                    org,
                    policy,
                    mock(MakerChecker.class),
                    mock(AuditService.class),
                    parameters,
                    mock(PayrollPeriods.class),
                    mock(PayrollRecords.class),
                    applier,
                    CLOCK,
                    mock(in.gov.fci.payroll.document.api.DocumentStore.class),
                    mock(in.gov.fci.payroll.platform.approval.ApprovalWithdrawals.class));

    private static AccessContext at(Long location) {
        return new AccessContext(
                3L, "maker@x", "Maker", "s", "P", location, null, List.of(), Map.of(), Set.of(),
                Set.of(), Set.of());
    }

    @Test
    void SRS_1_30_theScopeIsTheUsersCycleUnitOrEveryUnitAtHq() {
        assertThatThrownBy(() -> service.scopeUnit(at(null), "VIEW"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED));
        when(policy.isAtHeadquarters(any())).thenReturn(true);
        assertThat(service.scopeUnit(at(1L), "VIEW")).isNull();
        verify(policy).requireAt(any(), eq(HrmsChangeService.SCREEN), eq("VIEW"), eq(1L));
        when(policy.isAtHeadquarters(any())).thenReturn(false);
        when(org.cycleUnitOf(500L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.scopeUnit(at(500L), "VIEW"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.CYCLE_NO_UNIT));
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        when(org.cycleUnitOf(501L)).thenReturn(Optional.of(unit));
        assertThat(service.scopeUnit(at(501L), "VIEW")).isEqualTo(300L);
    }

    private void dobRow(String next) {
        Map<String, Object> row = new HashMap<>();
        row.put("NEW_VALUE", next);
        row.put("BIRTH_DATE", java.sql.Date.valueOf("1970-05-20"));
        row.put("RETIREMENT_DATE", null);
        when(jdbc.queryForMap(anyString(), anyLong())).thenReturn(row);
    }

    @Test
    void SRS_1_22_theDobPreviewShowsTheDatesOnlyWhenTheAgesAreSet() {
        dobRow("1966-05-20");
        when(parameters.configured(any())).thenReturn(OptionalLong.empty());
        HrmsChangeService.DobPreview none = service.dobPreview(1L);
        assertThat(none.newRetirement()).isNull();
        assertThat(none.epsCutoff()).isNull();
        assertThat(none.retirementPast()).isFalse();
        assertThat(none.retiresSoon()).isFalse();
        assertThat(none.retirementAgeConfigured()).isFalse();

        when(parameters.configured(SystemParameters.Code.RETIREMENT_AGE))
                .thenReturn(OptionalLong.of(60));
        when(parameters.configured(SystemParameters.Code.EPS_CUTOFF_AGE))
                .thenReturn(OptionalLong.of(58));
        HrmsChangeService.DobPreview past = service.dobPreview(1L);
        assertThat(past.newRetirement()).isEqualTo(LocalDate.of(2026, 5, 31));
        assertThat(past.epsCutoff()).isEqualTo(LocalDate.of(2024, 5, 20));
        assertThat(past.retirementPast()).isTrue();
        assertThat(past.currentBirthDate()).isEqualTo(LocalDate.of(1970, 5, 20));

        dobRow("1966-11-15");
        HrmsChangeService.DobPreview soon = service.dobPreview(1L);
        assertThat(soon.retirementPast()).isFalse();
        assertThat(soon.retiresSoon()).isTrue();
    }

    private HrmsChangeService serviceOn(String day) {
        return new HrmsChangeService(
                jdbc,
                mock(NamedParameterJdbcTemplate.class),
                org,
                policy,
                mock(MakerChecker.class),
                mock(AuditService.class),
                parameters,
                mock(PayrollPeriods.class),
                mock(PayrollRecords.class),
                applier,
                Clock.fixed(Instant.parse(day + "T06:00:00Z"), ZoneId.of("UTC")),
                mock(in.gov.fci.payroll.document.api.DocumentStore.class),
                mock(in.gov.fci.payroll.platform.approval.ApprovalWithdrawals.class));
    }

    /**
     * SRS-1-23 (BMAD TR-038): "already past" is a retirement date before today. A retirement on
     * today is not yet past (no HQ Establishment level); one on yesterday is.
     */
    @Test
    void SRS_1_23_aRetirementOnTodayIsNotYetPastAndOneOnYesterdayIs() {
        when(parameters.configured(SystemParameters.Code.RETIREMENT_AGE))
                .thenReturn(OptionalLong.of(60));
        when(parameters.configured(SystemParameters.Code.EPS_CUTOFF_AGE))
                .thenReturn(OptionalLong.of(58));
        dobRow("1966-10-20");
        HrmsChangeService.DobPreview today = serviceOn("2026-10-31").dobPreview(1L);
        assertThat(today.newRetirement()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(today.retirementPast()).as("retires today: not yet past").isFalse();
        HrmsChangeService.DobPreview yesterday = serviceOn("2026-11-01").dobPreview(1L);
        assertThat(yesterday.newRetirement()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(yesterday.retirementPast()).as("retired yesterday: past").isTrue();
    }

    private static ResultSet task(String status, Timestamp received, String last) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("field_code")).thenReturn("BASIC_PAY");
        when(rs.getTimestamp("received_at")).thenReturn(received);
        when(rs.getString("shown_status")).thenReturn(status);
        when(rs.getString("target_payroll_yymm")).thenReturn("202610");
        when(rs.getString("first_name")).thenReturn("Asha");
        when(rs.getString("last_name")).thenReturn(last);
        return rs;
    }

    @Test
    void SRS_1_68_daysPendingCountOnlyWhilePendingAndReceived() throws Exception {
        Timestamp tenDaysAgo = Timestamp.from(Instant.parse("2026-10-16T06:00:00Z"));
        HrmsChangeService.TaskView pending =
                service.view(task("PENDING_CHECKER", tenDaysAgo, "Rao"), OptionalLong.of(7));
        assertThat(pending.daysPending()).isEqualTo(10);
        assertThat(pending.overdue()).isTrue();
        assertThat(pending.employee()).isEqualTo("Asha Rao");
        assertThat(pending.targetPeriod()).isEqualTo("2026-10");
        HrmsChangeService.TaskView applied =
                service.view(task("APPROVED", tenDaysAgo, " "), OptionalLong.of(7));
        assertThat(applied.daysPending()).isZero();
        assertThat(applied.employee()).isEqualTo("Asha");
        HrmsChangeService.TaskView unreceived =
                service.view(task("PENDING_CHECKER", null, null), OptionalLong.empty());
        assertThat(unreceived.daysPending()).isZero();
        assertThat(unreceived.receivedAt()).isNull();
    }

    @Test
    void SRS_1_28_everyDateTypeIsRead() {
        assertThat(HrmsChangeService.date(LocalDate.of(2026, 1, 2)))
                .isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(HrmsChangeService.date(LocalDateTime.of(2026, 1, 2, 3, 4)))
                .isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(HrmsChangeService.date(Timestamp.valueOf("2026-01-02 03:04:00")))
                .isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(HrmsChangeService.date(null)).isNull();
        assertThat(HrmsChangeService.date("2026-01-02")).as("not a date type").isNull();
    }

    /** X-03: with no task awaiting, the oldest received time is empty. */
    @Test
    @SuppressWarnings("unchecked")
    void X_03_nothingAwaitingHasNoOldestTask() {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class)))
                .thenAnswer(
                        a -> {
                            ResultSet rs = mock(ResultSet.class);
                            when(rs.getLong(1)).thenReturn(0L);
                            return ((RowMapper<Object>) a.getArgument(1)).mapRow(rs, 0);
                        });
        HrmsChanges.Awaiting none = service.awaiting();
        assertThat(none.count()).isZero();
        assertThat(none.oldestReceivedAt()).isNull();
    }

    /**
     * SRS-1-28 / plan 09 phase 09c: a field not in the registry today is refused, and a value the
     * owning module cannot even read is refused as an invalid value, not as a server error.
     */
    @Test
    void SRS_1_28_anUnknownFieldOrAnUnreadableValueOpensNoTask() {
        assertThatThrownBy(
                        () ->
                                service.receive(
                                        new HrmsChanges.Change(
                                                1L, "NOPE", "x", null, "HRMS-1", null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.I21_FIELD));
        Map<String, Object> field = new HashMap<>();
        field.put("PAY_EFFECT_FIELD_ID", 4L);
        when(jdbc.queryForList(anyString(), eq("LOAN_FORECLOSURE"), any(), any()))
                .thenReturn(List.of(field));
        HrmsTaskEffects owner = mock(HrmsTaskEffects.class);
        doThrow(new IllegalArgumentException("not a number"))
                .when(owner)
                .check(eq("LOAN_FORECLOSURE"), anyLong(), any());
        when(applier.effectFor("LOAN_FORECLOSURE")).thenReturn(Optional.of(owner));
        assertThatThrownBy(
                        () ->
                                service.receive(
                                        new HrmsChanges.Change(
                                                1L,
                                                "LOAN_FORECLOSURE",
                                                "abc",
                                                null,
                                                "HRMS-2",
                                                null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.I21_VALUE);
                            assertThat(e.getCause()).isInstanceOf(IllegalArgumentException.class);
                        });
    }

    /** SRS-1-22: a DOB change is not submitted while the retirement age is not configured. */
    @Test
    void SRS_1_22_aDobChangeNeedsTheRetirementAge() {
        Map<String, Object> task = new HashMap<>();
        task.put("TASK_STATUS", "PENDING_ORDER_DETAILS");
        task.put("PAYING_UNIT_ID", 300L);
        task.put("PAY_EFFECT_FIELD_ID", 4L);
        when(jdbc.queryForList(contains("WHERE hrms_change_task_id"), eq(5L)))
                .thenReturn(List.of(task));
        Map<String, Object> field = new HashMap<>();
        field.put("FIELD_CODE", "DATE_OF_BIRTH");
        field.put("OTP_REQUIRED", "N");
        field.put("HQ_ESTAB_IF_RETIRE_PAST", "Y");
        dobRow("1966-05-20");
        when(jdbc.queryForMap(contains("pay_effect_field_id"), eq(4L))).thenReturn(field);
        when(parameters.configured(any())).thenReturn(OptionalLong.empty());
        when(applier.applicable(field)).thenReturn(true);
        assertThatThrownBy(
                        () ->
                                service.submit(
                                        at(501L),
                                        5L,
                                        new HrmsChangeService.OrderDetails(
                                                "O-1", LocalDate.of(2026, 10, 1), "HQ", false)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.I21_NOT_CONFIGURED));
    }
}
