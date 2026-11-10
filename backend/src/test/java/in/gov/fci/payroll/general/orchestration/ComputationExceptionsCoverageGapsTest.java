package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.general.business.model.ExceptionEntry;
import in.gov.fci.payroll.general.orchestration.ComputationExceptions.Condition;
import in.gov.fci.payroll.general.orchestration.ComputationExceptions.Filter;
import in.gov.fci.payroll.general.orchestration.ComputationExceptions.Review;
import in.gov.fci.payroll.general.orchestration.ComputationExceptions.Tiles;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * C-08 Computation Exceptions (SRS-G-9, SRS-G-13; SRS l.1641-1678) on an in-memory copy of the DDL
 * columns read (payroll_exception, payroll_cycle_instance, computation_run, employee,
 * labour_profile): one row per condition, oldest raised first; a condition names its payroll type
 * only when every item has the same one; it counts members, or items when none is a member's; it
 * blocks the lock only for a blocking item on gate G2; its owner is named only when one role fixes
 * every item; the tiles split every open item once; a settled item, another month's item and
 * another office's member are never shown.
 */
class ComputationExceptionsCoverageGapsTest {

    private static final PageSpec ALL = new PageSpec(0, 20);
    private static final Filter NOVEMBER = new Filter("202611", null, null);

