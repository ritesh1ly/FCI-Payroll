package in.gov.fci.payroll.settlement.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * O-05 Final Settlement list (SRS-10-11): a member without a first or last name is named by the
 * other; a member of no office shows none; each case lists the clearances it still awaits; an empty
 * page reads no clearances; a blank EIS filter is none.
 */
class SettlementViewsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final SettlementViews views =
            new SettlementViews(
                    db.named,
                    mock(Authorization.class),
                    mock(in.gov.fci.payroll.platform.approval.ApprovalStatuses.class),
                    java.time.Clock.systemUTC());

    private static java.util.Map<String, Object> settlement(
            long id, String first, String last, String office) {
        return row(
                "settlement_case_id",
                id,
                "case_reference",
                "FS-" + id,
                "emp_num",
                "E" + id,
                "first_name",
                first,
                "last_name",
                last,
                "location_code",
                office,
                "location_name",
                office == null ? null : "Delhi",
                "separation_type",
                "SUPERANNUATION",
                "separation_date",
                Date.valueOf(LocalDate.of(2026, 9, 30)),
                "case_stage",
                1,
                "settlement_status",
                "DRAFT",
                "net_payable",
                BigDecimal.TEN);
    }

    @Test
    void SRS_10_11_namesOfficesAndAwaitedClearancesAreShownAsHeld() {
        db.rows(
                        "ORDER BY c.settlement_case_id DESC OFFSET",
                        settlement(1L, "Asha", null, "DH001"),
                        settlement(2L, null, "Rao", null))
                .rows("FROM settlement_clearance WHERE", cols(1L, "ESTATE"), cols(1L, "LIBRARY"));
        List<SettlementViews.CaseRow> rows =
                views.cases(
                                mock(AccessContext.class),
                                new SettlementViews.Filter(null, " "),
                                new PageSpec(0, 20))
                        .items();
        assertThat(rows).extracting(SettlementViews.CaseRow::name).containsExactly("Asha", "Rao");
        assertThat(rows)
                .extracting(SettlementViews.CaseRow::office)
                .containsExactly("DH001 - Delhi", null);
        assertThat(rows.get(0).awaiting()).containsExactly("ESTATE", "LIBRARY");
        assertThat(rows.get(1).awaiting()).isEmpty();
    }

    @Test
    void SRS_10_11_anEmptyPageReadsNoClearances() {
        assertThat(
                        views.cases(
                                        mock(AccessContext.class),
                                        new SettlementViews.Filter(2, "E1"),
                                        new PageSpec(0, 20))
                                .items())
                .isEmpty();
        assertThat(db.queries("FROM settlement_clearance")).isEmpty();
    }
}
