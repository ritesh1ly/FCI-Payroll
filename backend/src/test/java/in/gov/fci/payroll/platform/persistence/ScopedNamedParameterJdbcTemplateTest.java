package in.gov.fci.payroll.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.SqlProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * P1: every statement of the application's template reaches the database with the guard resolved.
 */
class ScopedNamedParameterJdbcTemplateTest {

    @Test
    void SRS_G_2_aRegisterQueryIsPreparedWithTheOfficeListInPlaceOfTheGuard() {
        JdbcOperations operations = mock(JdbcOperations.class);
        ScopedNamedParameterJdbcTemplate template =
                new ScopedNamedParameterJdbcTemplate(operations);
        template.query(
                "SELECT 1 FROM employee e WHERE (:all = 'Y' OR e.location_id IN (:locations))",
                new MapSqlParameterSource("all", "N").addValue("locations", java.util.List.of(7L)),
                (ResultSetExtractor<Object>) rs -> null);
        ArgumentCaptor<PreparedStatementCreator> creator =
                ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(operations)
                .query(
                        creator.capture(),
                        org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any());
        assertThat(((SqlProvider) creator.getValue()).getSql())
                .isEqualTo("SELECT 1 FROM employee e WHERE e.location_id IN (?)");
    }

    /**
     * Oracle review F1 (ORA-01795): a zone scope of 2,500 offices reaches the database as IN lists
     * of at most 1,000 binds each, never as one list of 2,500 (Oracle 19c refuses more than 1,000;
     * Spring expands a named list into one bind per value and does not split it).
     */
    @Test
    void SRS_G_2_anOfficeScopeOverAThousandLocationsIsNeverOneUnboundedInList() {
        JdbcOperations operations = mock(JdbcOperations.class);
        ScopedNamedParameterJdbcTemplate template =
                new ScopedNamedParameterJdbcTemplate(operations);
        java.util.List<Long> zone =
                java.util.stream.LongStream.rangeClosed(1, 2_500).boxed().toList();
        template.query(
                "SELECT 1 FROM employee e WHERE (:all = 'Y' OR e.location_id IN (:locations))",
                new MapSqlParameterSource("all", "N").addValue("locations", zone),
                (ResultSetExtractor<Object>) rs -> null);
        ArgumentCaptor<PreparedStatementCreator> creator =
                ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(operations)
                .query(
                        creator.capture(),
                        org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any());
        String sql = ((SqlProvider) creator.getValue()).getSql();
        java.util.regex.Matcher list =
                java.util.regex.Pattern.compile("IN \\(([?, ]*)\\)").matcher(sql);
        java.util.List<Integer> sizes = new java.util.ArrayList<>();
        while (list.find()) {
            sizes.add(list.group(1).split(",").length);
        }
        assertThat(sizes).as("three lists, none over 1,000").containsExactly(1_000, 1_000, 1_000);
        assertThat(sql).startsWith("SELECT 1 FROM employee e WHERE (e.location_id IN (?");
    }
}