    private final Authorization policy = mock(Authorization.class);
    private final ExceptionService worklist = mock(ExceptionService.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final AccessContext context = mock(AccessContext.class);
    private JdbcTemplate jdbc;
    private ComputationExceptions service;
    private long nextId = 1;

    @BeforeEach
    void seed() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:c08" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(h2);
        jdbc.execute(
                "CREATE TABLE payroll_cycle_instance (cycle_instance_id NUMBER(19) PRIMARY KEY,"
                        + " payroll_yymm VARCHAR2(6), org_unit_id NUMBER(19))");
        jdbc.execute(
                "CREATE TABLE computation_run (cycle_instance_id NUMBER(19), run_type"
                        + " VARCHAR2(20))");
        jdbc.execute(
                "CREATE TABLE employee (employee_id NUMBER(19) PRIMARY KEY, location_id"
                        + " NUMBER(19), emp_lbr_flag CHAR(1))");
        jdbc.execute(
                "CREATE TABLE labour_profile (employee_id NUMBER(19), payroll_type VARCHAR2(20))");
        jdbc.execute(
                "CREATE TABLE payroll_exception (exception_id NUMBER(19) PRIMARY KEY,"
                        + " cycle_instance_id NUMBER(19), employee_id NUMBER(19), exception_status"
                        + " VARCHAR2(20), module_code VARCHAR2(10), exception_code VARCHAR2(20),"
                        + " severity VARCHAR2(20), field_name VARCHAR2(50), field_source"
                        + " VARCHAR2(10), correction_screen VARCHAR2(10), blocks_gate VARCHAR2(5),"
                        + " owner_role_code VARCHAR2(30), resolvable_in_payroll CHAR(1), detail"
                        + " VARCHAR2(200), raised_at TIMESTAMP)");
        cycle(1, "202610", 5, "ACTUAL");
        cycle(2, "202611", 5, "TRIAL");
        cycle(3, "202609", 5, "ACTUAL");
        cycle(4, "202610", 6, "ACTUAL");
        cycle(5, "202612", 5, "ACTUAL");
        jdbc.update("INSERT INTO employee VALUES (1, 5, 'N')");
        jdbc.update("INSERT INTO employee VALUES (2, 5, 'Y')");
        jdbc.update("INSERT INTO employee VALUES (3, 5, 'Y')");
        jdbc.update("INSERT INTO employee VALUES (4, 6, 'N')");
        jdbc.update("INSERT INTO labour_profile VALUES (2, 'DEPT_LABOUR')");
        jdbc.update("INSERT INTO labour_profile VALUES (3, 'DPS_LABOUR')");
        raise(
                1,
                1L,
                "OPEN",
                "C-02",
                "E01",
                "BLOCKING",
                "basic_pay",
                "HRMS",
                "I-21",
                "G2",
                "PAY_MAKER",
                "N",
                "Basic missing",
                "10:00");
        raise(
                1,
                1L,
                "IN_PROGRESS",
                "C-02",
                "E01",
                "BLOCKING",
                "basic_pay",
                "HRMS",
                "I-21",
                "G2",
                "PAY_MAKER",
                "N",
                "Basic missing",
                "10:05");
        raise(
                1,
                2L,
                "OPEN",
                "C-02",
                "E02",
                "WARNING",
                "attendance",
                "ENT",
                "I-01",
                "G2",
                "LDH",
                "Y",
                "Attendance gap",
                "11:00");
        raise(
                1,
                3L,
                "ROUTED_UPSTREAM",
                "C-02",
                "E02",
                "WARNING",
                "attendance",
                "ENT",
                "I-01",
                "G2",
                "DPS_MAKER",
                "Y",
                "Attendance gap",
                "11:01");
        raise(
                1,
                null,
                "OPEN",
                "C-05",
                "E03",
                "INFORMATIONAL",
                null,
                null,
                "C-05",
                null,
                null,
                "N",
                "Cycle note",
                "09:00");
        raise(
                1,
                1L,
                "RESOLVED",
                "C-02",
                "E01",
                "BLOCKING",
                "basic_pay",
                "HRMS",
                "I-21",
                "G2",
                "PAY_MAKER",
                "N",
                "Basic missing",
                "08:00");
        raise(
                1,
                4L,
                "OPEN",
                "C-02",
                "E04",
                "BLOCKING",
                "basic_pay",
                "HRMS",
                "I-21",
                "G2",
                "PAY_MAKER",
                "N",
                "Other office",
                "08:00");
        raise(
                1,
                2L,
                "OPEN",
                "C-03",
                "E05",
                "BLOCKING",
                "net_pay",
                "SYS",
                "C-03",
                "G6",
                "PAY_MAKER",
                "Y",
                "Net pay 50%_floor",
                "12:00");
        raise(
                1,
                2L,
                "OPEN",
                "C-04",
                "E06",
                "WARNING",
                "arrear",
                "ENT",
                "C-04",
                null,
                "PAY_MAKER",
                "Y",
                "Arrear",
                "13:00");
        raise(
                1,
                null,
                "OPEN",
                "C-04",
                "E06",
                "WARNING",
                "arrear",
                "ENT",
                "C-04",
                null,
                "PAY_MAKER",
                "Y",
                "Arrear",
                "13:01");
        raise(
                3,
                1L,
                "OPEN",
                "C-02",
                "E09",
                "BLOCKING",
                "basic_pay",
                "HRMS",
                "I-21",
                "G2",
                "PAY_MAKER",
                "N",
                "September",
                "08:00");
        raise(
                4,
                null,
                "OPEN",
                "C-05",
                "E10",
                "BLOCKING",
                null,
                null,
                "C-05",
                "G2",
                null,
                "N",
                "Another unit",
                "08:00");
        when(context.dataScope()).thenReturn(Set.of(5L));
        service =
                new ComputationExceptions(
                        new NamedParameterJdbcTemplate(jdbc), policy, worklist, org);
    }

    @AfterEach
    void close() {
        jdbc.execute("SHUTDOWN");
    }

    private void cycle(long id, String yymm, long unit, String run) {
        jdbc.update("INSERT INTO payroll_cycle_instance VALUES (?, ?, ?)", id, yymm, unit);
        jdbc.update("INSERT INTO computation_run VALUES (?, ?)", id, run);
    }

