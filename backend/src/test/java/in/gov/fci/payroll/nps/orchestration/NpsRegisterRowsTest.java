package in.gov.fci.payroll.nps.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * I-14 NPS deductions as received from HRMS (SRS-9-1a, SRS-9-2): a member without a last name (none
 * or blank) is named by the first; a record without an effective date has none; the export writes N
 * for a member HRMS does not flag.
 */
class NpsRegisterRowsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final Authorization policy = mock(Authorization.class);
    private final AccessContext hq = mock(AccessContext.class);
    private final NpsRegisterService service =
            new NpsRegisterService(db.named, mock(OrgDirectory.class), policy);

    NpsRegisterRowsTest() {
        when(hq.locationId()).thenReturn(1L);
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        db.rows(
                        "AS shown_status",
                        record(1L, null, "Y", null),
                        record(2L, " ", "N", Date.valueOf(LocalDate.of(2026, 10, 1))))
                .value("SELECT COUNT(*)", 2L);
    }

    private static Map<String, Object> record(long id, String last, String flag, Date from) {
        return row(
                "nps_deduction_id",
                id,
                "emp_num",
                "E" + id,
                "first_name",
                "Asha",
                "last_name",
                last,
                "location_name",
                null,
                "hrms_nps_flag",
                flag,
                "pran_reference",
                null,
                "employee_amount",
                BigDecimal.TEN,
                "employer_amount",
                null,
                "deducted_amount",
                null,
                "hrms_effective_from",
                from,
                "shown_status",
                "RECEIVED");
    }

    @Test
    void SRS_9_1a_aMemberWithoutALastNameIsNamedByTheFirstAndAMissingDateIsNone() {
        List<NpsRegisterService.Row> rows =
                service.page(
                                hq,
                                new NpsRegisterService.Filter("202610", null, null),
                                new PageSpec(0, 20))
                        .items();
        assertThat(rows)
                .extracting(NpsRegisterService.Row::employee)
                .containsExactly("Asha", "Asha");
        assertThat(rows.get(0).effectiveFrom()).isNull();
        assertThat(rows.get(1).effectiveFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void SRS_9_2_theExportWritesNForAMemberHrmsDoesNotFlag() throws Exception {
        ExportSink sink = mock(ExportSink.class);
        when(sink.rows()).thenReturn(2L);
        service.export(hq, new NpsRegisterService.Filter("202610", null, null), sink);
        verify(sink).row(eq("E2"), any(), any(), eq("N"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void SRS_9_1_theTilesSumTheAmountsAsReceivedForTheLatestMonthWithRecords() {
        db.value("SELECT MAX(n.payroll_yymm)", "202601")
                .rows(
                        "AS mismatches",
                        row(
                                "members",
                                4L,
                                "employee_total",
                                new BigDecimal("16760"),
                                "employer_total",
                                new BigDecimal("23464"),
                                "mismatches",
                                2L));
        assertThat(service.summary(hq, "202610"))
                .isEqualTo(
                        new NpsRegisterService.Summary(
                                "202601", 4, new BigDecimal("16760"), new BigDecimal("23464"), 2));
    }

    @Test
    void SRS_9_1_aUnitWithoutRecordsReadsZeroAndABadMonthIsRefused() {
        db.value("SELECT MAX(n.payroll_yymm)", ScriptedJdbc.NULL);
        assertThat(service.summary(hq, "202610"))
                .isEqualTo(
                        new NpsRegisterService.Summary(
                                null, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.summary(hq, "202613"))
                .isInstanceOf(in.gov.fci.payroll.platform.error.FciException.class);
    }
}
