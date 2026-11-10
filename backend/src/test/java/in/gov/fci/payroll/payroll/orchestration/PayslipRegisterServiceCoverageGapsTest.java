package in.gov.fci.payroll.payroll.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * O-01 Payslip register reads (SRS l.3381-3392, BR-ESEVA-004; OD-123): the Period dropdown comes
 * from the cycles; the tiles never count a payslip to generate below zero; a month is locked only
 * when every cycle of the scope is locked and at least one exists; each register row shows the
 * first status that applies, the name without a blank last name, and whether it reached eSeva.
 */
class PayslipRegisterServiceCoverageGapsTest {

    private static final Instant PUBLISHED = Instant.parse("2026-11-01T04:30:00Z");

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final Authorization policy = mock(Authorization.class);
    private final AccessContext maker =
            new AccessContext(
                    1L,
                    "maker@x",
                    "M",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    private PayslipRegisterService service(NamedParameterJdbcTemplate named) {
        return new PayslipRegisterService(
                named,
                policy,
                mock(AuditService.class),
                Clock.fixed(Instant.parse("2026-11-07T06:00:00Z"), ZoneOffset.UTC));
    }

    private final PayslipRegisterService service = service(db.named);

    @Test
    void SRS_17_1_thePeriodDropdownComesFromTheCyclesNewestFirst() {
        db.rows(
                "SELECT g.yymm, g.all_locked",
                row("YYMM", "202610", "ALL_LOCKED", 1, "RESULTS", 1),
                row("YYMM", "202609", "ALL_LOCKED", 0, "RESULTS", 0));

        assertThat(service.periods(maker))
                .containsExactly(
                        new PayslipRegisterService.Period("202610", true, true),
                        new PayslipRegisterService.Period("202609", false, false));
    }

    @Test
    void SRS_17_1_theTilesCountWhatIsLeftToGenerateAndTheLastPublication() {
        db.value("SELECT COUNT(*) FROM payroll_result r JOIN employee e", 10L);
        db.value("s.published_to_ess = 'Y' AND s.suppressed_flag = 'N'", 6L);
        db.value("s.suppressed_flag = 'Y' AND", 1L);
        db.value("c.cycle_status <> 'LOCKED'", 0L);
        db.value("c.cycle_status = 'LOCKED'", 2L);
        db.rows(
                "SELECT s.payroll_yymm, MAX(s.published_at), COUNT(*)",
                row("YYMM", "202610", "AT", Timestamp.from(PUBLISHED), "N", 6L));

        PayslipRegisterService.Summary s =
                service.summary(maker, "202610", PayslipRegisterService.Level.STAFF);

        assertThat(s)
                .isEqualTo(
                        new PayslipRegisterService.Summary(
                                "202610", true, 3, 6, 1, "202610", PUBLISHED, 6L));
    }

    @Test
    void SRS_17_1_nothingPublishedYetAndMoreDoneThanResultsNeverGoesBelowZero() {
        db.value("SELECT COUNT(*) FROM payroll_result r JOIN employee e", 5L);
        db.value("s.published_to_ess = 'Y' AND s.suppressed_flag = 'N'", 6L);
        db.value("c.cycle_status <> 'LOCKED'", 1L);
        db.value("c.cycle_status = 'LOCKED'", 2L);

        PayslipRegisterService.Summary s = service.summary(maker, "202610", null);

        assertThat(s.toGenerate()).isZero();
        assertThat(s.locked()).as("one cycle of the scope is still open").isFalse();
        assertThat(s.lastPublished()).isNull();
        assertThat(s.lastPublishedAt()).isNull();
        assertThat(s.lastPublishedCount()).isZero();
    }

    @Test
    void SRS_17_1_noCycleAtAllIsNotALockedMonth() {
        db.value("c.cycle_status <> 'LOCKED'", 0L);
        db.value("c.cycle_status = 'LOCKED'", 0L);

        assertThat(
                        service.summary(maker, "202610", PayslipRegisterService.Level.LABOUR_DEPOT)
                                .locked())
                .isFalse();
    }

    @Test
    void SRS_17_1_eachRowShowsTheFirstStatusThatAppliesAndAPlainName() {
        db.value("SELECT COUNT(*) FROM payroll_result r JOIN employee e", 4L);
        db.rows(
                "SELECT e.emp_num, e.first_name",
                line("1001", "Asha", "Devi", "Y", "N", 0L),
                line("1002", "Ravi", " ", null, null, 0L),
                line("1003", "Uma", null, "N", "Y", 0L),
                line("1004", "Hari", "Om", "Y", "N", 1L));

        Paged<PayslipRegisterService.Row> page =
                service.register(
                        maker,
                        "202610",
                        PayslipRegisterService.Level.STAFF,
                        null,
                        new PageSpec(0, 20));

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.items())
                .extracting(
                        PayslipRegisterService.Row::name,
                        PayslipRegisterService.Row::status,
                        PayslipRegisterService.Row::published)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Asha Devi", "READY", true),
                        org.assertj.core.groups.Tuple.tuple("Ravi", "AWAITING_GENERATION", false),
                        org.assertj.core.groups.Tuple.tuple("Uma", "SUPPRESSED", false),
                        org.assertj.core.groups.Tuple.tuple("Hari Om", "HELD", true));
        PayslipRegisterService.Row first = page.items().get(0);
        assertThat(first.eis()).isEqualTo("1001");
        assertThat(first.payrollType()).isEqualTo("STAFF");
        assertThat(first.payPattern()).isEqualTo("IDA");
        assertThat(first.net()).isEqualByComparingTo("50000");
    }

    private static Map<String, Object> line(
            String eis, String first, String last, String published, String suppressed, long held) {
        return row(
                "EMP_NUM", eis,
                "FIRST_NAME", first,
                "LAST_NAME", last,
                "PAYROLL_TYPE", "STAFF",
                "PAY_PATTERN", "IDA",
                "NET_PAY", new BigDecimal("50000"),
                "IS_LOCKED", "Y",
                "PUBLISHED", published,
                "SUPPRESSED", suppressed,
                "HELD", held);
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_17_1_theSearchIsTheTrimmedUpperCaseTextAndABlankOneSearchesNothing() {
        NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
        PayslipRegisterService mocked = service(named);
        PageSpec spec = new PageSpec(0, 20);

        mocked.register(maker, "202610", null, " asha ", spec);
        mocked.register(maker, "202610", null, "   ", spec);
        mocked.register(maker, "202610", null, null, spec);

        ArgumentCaptor<MapSqlParameterSource> sent =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named, atLeastOnce()).query(anyString(), sent.capture(), any(RowMapper.class));
        List<MapSqlParameterSource> calls = sent.getAllValues();
        assertThat(calls).hasSize(3);
        assertThat(calls.get(0).getValue("text")).isEqualTo("ASHA");
        assertThat(calls.get(0).getValue("like")).isEqualTo("%ASHA%");
        assertThat(calls.get(0).getValue("level")).isEqualTo("STAFF");
        assertThat(calls.get(1).getValue("text")).isNull();
        assertThat(calls.get(1).getValue("like")).isEqualTo("%%");
        assertThat(calls.get(2).getValue("text")).isNull();
        verify(named, atLeastOnce())
                .queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class));
    }
}