    private void raise(
            long cycle,
            Long employee,
            String status,
            String module,
            String code,
            String severity,
            String field,
            String source,
            String screen,
            String gate,
            String owner,
            String resolvable,
            String detail,
            String at) {
        jdbc.update(
                "INSERT INTO payroll_exception VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?)",
                nextId++,
                cycle,
                employee,
                status,
                module,
                code,
                severity,
                field,
                source,
                screen,
                gate,
                owner,
                resolvable,
                detail,
                Timestamp.valueOf("2026-10-31 " + at + ":00"));
    }

    @Test
    void SRS_G_9_oneRowPerConditionOldestFirstWithTypeCountOwnerAndLockFlag() {
        Review review = service.review(context, NOVEMBER, ALL);

        assertThat(review.period()).isEqualTo("2026-10");
        assertThat(review.total()).isEqualTo(5);
        assertThat(review.items())
                .containsExactly(
                        new Condition(
                                "C-05",
                                "E03",
                                "ALL",
                                1,
                                "INFORMATIONAL",
                                null,
                                null,
                                "C-05",
                                false,
                                null,
                                false,
                                "Cycle note"),
                        new Condition(
                                "C-02",
                                "E01",
                                "STAFF",
                                1,
                                "BLOCKING",
                                "basic_pay",
                                "HRMS",
                                "I-21",
                                true,
                                "PAY_MAKER",
                                false,
                                "Basic missing"),
                        new Condition(
                                "C-02",
                                "E02",
                                "ALL",
                                2,
                                "WARNING",
                                "attendance",
                                "ENT",
                                "I-01",
                                false,
                                null,
                                true,
                                "Attendance gap"),
                        new Condition(
                                "C-03",
                                "E05",
                                "DEPT_LABOUR",
                                1,
                                "BLOCKING",
                                "net_pay",
                                "SYS",
                                "C-03",
                                false,
                                "PAY_MAKER",
                                true,
                                "Net pay 50%_floor"),
                        new Condition(
                                "C-04",
                                "E06",
                                "ALL",
                                1,
                                "WARNING",
                                "arrear",
                                "ENT",
                                "C-04",
                                false,
                                "PAY_MAKER",
                                true,
                                "Arrear"));
        // Every open item in one of the last three tiles: 5 fixable + 2 HRMS + 1 system.
        assertThat(review.tiles()).isEqualTo(new Tiles(8, 2, 5, 2, 1));
        assertThat(review.page()).isZero();
        assertThat(review.size()).isEqualTo(20);
    }

    @Test
    void SRS_G_9_theSeverityFilterAndTheSearchNarrowRowsAndTilesAlike() {
        Review warnings =
                service.review(
                        context, new Filter("202611", ExceptionEntry.Severity.WARNING, null), ALL);
        assertThat(warnings.items())
                .extracting(Condition::exceptionCode)
                .containsExactly("E02", "E06");
        assertThat(warnings.tiles()).isEqualTo(new Tiles(4, 0, 4, 0, 0));

        Review literal = service.review(context, new Filter("202611", null, "%_FLOOR"), ALL);
        assertThat(literal.items()).extracting(Condition::exceptionCode).containsExactly("E05");
        assertThat(literal.total()).isEqualTo(1);

        Review byCode = service.review(context, new Filter("202611", null, "e01"), ALL);
        assertThat(byCode.items()).extracting(Condition::exceptionCode).containsExactly("E01");
        assertThat(byCode.tiles().hrms()).isEqualTo(2);

        Review none = service.review(context, new Filter("202611", null, "a_b"), ALL);
        assertThat(none.items()).isEmpty();
        assertThat(none.tiles()).isEqualTo(new Tiles(0, 0, 0, 0, 0));
    }

    @Test
    void SRS_G_9_theReviewPagesInRaisedOrder() {
        Review second = service.review(context, NOVEMBER, new PageSpec(1, 2));

        assertThat(second.total()).isEqualTo(5);
        assertThat(second.items())
                .extracting(Condition::exceptionCode)
                .containsExactly("E02", "E05");
        assertThat(second.page()).isEqualTo(1);
    }

