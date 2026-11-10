package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.labour.business.AttendanceRules;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews.Filter;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The lists of I-01 and I-02 as drawn (OD-121; SRS-2-2, SRS-2-34, SRS-2-36, SRS-2-57): the right is
 * checked before any read, an unknown payroll type is refused, and a depot session reads its own
 * depots; the SQL itself runs in the integration suite.
 */
class LabourCaptureViewsTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 10);
    private static final Filter DEPT = new Filter("DEPT_LABOUR", null, null);

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AccessContext context = mock(AccessContext.class);
    private final AttendanceViews attendance = new AttendanceViews(named, policy, parameters);
    private final WorkSlipViews slips = new WorkSlipViews(named, policy, attendance);

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(LocalDate.of(2026, 11, 7));
        when(context.locationId()).thenReturn(3L);
        when(context.dataScope()).thenReturn(Set.of(3L));
        when(context.groups()).thenReturn(List.of());
        when(named.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);
    }

    private FciException refused() {
        return new FciException(CommonErrorCode.ACCESS_DENIED);
    }

    @Test
    void SRS_2_2_noI01RightReadsNothing() {
        doThrow(refused()).when(policy).require(context, "I-01", "VIEW");
        PageSpec page = new PageSpec(0, 20);
        assertThatThrownBy(() -> attendance.overview(context, DEPT, MONTH))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> attendance.gangSummary(context, DEPT, MONTH, page))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> attendance.daily(context, DEPT, null, page))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> attendance.leave(context, DEPT, page))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> attendance.sources(context, page))
                .isInstanceOf(FciException.class);
        verify(named, never())
                .queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class));
    }

    @Test
    void SRS_2_34_noI02RightReadsNothing() {
        doThrow(refused()).when(policy).require(context, "I-02", "VIEW");
        PageSpec page = new PageSpec(0, 20);
        assertThatThrownBy(() -> slips.overview(context, DEPT, MONTH))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> slips.register(context, DEPT, MONTH, null, page))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> slips.overtime(context, DEPT, MONTH, page))
                .isInstanceOf(FciException.class);
    }

    @Test
    void SRS_G_13_exportNeedsItsOwnRight() {
        doThrow(refused()).when(policy).require(context, "I-01", "EXPORT");
        doThrow(refused()).when(policy).require(context, "I-02", "EXPORT");
        assertThatThrownBy(() -> attendance.exportSize(context, DEPT, MONTH))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> slips.exportSize(context, DEPT, MONTH, null))
                .isInstanceOf(FciException.class);
    }

    @Test
    void SRS_2_2_anUnknownPayrollTypeIsRefused() {
        Filter unknown = new Filter("STAFF", null, null);
        assertThatThrownBy(
                        () -> attendance.gangSummary(context, unknown, MONTH, new PageSpec(0, 5)))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> attendance.daily(context, unknown, null, new PageSpec(0, 5)))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> attendance.leave(context, unknown, new PageSpec(0, 5)))
                .isInstanceOf(FciException.class);
    }

    @Test
    void SRS_2_2_aDepotSessionIsBoundToItsDepotsAndTheCriteria() {
        attendance.gangSummary(
                context, new Filter("DPS_LABOUR", 7L, "  gng "), MONTH, new PageSpec(1, 10));
        ArgumentCaptor<MapSqlParameterSource> p =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named).queryForObject(contains("labour_gang g"), p.capture(), eq(Long.class));
        assertThat(p.getValue().getValue("all")).isEqualTo("N");
        assertThat(p.getValue().getValue("type")).isEqualTo("DPS_LABOUR");
        assertThat(p.getValue().getValue("gang")).isEqualTo(7L);
        assertThat(p.getValue().getValue("text")).isEqualTo("%GNG%");
        assertThat(p.getValue().getValue("offset")).isEqualTo(10L);
    }

    @Test
    void SRS_2_34_aBlankSearchIsNoCriterionAndTheDayIsBound() {
        slips.register(
                context, new Filter("DPS_LABOUR", null, " "), MONTH, null, new PageSpec(0, 5));
        ArgumentCaptor<MapSqlParameterSource> p =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named).queryForObject(contains("work_slip s"), p.capture(), eq(Long.class));
        assertThat(p.getValue().getValue("text")).isNull();
        assertThat(p.getValue().getValue("day")).isNull();
    }

    @Test
    void SRS_2_35_noMemberMeansNoFurtherReadOfTheDay() {
        when(named.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.<Map<String, Object>>of());
        assertThat(attendance.daily(context, DEPT, null, new PageSpec(0, 5)).items()).isEmpty();
        verify(named, never())
                .query(
                        contains("biometric_punch"),
                        any(MapSqlParameterSource.class),
                        any(RowCallbackHandler.class));
    }

    @Test
    void SRS_2_36_theSourceMasterIsReadInEveryScope() {
        when(named.query(
                        anyString(),
                        any(MapSqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<AttendanceViews.SourceRow>>any()))
                .thenReturn(List.of());
        assertThat(attendance.sources(context, new PageSpec(0, 5)).items()).isEmpty();
    }

    @Test
    void SRS_2_35_markableDaysStopAtEndRetirementAndTheCutOff() {
        LocalDate first = MONTH.atDay(1);
        LocalDate last = MONTH.atEndOfMonth();
        assertThat(AttendanceRules.markableDays(MONTH, first, null, null, last)).isEqualTo(31);
        assertThat(AttendanceRules.markableDays(MONTH, first.minusMonths(1), null, null, last))
                .isEqualTo(31);
        assertThat(AttendanceRules.markableDays(MONTH, MONTH.atDay(11), null, null, last))
                .isEqualTo(21);
        assertThat(AttendanceRules.markableDays(MONTH, first, MONTH.atDay(5), null, last))
                .isEqualTo(5);
        assertThat(AttendanceRules.markableDays(MONTH, first, null, MONTH.atDay(6), last))
                .isEqualTo(6);
        assertThat(AttendanceRules.markableDays(MONTH, first, null, null, MONTH.atDay(7)))
                .isEqualTo(7);
        assertThat(AttendanceRules.markableDays(MONTH, first, null, null, last.plusDays(5)))
                .isEqualTo(31);
        assertThat(AttendanceRules.markableDays(MONTH, MONTH.atDay(9), MONTH.atDay(3), null, last))
                .isZero();
    }
}
