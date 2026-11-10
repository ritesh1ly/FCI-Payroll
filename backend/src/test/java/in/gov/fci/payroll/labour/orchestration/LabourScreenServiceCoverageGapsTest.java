package in.gov.fci.payroll.labour.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
import in.gov.fci.payroll.labour.orchestration.LabourScreenService.GangLine;
import in.gov.fci.payroll.labour.orchestration.LabourScreenService.MinWageLine;
import in.gov.fci.payroll.labour.orchestration.LabourScreenService.SlabLine;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * M-05, M-06 and M-09 rows as stored (OD-121; SRS-3-6, SRS-3-12, SRS-5-3, SRS-5-11, SRS-G-13): an
 * open-ended slab band has no upper bound and no step, a minimum wage without both parts has no MGW
 * (never an assumed one), the first MGW of a gang role wins, a gang waiting for the Checker is
 * flagged, the rate export of a blank payroll type is the departmental table, and the norm type
 * filter is one of the drawn three or none.
 */
class LabourScreenServiceCoverageGapsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final Authorization policy = mock(Authorization.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AccessContext context = mock(AccessContext.class);
    private final LabourScreenService service = service(db.jdbc, db.named);

    private LabourScreenService service(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        return new LabourScreenService(
                jdbc,
                named,
                policy,
                parameters,
                mock(GangStrengthService.class),
                mock(DocumentStore.class));
    }

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(LocalDate.of(2026, 11, 7));
        when(context.locationId()).thenReturn(3L);
        when(context.dataScope()).thenReturn(Set.of(3L));
        when(context.groups()).thenReturn(List.of());
    }

    @Test
    void SRS_3_6_anOpenEndedBandHasNoUpperBoundAndNoStep() {
        db.rows("FROM labour_norm WHERE norm_id", row("DEPOT_LOCATION_ID", 3L));
        db.rows(
                "FROM labour_above_norm_slab",
                row("T", "A", "O", 1, "F", 0L, "TO", 100L, "R", new BigDecimal("10"), "S", 25L),
                row("T", "A", "O", 2, "F", 101L, "TO", null, "R", new BigDecimal("15"), "S", null));

        List<SlabLine> slabs = service.slabs(context, 12L);

        assertThat(slabs)
                .containsExactly(
                        new SlabLine("A", 1, 0, 100L, new BigDecimal("10"), 25L),
                        new SlabLine("A", 2, 101, null, new BigDecimal("15"), null));
        verify(policy).requireRecordAt(context, "M-06", "VIEW", 3L);
    }

    @Test
    void SRS_5_11_aMinimumWageWithoutBothPartsHasNoMgw() {
        db.rows(
                "FROM labour_min_wage w LEFT JOIN approval_queue q",
                row(
                        "ID",
                        1L,
                        "T",
                        "URBAN",
                        "B",
                        new BigDecimal("400"),
                        "V",
                        new BigDecimal("100"),
                        "F",
                        Date.valueOf("2026-04-01"),
                        "TO",
                        null,
                        "S",
                        null),
                row(
                        "ID",
                        2L,
                        "T",
                        "RURAL",
                        "B",
                        null,
                        "V",
                        new BigDecimal("90"),
                        "F",
                        Date.valueOf("2026-04-01"),
                        "TO",
                        null,
                        "S",
                        "PENDING_APPROVAL"),
                row(
                        "ID",
                        3L,
                        "T",
                        "SEMI",
                        "B",
                        new BigDecimal("300"),
                        "V",
                        null,
                        "F",
                        Date.valueOf("2026-04-01"),
                        "TO",
                        null,
                        "S",
                        null));

        List<MinWageLine> lines = service.minWages(context);

        assertThat(lines.get(0).mgw()).isEqualByComparingTo("13000");
        assertThat(lines.get(0).approvalState()).isEqualTo("APPROVED");
        assertThat(lines.get(1).mgw()).isNull();
        assertThat(lines.get(1).approvalState()).isEqualTo("PENDING_APPROVAL");
        assertThat(lines.get(2).mgw()).isNull();
    }

    @Test
    void SRS_5_3_theFirstMgwOfAGangRoleWins() {
        db.value("SELECT COUNT(*) FROM", 1L);
        db.rows(
                "SELECT s.rate_set_id",
                row(
                        "ID",
                        7L,
                        "C",
                        "NW-2026-ASOR",
                        "L",
                        "Depot Three",
                        "F",
                        Date.valueOf("2026-04-01"),
                        "TO",
                        null,
                        "S",
                        "APPROVED",
                        "P",
                        new BigDecimal("2.50")));
        db.rows(
                "FROM labour_min_wage WHERE rate_set_id",
                row("ROLE", "MATE", "MGW", new BigDecimal("500")),
                row("ROLE", "MATE", "MGW", new BigDecimal("600")),
                row("ROLE", "LABOUR", "MGW", new BigDecimal("450")));

        var page = service.asorRates(context, new PageSpec(0, 20));

        assertThat(page.items().get(0).mgw())
                .containsEntry("MATE", new BigDecimal("500"))
                .containsEntry("LABOUR", new BigDecimal("450"))
                .hasSize(2);
    }

    @Test
    void SRS_2_2_aGangWaitingForTheCheckerIsFlagged() {
        db.value("SELECT COUNT(*) FROM", 2L);
        db.rows("SELECT g.gang_id", gang(10L, 1, 0L), gang(11L, 0, 3L));

        List<GangLine> gangs =
                service.gangs(
                                context,
                                new LabourScreenService.GangFilter(" ", null, null),
                                new PageSpec(0, 20))
                        .items();

        assertThat(gangs.get(0).pending()).isTrue();
        assertThat(gangs.get(0).endBlocked()).isFalse();
        assertThat(gangs.get(1).pending()).isFalse();
        assertThat(gangs.get(1).endBlocked()).isTrue();
        assertThat(gangs.get(1).sardar()).isEqualTo("Ramu");
    }

    private static java.util.Map<String, Object> gang(long id, int waiting, long slips) {
        return row(
                "GANG_ID",
                id,
                "GANG_CODE",
                "G" + id,
                "GANG_NAME",
                "Gang " + id,
                "PAYROLL_TYPE",
                "DEPT_LABOUR",
                "GANG_TYPE",
                "HANDLING",
                "GANG_STATUS",
                "ACTIVE",
                "LOCATION_NAME",
                "Depot Three",
                "SHED",
                null,
                "SARDAR",
                " Ramu ",
                "MANDAL",
                null,
                "LABOUR_COUNT",
                12L,
                "EFFECTIVE_FROM",
                Date.valueOf("2026-01-01"),
                "EFFECTIVE_TO",
                null,
                "SLIPS",
                slips,
                "WAITING",
                waiting);
    }

    @Test
    void SRS_G_13_theRateExportOfABlankTypeIsTheDepartmentalTable() throws Exception {
        db.rows(
                "SELECT d.designation_name, w.nwnp_loc_type",
                row(
                        "DESIGNATION_NAME",
                        "Mate",
                        "NWNP_LOC_TYPE",
                        null,
                        "MIN_GUARANTEED_WAGE",
                        new BigDecimal("500"),
                        "BASIC_WAGE",
                        new BigDecimal("400"),
                        "VDA",
                        new BigDecimal("100"),
                        "INTERIM_RELIEF",
                        null,
                        "GOI_RATE",
                        null,
                        "EFFECTIVE_FROM",
                        Date.valueOf("2026-04-01"),
                        "EFFECTIVE_TO",
                        null));
        Recorded sink = new Recorded();

        service.exportRates(context, " ", sink);

        assertThat(sink.rows).hasSize(1);
        assertThat(Arrays.asList(sink.rows.get(0)))
                .containsExactly(
                        "Mate",
                        null,
                        new BigDecimal("500"),
                        new BigDecimal("400"),
                        new BigDecimal("100"),
                        null,
                        null,
                        Date.valueOf("2026-04-01"),
                        null);
        assertThatThrownBy(() -> service.exportRates(context, "STAFF", new Recorded()))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(LabourErrorCode.ENTRY));
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_3_12_theNormTypeFilterIsOneOfTheDrawnThreeOrNone() {
        NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
        LabourScreenService mocked = service(mock(JdbcTemplate.class), named);
        PageSpec spec = new PageSpec(0, 20);

        mocked.norms(context, new LabourScreenService.NormFilter(null, "HEIGHT", null), spec);
        mocked.norms(context, new LabourScreenService.NormFilter(null, "LEAD", null), spec);
        mocked.norms(context, new LabourScreenService.NormFilter(null, " ", null), spec);

        ArgumentCaptor<MapSqlParameterSource> sent =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named, org.mockito.Mockito.times(3))
                .query(anyString(), sent.capture(), any(RowMapper.class));
        List<MapSqlParameterSource> p = sent.getAllValues();
        assertThat(List.of(p.get(0).getValue("height"), p.get(0).getValue("lead")))
                .containsExactly("Y", "N");
        assertThat(List.of(p.get(1).getValue("height"), p.get(1).getValue("lead")))
                .containsExactly("N", "Y");
        assertThat(
                        List.of(
                                p.get(2).getValue("handling"),
                                p.get(2).getValue("height"),
                                p.get(2).getValue("lead")))
                .containsExactly("N", "N", "N");
        assertThatThrownBy(
                        () ->
                                mocked.norms(
                                        context,
                                        new LabourScreenService.NormFilter(null, "BOGUS", null),
                                        spec))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("NORM_TYPE"));
        verify(named, org.mockito.Mockito.times(3))
                .queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class));
    }

    @Test
    void SRS_2_47_anAttachedLabourWithoutALastNameIsShownByTheFirstName() {
        db.value("SELECT COUNT(*) FROM labour_temp_attachment", 2L);
        db.rows(
                "SELECT t.temp_attachment_id",
                row(
                        "ID",
                        1L,
                        "R",
                        "TA-1",
                        "N",
                        "DL0100",
                        "F",
                        "Asha",
                        "L",
                        "Devi",
                        "C",
                        "HANDLING",
                        "G",
                        "Gang One",
                        "FR",
                        Date.valueOf("2026-11-01"),
                        "TO",
                        null,
                        "S",
                        "ACTIVE"),
                row(
                        "ID",
                        2L,
                        "R",
                        "TA-2",
                        "N",
                        "DL0102",
                        "F",
                        "Ravi",
                        "L",
                        null,
                        "C",
                        "HANDLING",
                        "G",
                        "Gang Two",
                        "FR",
                        Date.valueOf("2026-11-02"),
                        "TO",
                        Date.valueOf("2026-11-30"),
                        "S",
                        "ACTIVE"));

        var page = service.attachments(context, "DEPT_LABOUR", new PageSpec(0, 20));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items())
                .extracting(LabourScreenService.AttachmentLine::labour)
                .containsExactly("Asha Devi", "Ravi");
        assertThat(page.items().get(1).to()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(page.items().get(0).to()).isNull();
    }

    private static final class Recorded implements ExportSink {
        private final List<Object[]> rows = new ArrayList<>();

        @Override
        public void header(String... titles) {
            // The titles are not under test here.
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
}
