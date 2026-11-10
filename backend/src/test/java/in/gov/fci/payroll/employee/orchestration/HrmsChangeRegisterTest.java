package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * The I-21 register exports' columns (plan 16 phase 16b; SRS-1-30, SRS-1-71, SRS-1-22, SRS-G-13): a
 * DOB row carries the retirement and EPS cut-off dates only when their ages are configured; an
 * order held in HRMS shows so; the reason is the rejection, else the return; the HRMS register
 * marks an overdue task; an HQ-wide export is checked at the user's own office; a failing file
 * stops the export.
 */
class HrmsChangeRegisterTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final HrmsChangeService changes = mock(HrmsChangeService.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final HrmsChangeRegister register =
            new HrmsChangeRegister(
                    named,
                    policy,
                    changes,
                    parameters,
                    mock(in.gov.fci.payroll.organisation.api.OrgDirectory.class));

    HrmsChangeRegisterTest() {
        // The register filters as the service builds them, the unit alone standing for its subtree.
        when(changes.params(org.mockito.ArgumentMatchers.<Long>any(), any()))
                .thenAnswer(
                        i -> {
                            Long unit = i.getArgument(0);
                            return HrmsChangeService.params(
                                    unit == null ? null : java.util.Set.of(unit),
                                    (HrmsChangeService.Filter) i.getArgument(1));
                        });
    }

    private final HrmsChangeService.Filter filter =
            new HrmsChangeService.Filter(null, null, null, false);

    /** Records what the export writes. */
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

    private static AccessContext user() {
        return new AccessContext(
                3L, "maker@x", "Maker", "s", "P", 300L, null, List.of(), Map.of(), Set.of(),
                Set.of(), Set.of());
    }

    private static HrmsChangeService.TaskView task(
            String field,
            String value,
            boolean held,
            String returned,
            String rejected,
            boolean overdue) {
        return new HrmsChangeService.TaskView(
                1L,
                "HCA-1",
                "100041",
                "A B",
                field,
                field,
                "G",
                "old",
                value,
                LocalDate.of(2026, 10, 1),
                null,
                "HRMS",
                "APPLIED",
                "202610",
                false,
                Instant.parse("2026-10-01T00:00:00Z"),
                25L,
                false,
                "O/1",
                LocalDate.of(2026, 9, 1),
                "ZM",
                held,
                "m",
                "c",
                returned,
                rejected,
                9L,
                null,
                overdue,
                null);
    }

    private void rows(Long count, HrmsChangeService.TaskView... views) throws Exception {
        when(named.queryForObject(
                        contains(" FROM hrms_change_task t"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(count);
        ResultSet rs = mock(ResultSet.class);
        doAnswer(
                        i -> {
                            RowCallbackHandler h = i.getArgument(2);
                            for (int k = 0; k < views.length; k++) {
                                h.processRow(rs);
                            }
                            return null;
                        })
                .when(named)
                .query(
                        contains(" FROM hrms_change_task t"),
                        any(SqlParameterSource.class),
                        any(RowCallbackHandler.class));
        if (views.length > 0) {
            HrmsChangeService.TaskView[] rest = Arrays.copyOfRange(views, 1, views.length);
            when(changes.view(any(), any())).thenReturn(views[0], rest);
        }
    }

    @Test
    void SRS_1_22_aDobRowCarriesItsDatesOnlyWhenTheAgesAreConfigured() throws Exception {
        when(changes.scopeUnit(any(), eq("VIEW"))).thenReturn(null);
        when(parameters.configured(SystemParameters.Code.RETIREMENT_AGE))
                .thenReturn(OptionalLong.of(60));
        when(parameters.configured(SystemParameters.Code.EPS_CUTOFF_AGE))
                .thenReturn(OptionalLong.empty());
        rows(
                2L,
                task("DATE_OF_BIRTH", "1966-05-20", true, "check", "wrong", false),
                task("NAME", "A C", false, "check", null, false));
        Sink sink = new Sink();
        register.export(user(), filter, HrmsChangeRegister.Register.NAME_DOB, sink);
        List<Object> dob = sink.rows.get(0);
        assertThat(dob).containsSubsequence("wrong", "HELD_IN_HRMS", "", "");
        assertThat(dob.get(11)).isEqualTo(LocalDate.of(2026, 5, 31));
        assertThat(dob.get(12)).as("EPS cut-off age not configured").isEqualTo("");
        List<Object> name = sink.rows.get(1);
        assertThat(name).containsSubsequence("check", "O/1");
        assertThat(name.get(11)).isEqualTo("");
        verify(policy).requireAt(any(), eq(HrmsChangeService.SCREEN), eq("EXPORT"), eq(300L));
    }

    /** SRS-1-22: without the retirement age configured a DOB row shows no retirement date. */
    @Test
    void SRS_1_22_aDobRowShowsNoRetirementDateWhileTheAgeIsNotConfigured() throws Exception {
        when(changes.scopeUnit(any(), eq("VIEW"))).thenReturn(null);
        when(parameters.configured(SystemParameters.Code.RETIREMENT_AGE))
                .thenReturn(OptionalLong.empty());
        when(parameters.configured(SystemParameters.Code.EPS_CUTOFF_AGE))
                .thenReturn(OptionalLong.empty());
        rows(1L, task("DATE_OF_BIRTH", "1966-05-20", false, null, null, false));
        Sink sink = new Sink();
        register.export(user(), filter, HrmsChangeRegister.Register.NAME_DOB, sink);
        assertThat(sink.rows.get(0).get(11)).as("retirement age not configured").isEqualTo("");
        assertThat(sink.rows.get(0).get(12)).isEqualTo("");
    }

    @Test
    void SRS_1_71_theHrmsRegisterMarksAnOverdueTask() throws Exception {
        when(changes.scopeUnit(any(), eq("VIEW"))).thenReturn(301L);
        when(parameters.configured(any())).thenReturn(OptionalLong.empty());
        rows(
                2L,
                task("BASIC_PAY", "61200", false, null, null, true),
                task("BASIC_PAY", "61200", false, null, null, false));
        Sink sink = new Sink();
        register.export(user(), filter, HrmsChangeRegister.Register.HRMS, sink);
        assertThat(sink.rows).extracting(r -> r.get(17)).containsExactly("Y", "N");
        verify(policy).requireAt(any(), eq(HrmsChangeService.SCREEN), eq("EXPORT"), eq(301L));
    }

    @Test
    void SRS_G_13_noCountIsNoRowsAndAFailingFileStopsTheExport() throws Exception {
        when(parameters.configured(any())).thenReturn(OptionalLong.empty());
        rows(null);
        Sink empty = new Sink();
        register.export(user(), filter, HrmsChangeRegister.Register.HRMS, empty);
        assertThat(empty.rows).isEmpty();
        rows(1L, task("BASIC_PAY", "61200", false, null, null, false));
        Sink failing = new Sink();
        failing.fail = true;
        assertThatThrownBy(
                        () ->
                                register.export(
                                        user(), filter, HrmsChangeRegister.Register.HRMS, failing))
                .isInstanceOf(IOException.class)
                .hasMessage("disk full");
    }

    /** One HRMS Update Request row: no last name, no fetch result, not linked. */
    private void request(Long count) throws Exception {
        when(named.queryForObject(
                        contains(" FROM hrms_update_request r"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(count);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("request_no")).thenReturn("HUR-2026-000007");
        when(rs.getString("emp_num")).thenReturn("100041");
        when(rs.getString("first_name")).thenReturn("Usha");
        when(rs.getString("field_label")).thenReturn("Bank account");
        when(rs.getString("raised_by")).thenReturn("100041");
        when(rs.getString("request_status")).thenReturn("NOT_YET_IN_HRMS");
        doAnswer(
                        i -> {
                            ((RowCallbackHandler) i.getArgument(2)).processRow(rs);
                            return null;
                        })
                .when(named)
                .query(
                        contains(" FROM hrms_update_request r"),
                        any(SqlParameterSource.class),
                        any(RowCallbackHandler.class));
    }

    @Test
    void SRS_17_15_eachUpdateRequestAndItsOutcomeIsInTheHrmsRegisterOnly() throws Exception {
        when(changes.scopeUnit(any(), eq("VIEW"))).thenReturn(null);
        when(parameters.configured(any())).thenReturn(OptionalLong.empty());
        rows(1L, task("BASIC_PAY", "61200", false, null, null, false));
        request(1L);
        Sink sink = new Sink();
        register.export(user(), filter, HrmsChangeRegister.Register.HRMS, sink);
        assertThat(sink.rows).hasSize(2);
        assertThat(sink.rows.get(0)).hasSize(21).endsWith("", "");
        List<Object> req = sink.rows.get(1);
        assertThat(req).hasSize(21);
        assertThat(req.get(0)).as("not linked to a task").isEqualTo("");
        assertThat(req.get(3)).isEqualTo("Usha");
        assertThat(req.get(14)).isEqualTo("NOT_YET_IN_HRMS");
        assertThat(req.subList(19, 21)).containsExactly("HUR-2026-000007", "");
        // The Name / DOB register carries the tasks only.
        Sink nameDob = new Sink();
        rows(1L, task("NAME", "A C", false, null, null, false));
        register.export(user(), filter, HrmsChangeRegister.Register.NAME_DOB, nameDob);
        assertThat(nameDob.rows).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_17_15_theExportSizeCountsTheRequestsOfTheHrmsRegisterOnly() throws Exception {
        when(changes.scopeUnit(any(), eq("VIEW"))).thenReturn(301L);
        when(named.query(
                        anyString(),
                        any(SqlParameterSource.class),
                        any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(new HrmsChangeRegister.Count("APPLIED", 2)));
        request(3L);
        assertThat(register.exportSize(user(), filter)).isEqualTo(5);
        assertThat(
                        register.exportSize(
                                user(), new HrmsChangeService.Filter(null, null, null, true)))
                .isEqualTo(2);
        request(null);
        assertThat(register.exportSize(user(), filter)).isEqualTo(2);
    }

    @Test
    void SRS_17_15_aFailingFileStopsTheExportAtAnUpdateRequestRowWithTheOriginalIoException()
            throws Exception {
        when(changes.scopeUnit(any(), eq("VIEW"))).thenReturn(null);
        when(parameters.configured(any())).thenReturn(OptionalLong.empty());
        rows(null);
        request(1L);
        Sink failing = new Sink();
        failing.fail = true;

        assertThatThrownBy(
                        () ->
                                register.export(
                                        user(), filter, HrmsChangeRegister.Register.HRMS, failing))
                .isInstanceOf(IOException.class)
                .hasMessage("disk full");
        assertThat(failing.rows).as("nothing is written after the failure").isEmpty();
    }

    @Test
    void SRS_17_15_anUpdateRequestRowJoinsTheFirstAndLastNameAndShowsTheFetchResult()
            throws Exception {
        when(changes.scopeUnit(any(), eq("VIEW"))).thenReturn(null);
        when(parameters.configured(any())).thenReturn(OptionalLong.empty());
        rows(null);
        when(named.queryForObject(
                        contains(" FROM hrms_update_request r"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(1L);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("request_no")).thenReturn("HUR-2026-000008");
        when(rs.getString("emp_num")).thenReturn("100042");
        when(rs.getString("first_name")).thenReturn("Usha");
        when(rs.getString("last_name")).thenReturn("Rao");
        when(rs.getString("field_label")).thenReturn("Bank account");
        when(rs.getString("task_no")).thenReturn("HCA-1");
        when(rs.getString("hrms_reference")).thenReturn("HR-77");
        when(rs.getString("fetch_result")).thenReturn("FETCHED");
        when(rs.getString("request_status")).thenReturn("APPLIED");
        doAnswer(
                        i -> {
                            ((RowCallbackHandler) i.getArgument(2)).processRow(rs);
                            return null;
                        })
                .when(named)
                .query(
                        contains(" FROM hrms_update_request r"),
                        any(SqlParameterSource.class),
                        any(RowCallbackHandler.class));
        Sink sink = new Sink();

        register.export(user(), filter, HrmsChangeRegister.Register.HRMS, sink);

        assertThat(sink.rows).hasSize(1);
        List<Object> row = sink.rows.get(0);
        assertThat(row.get(0)).isEqualTo("HCA-1");
        assertThat(row.get(1)).isEqualTo("HR-77");
        assertThat(row.get(3)).isEqualTo("Usha Rao");
        assertThat(row.subList(19, 21)).containsExactly("HUR-2026-000008", "FETCHED");
    }
}
