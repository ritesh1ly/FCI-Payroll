package in.gov.fci.payroll.leave.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportIncompleteException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * SRS-LV-23 (FRS V0.5 §7.15.7.2; US-19-18): the Leave Recovery Report comes only from what a lock
 * posted, for the I-11 EXPORT right and the session's offices, with the FRS columns and totals by
 * unit and leave type; a reversed month range is refused.
 */
class LeaveRecoveryReportServiceTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final Authorization policy = mock(Authorization.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final AccessContext context =
            new AccessContext(
                    1L,
                    "u@x",
                    "U",
                    "s",
                    "P",
                    40L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(40L));
    private final LeaveRecoveryReportService report =
            new LeaveRecoveryReportService(db.named, policy, org);

    private static final LeaveRecoveryReportService.Filter JULY =
            new LeaveRecoveryReportService.Filter("202607", "202607", null, null, null, null, null);

    /** Records what the file receives. */
    private static final class Sink implements ExportSink {
        final List<Object[]> rows = new ArrayList<>();
        final List<String> header = new ArrayList<>();
        final List<String> titles = new ArrayList<>();

        @Override
        public void header(String... titles) {
            header.addAll(List.of(titles));
        }

        @Override
        public void title(String... lines) {
            titles.addAll(List.of(lines));
        }

        @Override
        public void row(Object... cells) {
            rows.add(cells);
        }

        @Override
        public long rows() {
            return rows.size();
        }

        @Override
        public void close() {
            // Nothing to complete.
        }
    }

    private static Map<String, Object> line(String unit, String type, String amount) {
        return row(
                "emp_num",
                "881403",
                "first_name",
                "Manish",
                "last_name",
                "Verma",
                "location_code",
                unit,
                "location_name",
                "DO Delhi",
                "leave_type_code",
                type,
                "event_name",
                "CONVERSION",
                "hrms_sanction_ref",
                "HR/LV/2026/80277",
                "task_no",
                "HCA-2026-9423",
                "event_date",
                Timestamp.from(Instant.parse("2026-07-16T06:00:00Z")),
                "period_from",
                LocalDate.of(2026, 6, 10),
                "period_to",
                LocalDate.of(2026, 6, 14),
                "days",
                new BigDecimal("5"),
                "legacy_pay_code",
                101,
                "component_name",
                "Basic",
                "total_amount",
                new BigDecimal(amount),
                "posted_to_yymm",
                "202607",
                "recovered",
                new BigDecimal(amount),
                "residual",
                BigDecimal.ZERO,
                "event_status",
                "POSTED",
                "maker_user",
                "maker.delhi",
                "checker_user",
                "ddo.delhi");
    }

    @Test
    void SRS_LV_23_theReportHasTheFrsColumnsAndTotalsByUnitAndLeaveType() throws Exception {
        db.value("COUNT(*) FROM (SELECT", 2L);
        db.rows("AS event_name", line("DH001", "LWP", "-9750"), line("DH001", "HPL", "-4155"));
        Sink sink = new Sink();
        report.report(context, JULY, sink);
        assertThat(sink.header)
                .contains(
                        "employee_code",
                        "unit",
                        "event_type",
                        "event_reference",
                        "period_from",
                        "recovery_or_arrear",
                        "cpf_tds_pt_effect",
                        "cycle_recovered",
                        "amount_recovered",
                        "residual_carried_forward",
                        "maker",
                        "checker");
        assertThat(sink.titles).anyMatch(l -> l.contains("202607"));
        assertThat(sink.rows).hasSize(5);
        assertThat(sink.rows.get(0)[1]).isEqualTo("Manish Verma");
        assertThat(sink.rows.get(0)[2]).isEqualTo("DH001 - DO Delhi");
        assertThat(sink.rows.get(2)[0]).isEqualTo("TOTAL UNIT");
        assertThat((BigDecimal) sink.rows.get(2)[12]).isEqualByComparingTo("-13905");
        assertThat(sink.rows.get(3)[0]).isEqualTo("TOTAL LEAVE TYPE");
    }

    @Test
    void SRS_LV_23_onlyWhatALockPostedIsReported() throws Exception {
        db.value("COUNT(*) FROM (SELECT", 0L);
        report.report(context, JULY, new Sink());
        assertThat(db.queries("AS event_name").getFirst())
                .contains("HAVING SUM(CASE WHEN d.arrear_status IN ('POSTED'")
                .contains("ev.event_status IN ('APPROVED', 'POSTED')")
                .contains("ev.posted_to_yymm BETWEEN :fromMonth AND :toMonth");
    }

    @Test
    void SRS_LV_23_aReferenceFallsBackToTheTask_aMemberWithNoLastNameOrUnitStillPrints()
            throws Exception {
        var l = line("DH001", "LWP", "-1");
        l.put("HRMS_SANCTION_REF", null);
        l.put("LAST_NAME", null);
        l.put("LOCATION_CODE", null);
        db.value("COUNT(*) FROM (SELECT", 1L);
        db.rows("AS event_name", l);
        Sink sink = new Sink();
        report.report(context, JULY, sink);
        assertThat(sink.rows.get(0)[1]).isEqualTo("Manish");
        assertThat(sink.rows.get(0)[2]).isEqualTo("");
        assertThat(sink.rows.get(0)[5]).isEqualTo("HCA-2026-9423");
    }

    @Test
    void SRS_LV_23_aRowCountThatChangesMidExportFailsRatherThanShortens() {
        db.value("COUNT(*) FROM (SELECT", 3L);
        db.rows("AS event_name", line("DH001", "LWP", "-1"));
        assertThatThrownBy(() -> report.report(context, JULY, new Sink()))
                .isInstanceOf(ExportIncompleteException.class);
    }

    @Test
    void SRS_LV_23_aReversedMonthRangeIsRefused() {
        assertThatThrownBy(
                        () ->
                                report.size(
                                        context,
                                        new LeaveRecoveryReportService.Filter(
                                                "202608", "202607", null, null, null, null, null)))
                .isInstanceOf(FciException.class);
    }

    @Test
    void RBAC_aRoleWithoutI11ExportIsRefused_theAdministratorHasNoLeaveData() {
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .require(any(), any(), any());
        assertThatThrownBy(() -> report.size(context, JULY)).isInstanceOf(FciException.class);
    }

    @Test
    void RBAC_anOfficeFilterNarrowsTheScopeAndNeverWidensIt() {
        when(org.subtree(50L)).thenReturn(Set.of(50L, 51L));
        db.value("COUNT(*) FROM (SELECT", 0L);
        report.size(
                context,
                new LeaveRecoveryReportService.Filter(
                        "202607", "202607", 50L, null, null, null, null));
        assertThat(db.queries("COUNT(*) FROM (SELECT").getFirst())
                .contains("(:all = 'Y' OR e.location_id IN (:locations))")
                .contains("(:office = 'N' OR e.location_id IN (:offices))");
    }
}
