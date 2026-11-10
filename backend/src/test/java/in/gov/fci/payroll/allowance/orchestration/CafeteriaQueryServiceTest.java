package in.gov.fci.payroll.allowance.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * I-16 Cafeteria Allowance (SRS-1-73; plan 10 D10-6): the selections of the user's offices, a
 * member without a last name named by the first; the month's pay effect counts only a selection
 * applied (or superseded during the month), pro rata; a pending one has none; an office-scoped user
 * is filtered by office, a blank EIS is no filter.
 */
class CafeteriaQueryServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final ClaimScope scope = mock(ClaimScope.class);
    private final SystemParameters settings = mock(SystemParameters.class);
    private final CafeteriaQueryService service =
            new CafeteriaQueryService(db.named, scope, settings);

    @Test
    void SRS_1_73_theMonthsEffectCountsOnlyALiveSelection() {
        when(settings.today()).thenReturn(TODAY);
        when(scope.offices(any(), any(), any())).thenReturn(Optional.of(Set.of(40L)));
        Date from = Date.valueOf(LocalDate.of(2026, 4, 1));
        db.rows(
                        "c.hrms_request_id, c.election_status",
                        cols(
                                1L, "E1", "Asha", null, "IDA", "2026-27", 1, from, null, "HR-1",
                                "APPLIED"),
                        cols(
                                2L, "E2", "Ravi", "Kumar", "IDA", "2026-27", 1, from, null, "HR-2",
                                "PENDING"),
                        cols(
                                3L,
                                "E3",
                                "Meena",
                                "Rao",
                                "IDA",
                                "2026-27",
                                1,
                                from,
                                null,
                                "HR-3",
                                "SUPERSEDED"))
                .rows(
                        "FROM cafeteria_election_line l JOIN",
                        cols(1L, 951, "Books", new BigDecimal("1000")),
                        cols(2L, 951, "Books", new BigDecimal("1000")),
                        cols(3L, 951, "Books", new BigDecimal("500")));
        List<CafeteriaQueryService.Selection> rows =
                service.selections(mock(AccessContext.class), " ", new PageSpec(0, 20)).items();
        assertThat(rows)
                .extracting(CafeteriaQueryService.Selection::name)
                .containsExactly("Asha", "Ravi Kumar", "Meena Rao");
        assertThat(rows)
                .extracting(CafeteriaQueryService.Selection::payEffect)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("500"));
    }

    @Test
    void SRS_1_73_noSelectionReadsNoComponents() {
        when(settings.today()).thenReturn(TODAY);
        when(scope.offices(any(), any(), any())).thenReturn(Optional.empty());
        assertThat(service.selections(mock(AccessContext.class), "E9", new PageSpec(0, 20)).items())
                .isEmpty();
        assertThat(db.queries("FROM cafeteria_election_line")).isEmpty();
    }

    @Test
    void SRS_11_16_aMemberWithNoSelectionForTheYearReadsNoSelection_selectionsComeFirst() {
        when(settings.today()).thenReturn(TODAY);
        when(scope.offices(any(), any(), any())).thenReturn(Optional.of(Set.of(40L)));
        db.rows(
                "c.hrms_request_id, c.election_status",
                cols(null, "E9", "Ravi", "Das", "IDA", null, null, null, null, null, null));
        List<CafeteriaQueryService.Selection> rows =
                service.selections(mock(AccessContext.class), null, "2026-27", new PageSpec(0, 20))
                        .items();
        assertThat(rows.getFirst().id()).isNull();
        assertThat(rows.getFirst().status()).isEqualTo(CafeteriaQueryService.NO_SELECTION);
        assertThat(rows.getFirst().components()).isEmpty();
        assertThat(rows.getFirst().payEffect()).isEqualByComparingTo("0");
        assertThat(db.queries("FROM employee e LEFT JOIN cafeteria_election c")).isNotEmpty();
    }

    @Test
    void SRS_11_16_aSelectionWithItsArrearRaisedNamesTheLockedMonths() {
        when(settings.today()).thenReturn(TODAY);
        when(scope.offices(any(), any(), any())).thenReturn(Optional.of(Set.of(40L)));
        Date from = Date.valueOf(LocalDate.of(2026, 4, 1));
        db.rows(
                        "c.hrms_request_id, c.election_status",
                        cols(
                                1L,
                                "E1",
                                "Asha",
                                null,
                                "IDA",
                                "2026-27",
                                1,
                                from,
                                null,
                                "HR-1",
                                "ARREAR_RAISED"))
                .rows(
                        "a.scope_from_yymm",
                        cols(
                                1L,
                                "202604",
                                "202606",
                                java.time.Instant.parse("2026-07-02T06:00:00Z")))
                .rows(
                        "FROM cafeteria_election_line l JOIN",
                        cols(1L, 951, "Books", new BigDecimal("1000")));
        CafeteriaQueryService.Selection s =
                service.selections(mock(AccessContext.class), null, "2026-27", new PageSpec(0, 20))
                        .items()
                        .getFirst();
        assertThat(s.arrearFrom()).isEqualTo("202604");
        assertThat(s.arrearTo()).isEqualTo("202606");
        assertThat(s.arrearIn()).isEqualTo("202607");
        assertThat(s.payEffect()).isEqualByComparingTo("1000");
    }

    @Test
    void SRS_G_13_theExportWritesTheListRowForRow() throws Exception {
        when(settings.today()).thenReturn(TODAY);
        when(scope.offices(any(), any(), any())).thenReturn(Optional.empty());
        db.value("SELECT COUNT(*)", 1L);
        db.rows(
                        "c.hrms_request_id, c.election_status",
                        cols(
                                1L,
                                "E1",
                                "Asha",
                                null,
                                "IDA",
                                "2026-27",
                                1,
                                Date.valueOf(LocalDate.of(2026, 4, 1)),
                                null,
                                "HR-1",
                                "APPLIED"))
                .rows(
                        "FROM cafeteria_election_line l JOIN",
                        cols(1L, 951, "Books", new BigDecimal("1000")),
                        cols(1L, 952, "Mobile", new BigDecimal("500")));
        in.gov.fci.payroll.platform.export.ExportSink sink =
                mock(in.gov.fci.payroll.platform.export.ExportSink.class);
        when(sink.rows()).thenReturn(1L);
        service.selections(mock(AccessContext.class), null, "2026-27", sink);
        org.mockito.Mockito.verify(sink)
                .row(
                        "E1",
                        "Asha",
                        "IDA",
                        "2026-27",
                        "Books / Mobile",
                        LocalDate.of(2026, 4, 1),
                        "HR-1",
                        new BigDecimal("1500"),
                        "APPLIED");
        assertThat(service.size(mock(AccessContext.class), null, "2026-27")).isEqualTo(1L);
    }
}
