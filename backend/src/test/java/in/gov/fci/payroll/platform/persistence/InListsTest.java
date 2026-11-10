package in.gov.fci.payroll.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.ScopeType;
import java.sql.Types;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * SRS-G-2 office scope and Oracle review F1 (ORA-01795): an office scope of more than 1,000
 * locations (a zone, the whole Corporation for a GLOBAL group away from Headquarters) or any other
 * named list over 1,000 values never reaches the database as one unbounded IN list. The rewritten
 * statements run on an in-memory database in Oracle mode with 2,500 offices.
 */
class InListsTest {

    private static final int OFFICES = 2_500;
    private static JdbcTemplate jdbc;
    private static ScopedNamedParameterJdbcTemplate named;

    @BeforeAll
    static void offices() {
        org.h2.jdbcx.JdbcDataSource h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:inlists;MODE=Oracle;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(h2);
        named = new ScopedNamedParameterJdbcTemplate(jdbc);
        jdbc.execute("DROP TABLE IF EXISTS employee");
        jdbc.execute("DROP TABLE IF EXISTS org_location");
        jdbc.execute("CREATE TABLE org_location (location_id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE employee (employee_id BIGINT PRIMARY KEY, location_id BIGINT)");
        jdbc.batchUpdate(
                "INSERT INTO org_location VALUES (?)",
                LongStream.rangeClosed(1, OFFICES).mapToObj(i -> new Object[] {i}).toList());
        // One member per office, and one without an office.
        jdbc.batchUpdate(
                "INSERT INTO employee VALUES (?, ?)",
                LongStream.rangeClosed(1, OFFICES).mapToObj(i -> new Object[] {i, i}).toList());
        jdbc.update("INSERT INTO employee VALUES (?, NULL)", OFFICES + 1L);
    }

    private static Set<Long> ids(long from, long to) {
        Set<Long> ids = new LinkedHashSet<>();
        LongStream.rangeClosed(from, to).forEach(ids::add);
        return ids;
    }

    /** The largest number of binds in any one IN list of the statement. */
    private static int largestList(String sql) {
        Matcher m = Pattern.compile("IN \\(([^()]*)\\)").matcher(sql);
        int max = 0;
        while (m.find()) {
            max = Math.max(max, m.group(1).split(",").length);
        }
        return max;
    }

    private static AccessContext session(long office, Set<Long> scope, ScopeType type) {
        ActiveGroup group =
                new ActiveGroup(
                        1L,
                        "G",
                        "Group",
                        2L,
                        "PAYROLL_MAKER",
                        "Maker",
                        type,
                        false,
                        scope,
                        Set.of(),
                        AccessMode.FULL);
        return new AccessContext(
                1L,
                "u@x",
                "U",
                "s",
                "P",
                office,
                null,
                List.of(group),
                Map.of(),
                Set.of(),
                Set.of(),
                scope);
    }

    @Test
    void SRS_G_2_aScopeOfMoreThanAThousandOfficesIsSplitIntoListsOracleTakes() {
        String guard =
                "SELECT COUNT(*) FROM employee e WHERE (:all = 'Y' OR e.location_id IN"
                        + " (:locations))";
        // A zone of 1,500 offices: the session at the zonal office, not Headquarters.
        MapSqlParameterSource zone =
                OfficeScope.of(session(1L, ids(1, 1_500), ScopeType.ZONE), false);
        InLists.Expanded statement = InLists.expand(OfficeScope.indexable(guard, zone), zone);
        assertThat(statement.sql())
                .isEqualTo(
                        "SELECT COUNT(*) FROM employee e WHERE (e.location_id IN (:locations__0)"
                                + " OR e.location_id IN (:locations__1))");
        assertThat((List<?>) statement.params().getValue("locations__0")).hasSize(1_000);
        assertThat((List<?>) statement.params().getValue("locations__1"))
                .as("the last chunk padded to the full size with its own last value")
                .hasSize(1_000)
                .allMatch(ids(1_001, 1_500)::contains);
        // Through the application's template the statement runs, with the same rows.
        assertThat(named.queryForObject(guard, zone, Long.class)).isEqualTo(1_500L);
    }

    @Test
    void SRS_G_2_aGlobalGroupAwayFromHeadquartersReadsEveryOfficeWithoutAList() {
        Set<Long> everything = ids(1, OFFICES);
        AccessContext global = session(7L, everything, ScopeType.GLOBAL);
        assertThat(OfficeScope.everyOffice(global)).isTrue();
        MapSqlParameterSource p = OfficeScope.of(global, false);
        assertThat(p.getValue(OfficeScope.EVERY_OFFICE)).isEqualTo("Y");
        String guard =
                "SELECT COUNT(*) FROM employee e WHERE (:all = 'Y' OR e.location_id IN"
                        + " (:locations))";
        assertThat(OfficeScope.indexable(guard, p))
                .isEqualTo(
                        "SELECT COUNT(*) FROM employee e WHERE e.location_id IN (SELECT"
                                + " l.location_id FROM org_location l)");
        // Every office's member, never the one without an office (as the list of every office).
        assertThat(named.queryForObject(guard, p, Long.class)).isEqualTo((long) OFFICES);
        // Headquarters stays "every row".
        assertThat(named.queryForObject(guard, OfficeScope.of(global, true), Long.class))
                .isEqualTo(OFFICES + 1L);
        // A ZONE group is not every office.
        assertThat(OfficeScope.everyOffice(session(7L, ids(1, 3), ScopeType.ZONE))).isFalse();
    }

