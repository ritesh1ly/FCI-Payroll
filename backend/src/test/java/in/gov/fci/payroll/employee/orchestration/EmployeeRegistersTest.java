package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.HrmsRecords;
import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.persistence.EmployeeRepository;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * The Employee screens' reads (OD-121; PENDING API-16-8): every read checks the screen's right
 * before any query, is bounded by the data scope in the query, and an empty scope reads nothing.
 */
class EmployeeRegistersTest {

    private static AccessContext scoped(Set<Long> scope) {
        return new AccessContext(
                1L,
                "viewer@x",
                "Viewer",
                "s",
                "P",
                10L,
                null,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                scope);
    }

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final EmployeeService employees = mock(EmployeeService.class);
    private final EmployeeRepository repository = mock(EmployeeRepository.class);
    private final HrmsChangeService changes = mock(HrmsChangeService.class);
    private final DocumentStore documents = mock(DocumentStore.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<HrmsRecords> hrms = mock(ObjectProvider.class);

    private final Clock clock =
            Clock.fixed(Instant.parse("2026-11-07T05:00:00Z"), ZoneId.of("UTC"));
    private final EmployeeRegisters registers =
            new EmployeeRegisters(
                    named, policy, org, employees, repository, changes, documents, hrms, clock);

    private static FciException denied() {
        return new FciException(
                ApiErrorCode.PERMISSION_DENIED, new Object[] {"X", "VIEW"}, List.of(), null);
    }

    private Employee employee() {
        Employee e = mock(Employee.class);
        when(e.getId()).thenReturn(5L);
        when(e.getEmpNum()).thenReturn("104882");
        when(e.getLocationId()).thenReturn(10L);
        when(e.getJoiningDate()).thenReturn(LocalDate.parse("2004-01-02"));
        when(repository.findByEmpNum("104882")).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    void SRS_G_54_aRoleWithoutTheScreenReadsNothing() {
        AccessContext ctx = scoped(Set.of(10L));
        doThrow(denied()).when(policy).require(any(), anyString(), anyString());
        PageSpec spec = new PageSpec(0, 20);
        assertThatThrownBy(() -> registers.offices(ctx)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> registers.events(ctx, false, spec))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> registers.transfers(ctx, spec)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> registers.transferSummary(ctx)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> registers.exportEvents(ctx, false, null))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> registers.exportTransfers(ctx, null))
                .isInstanceOf(FciException.class);
        verifyNoInteractions(named);
    }

