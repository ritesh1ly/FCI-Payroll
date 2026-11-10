package in.gov.fci.payroll.payroll.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * C-02 read side as Wireframes v6.1 draws it (OD-121; SRS l.3686-3692, FRS §7.4, BR-AUDIT-009 /
 * 010, SRS-G-13): nothing is computed here, every read is checked at the session's cycle unit, and
 * an id of another office answers as unknown.
 */
class ComputationResultsTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 1);
    private static final ComputationResults.Filter NONE =
            new ComputationResults.Filter(null, null, null);
    private static final String CYCLE = "SELECT cycle_instance_id, current_run_id";
    private static final String COUNT = "SELECT COUNT(*) FROM payroll_result r";

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final Authorization policy = mock(Authorization.class);
    private final ComputationResults service = new ComputationResults(db.jdbc, org, policy);
    private final AccessContext user =
            new AccessContext(
                    3L,
                    "maker@x",
                    "User",
                    "s",
                    "P",
                    300L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of("PAYROLL_MAKER"),
                    Set.of(300L));

    ComputationResultsTest() {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        when(org.cycleUnitOf(300L)).thenReturn(Optional.of(unit));
    }

    private void ofRecord() {
        db.rows(CYCLE, row("CYCLE_INSTANCE_ID", 9L, "CURRENT_RUN_ID", 31L));
    }

    private static Map<String, Object> resultRow(String status, String group, Object hra) {
        return cols(
                7L,
                "104882",
                "Rakesh",
                "Sharma",
                new BigDecimal("127350"),
                new BigDecimal("35387"),
                new BigDecimal("91963"),
                "CURRENT",
                "Y",
                "IDA",
                new BigDecimal("62400"),
                new BigDecimal("31200"),
                "X",
                "E-4",
                hra,
                status,
                group);
    }

    @Test
    void SRS_G_29_theShownPeriodIsTheSessionsWhenItHasARun() {
        ofRecord();
        assertThat(service.resultsPeriod(user, PERIOD))
                .contains(new ComputationResults.ResultsPeriod("2026-01", "ACTUAL"));
        verify(policy).requireAt(user, "C-02", "VIEW", 300L);
    }

    @Test
    void SRS_G_29_withoutARunTheUnitsLatestEarlierRunIsShown() {
        db.rows("SELECT c.payroll_yymm", cols("202512"));
        assertThat(service.resultsPeriod(user, PERIOD))
                .contains(new ComputationResults.ResultsPeriod("2025-12", "ACTUAL"));
        assertThat(db.queries("c.payroll_yymm < ?")).hasSize(1);
    }

    @Test
    void SRS_G_29_aUnitWithoutAnyRunHasNothingToShow() {
        assertThat(service.resultsPeriod(user, PERIOD)).isEmpty();
    }

    @Test
    void BR_CYC_012_aFinishedDryRunIsShownBeforeTheRunOfRecord() {
        db.rows(CYCLE, row("CYCLE_INSTANCE_ID", 9L, "CURRENT_RUN_ID", null));
        db.rows("SELECT run_id FROM computation_run", cols(44L));
        assertThat(service.resultsPeriod(user, PERIOD))
                .contains(new ComputationResults.ResultsPeriod("2026-01", "SIMULATION"));
    }

    @Test
    void aCycleWithoutARunShowsNoRow() {
        db.rows(CYCLE, row("CYCLE_INSTANCE_ID", 9L, "CURRENT_RUN_ID", null));
        assertThat(service.results(user, PERIOD, NONE, new PageSpec(0, 20)).items()).isEmpty();
        assertThat(db.queries("FROM payroll_result r")).isEmpty();
    }

    @Test
    void FRS_7_4_1_aRowCarriesTheDrawnColumnsAndOtherIsTheRestOfGross() {
        ofRecord();
        db.value(COUNT, 21L);
        db.rows("SELECT r.payroll_result_id", resultRow("COMPUTED", "COMPUTED", null));
        var page = service.results(user, PERIOD, NONE, new PageSpec(1, 20));
        assertThat(page.total()).isEqualTo(21);
        ComputationResults.ResultRow r = page.items().getFirst();
        assertThat(r.name()).isEqualTo("Rakesh Sharma");
        assertThat(r.locked()).isTrue();
        assertThat(r.scale()).isEqualTo("E-4");
        assertThat(r.hra()).isNull();
        assertThat(r.other()).isEqualByComparingTo("33750");
        assertThat(r.rowStatus()).isEqualTo("COMPUTED");
        assertThat(r.statusGroup()).isEqualTo("COMPUTED");
    }

    @Test
    void FRS_7_4_1_aMemberWithoutBasicOrDaLinesCountsThemAsNothingInOther() {
        ofRecord();
        db.value(COUNT, 1L);
        Map<String, Object> bare = resultRow("MISSING_BANK", "EXCEPTION", new BigDecimal("1000"));
        bare.put("C11", null);
        bare.put("C12", null);
        db.rows("SELECT r.payroll_result_id", bare);
        ComputationResults.ResultRow r =
                service.results(user, PERIOD, NONE, new PageSpec(0, 20)).items().getFirst();
        assertThat(r.other()).isEqualByComparingTo("126350");
        assertThat(r.statusGroup()).isEqualTo("EXCEPTION");
    }

    @Test
    void SRS_G_54_theTilesAddUpThePatternsAndCountEachKind() {
        ofRecord();
        db.rows(
                "GROUP BY e.pay_pattern",
                cols("IDA", "COMPUTED", 4L, m("364465"), m("92467"), m("271998")),
                cols("IDA", "EXCEPTION", 2L, m("140180"), m("85330"), m("54850")),
                cols("CDA", "HELD", 1L, m("113690"), m("27480"), m("86210")),
                cols("FND", "COMPUTED", 2L, m("239320"), m("67090"), m("172230")),
                cols("CDA", "EXCEPTION", 1L, m("118359"), m("28700"), m("89659")));
        db.rows("SELECT COUNT(*), COALESCE", cols(2L, m("1"), m("2"), m("3")));
        ComputationResults.Summary s = service.summary(user, PERIOD, NONE);
        assertThat(s.ida()).isEqualTo(new ComputationResults.PatternTile(6, 2));
        assertThat(s.cda())
                .as("FND follows CDA")
                .isEqualTo(new ComputationResults.PatternTile(4, 1));
        assertThat(s.held()).isEqualTo(1);
        assertThat(s.gross()).isEqualByComparingTo("976014");
        assertThat(s.deductions()).isEqualByComparingTo("301067");
        assertThat(s.net()).isEqualByComparingTo("674947");
        assertThat(s.shown().members()).isEqualTo(2);
    }

    private static BigDecimal m(String v) {
        return new BigDecimal(v);
    }

    @Test
    void SRS_G_54_aUnitWithoutARunHasEmptyTiles() {
        ComputationResults.Summary s = service.summary(user, PERIOD, NONE);
        assertThat(s.ida().members()).isZero();
        assertThat(s.shown().gross()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void FRS_7_4_theTraceSortsLinesIntoTheDrawnRowsWithTheirRule() {
        db.rows(
                "SELECT r.gross_earnings",
                cols(
                        m("127350"),
                        m("35387"),
                        m("91963"),
                        "104882",
                        "Rakesh",
                        "Sharma",
                        "IDA",
                        "N",
                        5L,
                        "X"));
        db.rows("FROM lease_accommodation_detail", cols("SELF_LEASE", m("28000"), "Y"));
        // One scripted answer for every statutory head: the last asked (PT) holds the code.
        db.rows("v.numeric_attribute", cols(new BigDecimal("904")));
        db.rows(
                "c.rate_applied, c.basis_amount, d.rule_key",
                cols(101, "Basic Pay", "EARNING", m("62400"), null, null, null, null),
                cols(111, "DA", "EARNING", m("31200"), m("50"), m("62400"), "DA.IDA.RATE", 14),
                cols(121, "HRA", "EARNING", m("1"), null, null, null, null),
                cols(131, "CCA", "EARNING", m("3600"), null, null, null, null),
                cols(904, "CPF", "DEDUCTION", m("11232"), null, null, null, null),
                cols(504, "LIC", "DEDUCTION", m("9055"), null, null, null, null));
        ComputationResults.Trace t = service.trace(user, PERIOD, 7L);
        assertThat(t.name()).isEqualTo("Rakesh Sharma");
        assertThat(t.stagnationCase()).isFalse();
        assertThat(t.accommodation())
                .isEqualTo(new ComputationResults.Accommodation("SELF_LEASE", m("28000"), true));
        assertThat(t.lines())
                .extracting(ComputationResults.TraceLine::head)
                .containsExactly("BASIC", "DA", "HRA", "EARNING", "PT", "DEDUCTION");
        assertThat(t.lines().get(1).rule()).isEqualTo("DA.IDA.RATE");
        assertThat(t.lines().get(1).ruleVersion()).isEqualTo(14);
    }

    @Test
    void FRS_7_4_aStagnationCaseWithoutALeaseRecord() {
        db.rows(
                "SELECT r.gross_earnings",
                cols(m("1"), m("0"), m("1"), "1", "A", "B", null, "Y", 5L, null));
        ComputationResults.Trace t = service.trace(user, PERIOD, 7L);
        assertThat(t.stagnationCase()).isTrue();
        assertThat(t.accommodation()).isNull();
        assertThat(t.lines()).isEmpty();
    }

    @Test
    void SRS_G_29_aResultOfAnotherOfficeAnswersAsUnknown() {
        assertThatThrownBy(() -> service.trace(user, PERIOD, 7L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
    }

    @Test
    void R_RBAC_noReadWithoutTheRightAtTheUnit() {
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .requireAt(eq(user), eq("C-02"), eq("VIEW"), eq(300L));
        assertThatThrownBy(() -> service.results(user, PERIOD, NONE, new PageSpec(0, 20)))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.summary(user, PERIOD, NONE))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.trace(user, PERIOD, 7L)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.pliPayments(user, PERIOD, new PageSpec(0, 20)))
                .isInstanceOf(FciException.class);
        assertThat(db.queries("FROM")).isEmpty();
    }

    @Test
    void R_RBAC_noSessionOfficeNoRead() {
        AccessContext nowhere =
                new AccessContext(
                        3L, "x", "U", "s", "P", null, null, List.of(), Map.of(), Set.of(), Set.of(),
                        Set.of());
        assertThatThrownBy(() -> service.resultsPeriod(nowhere, PERIOD))
                .isInstanceOf(FciException.class);
        verify(policy, never()).requireAt(any(), any(), any(), any());
    }

    @Test
    void BR_AUDIT_010_pliEntriesCarryTheirInvoiceStatusAndTheFinancialYearOfThePeriod() {
        db.value("SELECT COUNT(*) FROM pli_transaction", 2L);
        Instant sent = Instant.parse("2026-01-28T00:00:00Z");
        db.rows(
                "SELECT t.pli_txn_id",
                cols(
                        7L,
                        "104882",
                        "Rakesh",
                        "Sharma",
                        "PAYMENT",
                        m("42600"),
                        202601000622L,
                        "SENT_TO_APPS",
                        Timestamp.from(sent),
                        "NOT_REQUESTED"),
                cols(
                        12345L,
                        "106620",
                        "Nisha",
                        "Gupta",
                        "ADJUSTMENT",
                        m("3200"),
                        null,
                        "NOT_RAISED",
                        null,
                        "NOT_REQUESTED"));
        var page = service.pliPayments(user, PERIOD, new PageSpec(0, 20));
        assertThat(page.total()).isEqualTo(2);
        ComputationResults.PliPayment first = page.items().getFirst();
        assertThat(first.entry()).isEqualTo("PLI-2025-0007");
        assertThat(first.finYear()).isEqualTo("2025-26");
        assertThat(first.member()).isEqualTo("Rakesh Sharma");
        assertThat(first.invoiceNo()).isEqualTo(202601000622L);
        assertThat(first.sentToAppsAt()).isEqualTo(sent);
        assertThat(page.items().get(1).entry()).isEqualTo("PLI-2025-12345");
        assertThat(page.items().get(1).invoiceNo()).isNull();
        assertThat(page.items().get(1).sentToAppsAt()).isNull();
    }

    /** A sink that keeps the rows. */
    private static final class Rows implements ExportSink {
        final List<Object[]> rows = new ArrayList<>();
        String[] header;

        @Override
        public void header(String... titles) {
            header = titles;
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
        public void close() {}
    }

    @Test
    void SRS_G_13_theExportIsOnItsOwnRightAndCarriesEveryRowOfTheFilter() throws IOException {
        ofRecord();
        db.value(COUNT, 1L);
        db.rows("SELECT r.payroll_result_id", resultRow("COMPUTED", "COMPUTED", null));
        assertThat(service.exportSize(user, PERIOD, NONE)).isEqualTo(1);
        Rows sink = new Rows();
        service.export(user, PERIOD, NONE, sink);
        verify(policy, times(2)).requireAt(user, "C-02", "EXPORT", 300L);
        assertThat(sink.header).hasSize(12);
        assertThat(sink.rows).hasSize(1);
        assertThat(sink.rows.getFirst()[0]).isEqualTo("104882");
        assertThat(sink.rows.getFirst()[11]).isEqualTo("COMPUTED");
    }

    @Test
    void SRS_G_13_withoutARunTheExportIsTheHeaderAlone() throws IOException {
        assertThat(service.exportSize(user, PERIOD, NONE)).isZero();
        Rows sink = new Rows();
        service.export(user, PERIOD, NONE, sink);
        assertThat(sink.header).isNotEmpty();
        assertThat(sink.rows).isEmpty();
    }

    @Test
    void SRS_G_13_aShortFileIsNeverDeliveredAsComplete() {
        ofRecord();
        db.value(COUNT, 2L);
        assertThatThrownBy(() -> service.export(user, PERIOD, NONE, new Rows()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void theFiltersAreBoundNotJoinedIntoTheStatement() {
        ofRecord();
        db.value(COUNT, 0L);
        service.results(
                user,
                PERIOD,
                new ComputationResults.Filter("CDA", "HELD", " sharma' OR 1=1 "),
                new PageSpec(0, 20));
        assertThat(db.queries(COUNT))
                .hasSize(1)
                .allSatisfy(sql -> assertThat(sql).doesNotContain("sharma"));
        service.results(
                user,
                PERIOD,
                new ComputationResults.Filter(null, null, "   "),
                new PageSpec(0, 20));
        assertThat(db.queries(COUNT)).hasSize(2);
    }
}
