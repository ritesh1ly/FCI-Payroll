package in.gov.fci.payroll.nps.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * I-14's register (plan 18 phase 18c; SRS-9-6, D18-7): a user without an office or whose office has
 * no cycle unit is refused; a month or range that is not one is refused; a missing count is zero; a
 * row's flag, name and dates are written as on screen; a failing file stops the export.
 */
class NpsRegisterServiceTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final Authorization policy = mock(Authorization.class);
    private final NpsRegisterService service = new NpsRegisterService(named, org, policy);

    private static AccessContext at(Long location) {
        return new AccessContext(
                3L, "maker@x", "Maker", "s", "P", location, null, List.of(), Map.of(), Set.of(),
                Set.of(), Set.of());
    }

    private static void refused(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static final class Sink implements ExportSink {
        final List<List<Object>> rows = new ArrayList<>();
        boolean fail;

        @Override
        public void header(String... titles) {}

        @Override
        public void row(Object... cells) throws IOException {
            if (fail) {
                throw new IOException("disk full");
            }
            rows.add(Arrays.asList(cells));
        }

        @Override
        public long rows() {
            return rows.size();
        }

        @Override
        public void close() {}
    }

    private static ResultSet row(String last, String flag) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("nps_deduction_id")).thenReturn(1L);
        when(rs.getString("emp_num")).thenReturn("100041");
        when(rs.getString("first_name")).thenReturn("Asha");
        when(rs.getString("last_name")).thenReturn(last);
        when(rs.getString("hrms_nps_flag")).thenReturn(flag);
        when(rs.getBigDecimal("employee_amount")).thenReturn(new BigDecimal("1500"));
        when(rs.getString("shown_status")).thenReturn("RECEIVED");
        when(rs.getString("payroll_yymm")).thenReturn("202610");
        return rs;
    }

    @Test
    void D18_7_aUserWithoutAnOfficeOrACycleUnitIsRefused() {
        refused(() -> service.counts(at(null), "202610"), CommonErrorCode.ACCESS_DENIED);
        when(org.cycleUnitOf(500L)).thenReturn(Optional.empty());
        refused(() -> service.counts(at(500L), "202610"), ApiErrorCode.CYCLE_NO_UNIT);
    }

    @Test
    void SRS_9_6_aMonthOrARangeThatIsNotOneIsRefused() {
        when(policy.isAtHeadquarters(any())).thenReturn(true);
        refused(
                () ->
                        service.page(
                                at(1L),
                                new NpsRegisterService.Filter("202613", null, null),
                                new PageSpec(0, 20)),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.reportSize(at(1L), "2026", "202610"),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.reportSize(at(1L), "202610", "20261"),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.reportSize(at(1L), "202611", "202610"),
                CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_9_1_aMissingCountIsZeroAndARowIsShownAsOnScreen() throws Exception {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        when(org.cycleUnitOf(30L)).thenReturn(Optional.of(unit));
        when(org.subtree(300L)).thenReturn(Set.of(30L));
        when(named.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(null);
        ResultSet rs = row(" ", "N");
        doAnswer(i -> List.of(((RowMapper<Object>) i.getArgument(2)).mapRow(rs, 0)))
                .when(named)
                .query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        NpsRegisterService.Filter filter = new NpsRegisterService.Filter("202610", " ", " 100041 ");
        var page = service.page(at(30L), filter, new PageSpec(0, 20));
        assertThat(page.total()).isZero();
        NpsRegisterService.Row r = page.items().get(0);
        assertThat(r.employee()).isEqualTo("Asha");
        assertThat(r.npsFlag()).isFalse();
        assertThat(r.effectiveFrom()).isNull();
        assertThat(service.exportSize(at(30L), filter)).isZero();
        assertThat(service.reportSize(at(30L), "202604", "202610")).isZero();
    }

    @Test
    void SRS_G_13_theExportAndTheReportWriteTheirRowsAndAFailingFileStops() throws Exception {
        when(policy.isAtHeadquarters(any())).thenReturn(true);
        when(named.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
        ResultSet rs = row("Rao", "Y");
        doAnswer(
                        i -> {
                            ((RowCallbackHandler) i.getArgument(2)).processRow(rs);
                            return null;
                        })
                .when(named)
                .query(
                        startsWith("SELECT"),
                        any(SqlParameterSource.class),
                        any(RowCallbackHandler.class));
        Sink register = new Sink();
        service.export(at(1L), new NpsRegisterService.Filter("202610", null, null), register);
        assertThat(register.rows.get(0)).containsSubsequence("100041", "Asha Rao").contains("Y");
        Sink report = new Sink();
        service.report(at(1L), "202610", "202610", report);
        assertThat(report.rows.get(0)).contains("202610", "Asha Rao");
        Sink failing = new Sink();
        failing.fail = true;
        assertThatThrownBy(
                        () ->
                                service.export(
                                        at(1L),
                                        new NpsRegisterService.Filter("202610", null, null),
                                        failing))
                .isInstanceOf(IOException.class);
    }
}
