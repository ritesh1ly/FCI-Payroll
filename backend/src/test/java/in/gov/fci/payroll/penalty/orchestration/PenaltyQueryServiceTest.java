package in.gov.fci.payroll.penalty.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.sql.ResultSet;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** I-06 order register and recovery report (SRS-14-1): the scope and the report months. */
class PenaltyQueryServiceTest {

    private final OrgDirectory org = mock(OrgDirectory.class);
    private final Authorization policy = mock(Authorization.class);
    private final AccessContext context = mock(AccessContext.class);
    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final PenaltyQueryService service = new PenaltyQueryService(named, org, policy);

    private static void refused(ErrorCode code, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    @Test
    void SRS_14_1_aUserWithoutAnOfficeOrCycleUnitSeesNoOrder() {
        when(context.locationId()).thenReturn(null);
        refused(CommonErrorCode.ACCESS_DENIED, () -> service.scope(context));
        when(context.locationId()).thenReturn(5L);
        when(org.cycleUnitOf(5L)).thenReturn(Optional.empty());
        refused(ApiErrorCode.CYCLE_NO_UNIT, () -> service.scope(context));
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        assertThat(service.scope(context)).isNull();
    }

    @Test
    void SRS_14_1_theReportMonthsAreYyyymmInOrder() {
        when(context.locationId()).thenReturn(5L);
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        refused(
                CommonErrorCode.VALIDATION_FAILED,
                () -> service.reportSize(context, null, "202604"));
        refused(
                CommonErrorCode.VALIDATION_FAILED,
                () -> service.reportSize(context, "202604", null));
        refused(
                CommonErrorCode.VALIDATION_FAILED,
                () -> service.reportSize(context, "202613", "202614"));
        refused(
                CommonErrorCode.VALIDATION_FAILED,
                () -> service.reportSize(context, "202605", "202604"));
        assertThat(service.reportSize(context, "202604", "202604")).isZero();
    }

    @Test
    void SRS_14_1_namesAndFiltersAreTrimmed() {
        assertThat(PenaltyQueryService.name("Asha", null)).isEqualTo("Asha");
        assertThat(PenaltyQueryService.name("Asha", " ")).isEqualTo("Asha");
        assertThat(PenaltyQueryService.name("Asha", "Rao")).isEqualTo("Asha Rao");
        assertThat(PenaltyQueryService.blank(null)).isNull();
        assertThat(PenaltyQueryService.blank("  ")).isNull();
        assertThat(PenaltyQueryService.blank(" E1 ")).isEqualTo("E1");
    }

    /** A report row of an order without its period: empty period cells, nothing else missing. */
    private void oneRowWithoutPeriod() {
        doAnswer(
                        a -> {
                            ResultSet rs = mock(ResultSet.class);
                            when(rs.getString(1)).thenReturn("PN-1");
                            ((RowCallbackHandler) a.getArgument(2)).processRow(rs);
                            return null;
                        })
                .when(named)
                .query(
                        contains("GROUP BY o.order_id"),
                        any(SqlParameterSource.class),
                        any(RowCallbackHandler.class));
    }

    @Test
    void SRS_14_1_anOrderWithoutAPeriodReportsEmptyPeriodCells() throws Exception {
        when(context.locationId()).thenReturn(5L);
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        oneRowWithoutPeriod();
        ExportSink sink = mock(ExportSink.class);
        service.report(context, "202604", "202604", sink);
        verify(sink)
                .row("PN-1", null, null, null, null, null, "", "", null, null, null, null, null);
    }

    @Test
    void SRS_14_1_aWriteFailureOfTheReportIsRaisedAsItself() throws Exception {
        when(context.locationId()).thenReturn(5L);
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        oneRowWithoutPeriod();
        IOException disk = new IOException("disk full");
        ExportSink sink =
                new ExportSink() {
                    @Override
                    public void header(String... titles) {}

                    @Override
                    public void row(Object... cells) throws IOException {
                        throw disk;
                    }

                    @Override
                    public long rows() {
                        return 0;
                    }

                    @Override
                    public void close() {}
                };
        assertThatThrownBy(() -> service.report(context, "202604", "202604", sink)).isSameAs(disk);
    }
}