    @Test
    void SRS_G_2_anyNamedListOverAThousandIsSplitAndNotInJoinsTheChunksWithAnd() {
        Map<String, Object> members = Map.of("ids", List.copyOf(ids(1, 2_001)));
        assertThat(
                        named.queryForObject(
                                "SELECT COUNT(*) FROM employee WHERE employee_id IN (:ids)",
                                members,
                                Long.class))
                .isEqualTo(2_001L);
        assertThat(
                        named.queryForObject(
                                "SELECT COUNT(*) FROM employee WHERE employee_id NOT IN (:ids)",
                                members,
                                Long.class))
                .as("2,500 offices' members and the one without an office, less 2,001")
                .isEqualTo(500L);
        InLists.Expanded not =
                InLists.expand(
                        "SELECT 1 FROM employee WHERE employee_id NOT IN (:ids) AND x IN (:ids)",
                        new MapSqlParameterSource(members));
        assertThat(not.sql())
                .contains(
                        "(employee_id NOT IN (:ids__0) AND employee_id NOT IN (:ids__1) AND"
                                + " employee_id NOT IN (:ids__2))")
                .contains("(x IN (:ids__0) OR x IN (:ids__1) OR x IN (:ids__2))");
        assertThat(largestList(not.sql())).isLessThanOrEqualTo(InLists.MAX);
    }

    @Test
    void SRS_G_2_aListOfAThousandOrFewerIsLeftAsWritten() {
        String sql = "SELECT 1 FROM employee WHERE employee_id IN (:ids) AND 'HH24:MI' = :text";
        MapSqlParameterSource small =
                new MapSqlParameterSource("ids", List.copyOf(ids(1, 1_000))).addValue("text", "x");
        InLists.Expanded same = InLists.expand(sql, small);
        assertThat(same.sql()).isSameAs(sql);
        assertThat(same.params()).isSameAs(small);
        assertThat(InLists.expand(sql, (SqlParameterSource) null).params()).isNull();
        assertThat(InLists.expand("SELECT 1 FROM dual", small).sql())
                .isEqualTo("SELECT 1 FROM dual");
        InLists.ExpandedMap map = InLists.expand(sql, Map.of("ids", List.of(1L), "text", "x"));
        assertThat(map.sql()).isSameAs(sql);
        assertThat(map.params()).containsEntry("text", "x");
    }

    @Test
    void SRS_G_2_anOversizedListThatCannotBeSplitIsRefusedBeforeTheDatabase() {
        MapSqlParameterSource big = new MapSqlParameterSource("ids", List.copyOf(ids(1, 1_001)));
        // A list in another form (a function of the column, ANY) cannot be split.
        assertThatThrownBy(
                        () ->
                                InLists.expand(
                                        "SELECT 1 FROM employee WHERE ABS(employee_id) IN (:ids)",
                                        big))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining(":ids has 1001 values");
        // Used once splittable and once not: still refused.
        assertThatThrownBy(
                        () ->
                                InLists.expand(
                                        "SELECT 1 FROM employee WHERE employee_id IN (:ids) OR"
                                                + " ABS(employee_id) IN (:ids)",
                                        big))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
    }

    @Test
    void SRS_G_2_theChunksCarryTheListsTypeAndTheMapFormIsSplitAlike() {
        MapSqlParameterSource typed =
                new MapSqlParameterSource()
                        .addValue("ids", List.copyOf(ids(1, 1_200)), Types.BIGINT, "NUMBER")
                        .addValue("unit", 3L);
        SqlParameterSource p =
                InLists.expand("SELECT 1 FROM employee WHERE employee_id IN (:ids)", typed)
                        .params();
        assertThat(p.hasValue("ids__1")).isTrue();
        assertThat(p.hasValue("unit")).isTrue();
        assertThat(p.hasValue("nothing")).isFalse();
        assertThat(p.getValue("unit")).isEqualTo(3L);
        assertThat(p.getSqlType("ids__1")).isEqualTo(Types.BIGINT);
        assertThat(p.getTypeName("ids__0")).isEqualTo("NUMBER");
        assertThat(p.getSqlType("unit")).isEqualTo(typed.getSqlType("unit"));
        assertThat(p.getTypeName("unit")).isNull();
        assertThat(p.getParameterNames()).contains("ids__0", "ids__1", "ids", "unit");
        SqlParameterSource unnamed =
                new SqlParameterSource() {
                    @Override
                    public boolean hasValue(String paramName) {
                        return "ids".equals(paramName);
                    }

                    @Override
                    public Object getValue(String paramName) {
                        return List.copyOf(ids(1, 1_001));
                    }
                };
        assertThat(
                        InLists.expand("SELECT 1 FROM t WHERE c IN (:ids)", unnamed)
                                .params()
                                .getParameterNames())
                .containsExactly("ids__0", "ids__1");

        InLists.ExpandedMap map =
                InLists.expand(
                        "SELECT 1 FROM t WHERE c IN (:locations) AND y = :yymm",
                        Map.of("locations", List.copyOf(ids(1, 1_001)), "yymm", "202610"));
        assertThat(map.sql()).contains("(c IN (:locations__0) OR c IN (:locations__1))");
        assertThat(map.params()).containsKeys("locations__0", "locations__1", "yymm");
        assertThat(largestList(map.sql())).isEqualTo(1);
    }
}