    @Test
    void SRS_G_9_aSessionMonthBeforeAnyRunHasNoReview() {
        // A session without an office (eSeva) has no unit of its own: the scope decides.
        when(context.locationId()).thenReturn(null);
        assertThat(service.review(context, NOVEMBER, ALL).period()).isEqualTo("2026-10");
        verifyNoInteractions(org);

        Review review = service.review(context, new Filter("202608", null, null), ALL);

        assertThat(review.period()).isNull();
        assertThat(review.total()).isZero();
    }

    @Test
    void SRS_G_9_theSessionsUnitRunWinsAndAUnitWithoutARunFallsBackToTheScope() {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(5L);
        when(context.locationId()).thenReturn(9L);
        when(context.dataScope()).thenReturn(Set.of(5L, 6L));
        when(org.cycleUnitOf(9L)).thenReturn(Optional.of(unit));
        // The unit's own December run: the session's month is December.
        assertThat(service.review(context, new Filter("202612", null, null), ALL).period())
                .isEqualTo("2026-12");

        OrgLocation idle = mock(OrgLocation.class);
        when(idle.getId()).thenReturn(7L);
        when(org.cycleUnitOf(9L)).thenReturn(Optional.of(idle));
        assertThat(service.review(context, NOVEMBER, ALL).period()).isEqualTo("2026-10");

        when(org.cycleUnitOf(9L)).thenReturn(Optional.empty());
        Review scoped = service.review(context, NOVEMBER, ALL);
        assertThat(scoped.period()).isEqualTo("2026-10");
        // Office 6 is in this scope: its member's item and its unit's cycle item are shown.
        assertThat(scoped.items())
                .extracting(Condition::exceptionCode)
                .contains("E04", "E10")
                .doesNotContain("E09");
    }

    @Test
    void SRS_G_9_theItemsOfAConditionAreTheWorklistsForTheRunsMonth() {
        @SuppressWarnings("unchecked")
        Paged<ExceptionService.ExceptionView> page = mock(Paged.class);
        ExceptionService.Filter expected =
                new ExceptionService.Filter("C-02", null, null, null, "202610", null, "E01");
        when(worklist.items(Set.of(5L), expected, ALL)).thenReturn(page);

        assertThat(service.items(context, "202611", "C-02", "E01", ALL)).isSameAs(page);
        verify(worklist).items(Set.of(5L), expected, ALL);
    }

    @Test
    void SRS_G_13_theExportWritesEveryConditionWithTheDrawnColumns() throws Exception {
        assertThat(service.exportSize(context, NOVEMBER)).isEqualTo(5);
        Recorded sink = new Recorded();

        service.export(context, NOVEMBER, sink);

        assertThat(sink.header)
                .containsExactly(
                        "Exception",
                        "Payroll type",
                        "Count",
                        "Severity",
                        "Field involved",
                        "Field source",
                        "Fix where",
                        "Blocks lock?");
        assertThat(sink.rows).hasSize(5);
        assertThat(Arrays.asList(sink.rows.get(1)))
                .containsExactly(
                        "E01: Basic missing",
                        "STAFF",
                        1L,
                        "BLOCKING",
                        "basic_pay",
                        "HRMS",
                        "I-21",
                        "Y");
        assertThat(sink.rows.get(0)[7]).isEqualTo("N");
    }

    @Test
    void SRS_G_13_aCountTheDatabaseDoesNotReturnIsNoCondition() {
        NamedParameterJdbcTemplate mocked = mock(NamedParameterJdbcTemplate.class);
        when(mocked.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn("202610");
        when(mocked.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(null);

        assertThat(
                        new ComputationExceptions(mocked, policy, worklist, org)
                                .exportSize(context, NOVEMBER))
                .isZero();
        verifyNoInteractions(worklist);
    }

    private static final class Recorded implements ExportSink {
        private final List<String> header = new ArrayList<>();
        private final List<Object[]> rows = new ArrayList<>();

        @Override
        public void header(String... titles) {
            header.addAll(List.of(titles));
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