    @Test
    void SRS_22_8_anEmptyScopeBindsNoOffice() {
        when(named.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);
        registers.events(scoped(Set.of()), false, new PageSpec(0, 20));
        ArgumentCaptor<SqlParameterSource> params =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named).queryForObject(anyString(), params.capture(), eq(Long.class));
        assertThat(params.getValue().getValue("scope")).isEqualTo(Set.of(-1L));
    }

    @Test
    void SRS_1_63_theReportNarrowsToSuspensionAndRevocation() {
        when(named.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);
        registers.events(scoped(Set.of(10L)), true, new PageSpec(0, 20));
        ArgumentCaptor<SqlParameterSource> params =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named).queryForObject(anyString(), params.capture(), eq(Long.class));
        assertThat(params.getValue().getValue("report")).isEqualTo("Y");
        assertThat(params.getValue().getValue("scope")).isEqualTo(Set.of(10L));
    }

    @Test
    void SRS_G_54_theOfficeFilterOffersOfficesNotDepots() {
        OrgLocation division = mock(OrgLocation.class);
        when(division.getType()).thenReturn(LocationType.DO);
        when(division.getCode()).thenReturn("DH001");
        when(division.displayLabel()).thenReturn("DO — Delhi HQ (DH001)");
        OrgLocation depot = mock(OrgLocation.class);
        when(depot.getType()).thenReturn(LocationType.DEPOT);
        when(depot.getCode()).thenReturn("DP-DL01");
        when(org.locations(Set.of(10L, 11L))).thenReturn(List.of(depot, division));
        assertThat(registers.offices(scoped(Set.of(10L, 11L))))
                .containsExactly(new EmployeeRegisters.Office("DH001", "DO — Delhi HQ (DH001)"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_16_7_theOriginalAllotmentComesFirstAndNoHistoryShowsTheCurrentNumber() {
        employee();
        when(org.location(10L)).thenReturn(Optional.empty());
        when(named.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        List<EmployeeRegisters.StaffNumberRow> none =
                registers.staffNumbers(scoped(Set.of(10L)), "104882");
        assertThat(none).hasSize(1);
        assertThat(none.get(0).oldNumber()).isNull();
        assertThat(none.get(0).newNumber()).isEqualTo("104882");
        assertThat(none.get(0).event()).isEqualTo("ORIGINAL");
        assertThat(none.get(0).effectiveDate()).isEqualTo(LocalDate.parse("2004-01-02"));

        EmployeeRegisters.StaffNumberRow moved =
                new EmployeeRegisters.StaffNumberRow(
                        "099882", "104882", "DO Mumbai", "DO Delhi", LocalDate.now(), "TRANSFER");
        when(named.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(moved));
        List<EmployeeRegisters.StaffNumberRow> rows =
                registers.staffNumbers(scoped(Set.of(10L)), "104882");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).newNumber()).isEqualTo("099882");
        assertThat(rows.get(0).destination()).isEqualTo("DO Mumbai");
        assertThat(rows.get(1)).isEqualTo(moved);
    }

    @Test
    void SRS_G_29_aRecordOutsideTheScopeIsRefusedBeforeItsHistory() {
        employee();
        AccessContext ctx = scoped(Set.of(99L));
        when(employees.get(ctx, "104882")).thenThrow(denied());
        assertThatThrownBy(() -> registers.memberIds(ctx, "104882"))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> registers.staffNumbers(ctx, "104882"))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> registers.recordExtras(ctx, "104882"))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> registers.orders(ctx, "104882")).isInstanceOf(FciException.class);
        verifyNoInteractions(named, documents);
    }

    @Test
    void SRS_1_70_theLiveReadJoinsTheRecordAndAMissingReaderIsUnavailable() {
        employee();
        Map<String, Object> row = new HashMap<>();
        row.put("GRADE_NAME", "Executive");
        row.put("BAND_CODE", "E-4");
        row.put("BANK_CODE", "SBI");
        row.put("old_basic", new BigDecimal("60600"));
        when(named.queryForMap(anyString(), any(SqlParameterSource.class))).thenReturn(row);
        when(hrms.getIfAvailable()).thenReturn(null);
        EmployeeRegisters.RecordExtras none = registers.recordExtras(scoped(Set.of(10L)), "104882");
        assertThat(none.hrmsAvailable()).isFalse();
        assertThat(none.cadre()).isEqualTo("Executive");
        assertThat(none.oldBasic()).isEqualByComparingTo("60600");

        HrmsRecords reader = mock(HrmsRecords.class);
        when(reader.snapshot("104882"))
                .thenReturn(new HrmsRecords.Snapshot(true, new BigDecimal("65000"), "ACTIVE"));
        when(hrms.getIfAvailable()).thenReturn(reader);
        EmployeeRegisters.RecordExtras live = registers.recordExtras(scoped(Set.of(10L)), "104882");
        assertThat(live.hrmsAvailable()).isTrue();
        assertThat(live.hrmsBasic()).isEqualByComparingTo("65000");
        assertThat(live.hrmsStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void SRS_G_30_anOrderIsAttachedOnlyWithTheFlagRightInScope() {
        employee();
        AccessContext ctx = scoped(Set.of(10L));
        HrmsChangeService.OrderCopy copy =
                new HrmsChangeService.OrderCopy(
                        "SANCTION_ORDER", "R/1", LocalDate.now(), "RO", "o.pdf", new byte[] {1});
        doThrow(denied()).when(policy).requireEmployeeRecord(ctx, "M-01", "FLAG", 5L, 10L);
        assertThatThrownBy(() -> registers.attachOrder(ctx, "104882", copy))
                .isInstanceOf(FciException.class);
        verify(documents, never()).attach(any(), any());
    }

    @Test
    void SRS_G_30_anUnknownRecordTakesNoOrder() {
        when(repository.findByEmpNum("X")).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () ->
                                registers.attachOrder(
                                        scoped(Set.of(10L)),
                                        " X ",
                                        new HrmsChangeService.OrderCopy(
                                                "OTHER", null, LocalDate.now(), null, "o", null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
    }

    @Test
    void SRS_1_68_theAgeingTileIsZeroWhileTheSettingIsNotSet() {
        AccessContext ctx = scoped(Set.of(10L));
        when(changes.scopeUnit(ctx, "VIEW")).thenReturn(null);
        when(changes.params((Long) null, HrmsChangeService.Filter.none()))
                .thenReturn(new MapSqlParameterSource());
        when(changes.ageing()).thenReturn(OptionalLong.empty());
        when(named.queryForMap(anyString(), any(SqlParameterSource.class)))
                .thenReturn(
                        Map.of(
                                "PENDING",
                                46L,
                                "BEFORE_F",
                                5L,
                                "CHECKER",
                                12L,
                                "AGED",
                                9L,
                                "AUTO",
                                64L));
        EmployeeRegisters.TaskSummary s = registers.taskSummary(ctx);
        assertThat(s).isEqualTo(new EmployeeRegisters.TaskSummary(46, 5, 41, 12, 0, 5, 64));
    }

    @Test
    void SRS_1_68_theAgeingTileCountsPastTheSetting() {
        AccessContext ctx = scoped(Set.of(10L));
        when(changes.scopeUnit(ctx, "VIEW")).thenReturn(null);
        when(changes.params((Long) null, HrmsChangeService.Filter.none()))
                .thenReturn(new MapSqlParameterSource());
        when(changes.ageing()).thenReturn(OptionalLong.of(7));
        when(named.queryForMap(anyString(), any(SqlParameterSource.class)))
                .thenReturn(
                        Map.of(
                                "PENDING",
                                4L,
                                "BEFORE_F",
                                1L,
                                "CHECKER",
                                2L,
                                "AGED",
                                3L,
                                "AUTO",
                                0L));
        assertThat(registers.taskSummary(ctx).ageing()).isEqualTo(3);
    }

    @Test
    void SRS_1_71_anEmptyRegisterPagesNothing() {
        AccessContext ctx = scoped(Set.of(10L));
        when(changes.scopeUnit(ctx, "VIEW")).thenReturn(10L);
        when(org.subtree(10L)).thenReturn(Set.of(10L, 11L));
        when(named.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);
        Paged<EmployeeRegisters.RegisterRow> page =
                registers.register(ctx, " Sha%rma ", new PageSpec(0, 20));
        assertThat(page.total()).isZero();
        ArgumentCaptor<SqlParameterSource> params =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named, times(1)).queryForObject(anyString(), params.capture(), eq(Long.class));
        assertThat((String) params.getValue().getValue("q")).startsWith("%sha").endsWith("rma%");
        assertThat(params.getValue().getValue("all")).isEqualTo("N");
        assertThat(params.getValue().getValue("units")).isEqualTo(Set.of(10L, 11L));
    }

    @Test
    void SRS_1_21_aDobTaskCarriesTheRetirementItWouldGive() {
        AccessContext ctx = scoped(Set.of(10L));
        HrmsChangeService.TaskView view = mock(HrmsChangeService.TaskView.class);
        when(view.id()).thenReturn(9L);
        when(view.taskNo()).thenReturn("HCA-1");
        when(changes.page(eq(ctx), any(), any())).thenReturn(new Paged<>(List.of(view), 0, 20, 1));
        LocalDate retirement = LocalDate.parse("2019-01-31");
        when(changes.detail(ctx, 9L))
                .thenReturn(
                        new HrmsChangeService.Detail(
                                null,
                                new HrmsChangeService.DobPreview(
                                        null, null, null, retirement, null, true, false, true),
                                true,
                                null,
                                null));
        EmployeeRegisters.DobTask row = registers.dobTasks(ctx, new PageSpec(0, 20)).items().get(0);
        assertThat(row.newRetirement()).isEqualTo(retirement);
        assertThat(row.retirementPast()).isTrue();
        assertThat(row.retiresSoon()).isFalse();

        when(changes.detail(ctx, 9L))
                .thenReturn(new HrmsChangeService.Detail(null, null, true, null, null));
        EmployeeRegisters.DobTask without =
                registers.dobTasks(ctx, new PageSpec(0, 20)).items().get(0);
        assertThat(without.newRetirement()).isNull();
        assertThat(without.retirementPast()).isFalse();
    }
}
