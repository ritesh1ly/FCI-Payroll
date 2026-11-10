package in.gov.fci.payroll.payroll.batch.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * The paging reader against a real (embedded H2) table: filter, order, paging and restart state.
 */
class ActiveEmployeeReaderTest {

    private EmbeddedDatabase db;

    @BeforeEach
    void schema() {
        db =
                new EmbeddedDatabaseBuilder()
                        .setType(EmbeddedDatabaseType.H2)
                        .generateUniqueName(true)
                        .build();
        JdbcTemplate jdbc = new JdbcTemplate(db);
        jdbc.execute(
                "CREATE TABLE employee (employee_id BIGINT PRIMARY KEY, location_id BIGINT,"
                        + " current_basic NUMERIC(12,2), stop_payment_flag CHAR(1), record_status"
                        + " VARCHAR(25), pay_pattern VARCHAR(3))");
        for (long id = 1; id <= 7; id++) {
            jdbc.update(
                    "INSERT INTO employee VALUES (?, ?, ?, 'N', ?, 'IDA')",
                    id,
                    id % 2 == 0 ? 20L : 10L,
                    1000 * id,
                    id == 3 ? "REJECTED" : "ACTIVE");
        }
        jdbc.update("INSERT INTO employee VALUES (8, 30, 5000, 'N', 'ACTIVE', 'IDA')");
    }

    @AfterEach
    void drop() {
        db.shutdown();
    }

    @Test
    void readsOnlyActiveEmployeesOfTheLocationsInIdOrderAcrossPages() throws Exception {
        JdbcPagingItemReader<EmployeeRow> reader =
                ActiveEmployeeReader.create("test", db, List.of(10L, 20L), 2);
        reader.open(new ExecutionContext());
        List<Long> ids = readAll(reader);
        reader.close();
        assertThat(ids).containsExactly(1L, 2L, 4L, 5L, 6L, 7L); // 3 is not ACTIVE, 8 is elsewhere
    }

    @Test
    void restartsAfterTheLastSavedPosition() throws Exception {
        ExecutionContext context = new ExecutionContext();
        JdbcPagingItemReader<EmployeeRow> first =
                ActiveEmployeeReader.create("test", db, List.of(10L, 20L), 2);
        first.open(context);
        first.read();
        first.read();
        first.read();
        first.update(context); // what the step saves at a chunk commit
        first.close();

        JdbcPagingItemReader<EmployeeRow> restarted =
                ActiveEmployeeReader.create("test", db, List.of(10L, 20L), 2);
        restarted.open(context);
        assertThat(readAll(restarted)).containsExactly(5L, 6L, 7L);
        restarted.close();
    }

    private static List<Long> readAll(JdbcPagingItemReader<EmployeeRow> reader) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (EmployeeRow row = reader.read(); row != null; row = reader.read()) {
            ids.add(row.employeeId());
        }
        return ids;
    }

    private Map<Long, Long> counts(List<Long> locations) {
        Map<Long, Long> counts = new HashMap<>();
        new NamedParameterJdbcTemplate(db)
                .query(
                        ActiveEmployeeReader.countSql(),
                        new MapSqlParameterSource("locations", locations),
                        rs -> {
                            counts.put(rs.getLong(1), rs.getLong(2));
                        });
        return counts;
    }

    /** The partitioner's count: ACTIVE employees only, per requested location only. */
    @Test
    void countsActiveEmployeesPerRequestedLocationOnly() {
        // 3 is REJECTED (location 10), 8 is ACTIVE but in location 30 which is not requested.
        assertThat(counts(List.of(10L, 20L))).containsOnly(Map.entry(10L, 3L), Map.entry(20L, 3L));
        assertThat(counts(List.of(10L))).containsOnly(Map.entry(10L, 3L));
        assertThat(counts(List.of(30L))).containsOnly(Map.entry(30L, 1L));
        assertThat(counts(List.of(99L))).as("a location without employees").isEmpty();
    }

    /** The count and the reader agree on who is counted (a partition has no gap or overlap). */
    @Test
    void theCountEqualsWhatTheReaderReadsForTheSameLocations() throws Exception {
        JdbcPagingItemReader<EmployeeRow> reader =
                ActiveEmployeeReader.create("test", db, List.of(10L, 20L), 3);
        reader.open(new ExecutionContext());
        int read = readAll(reader).size();
        reader.close();
        assertThat((long) read)
                .isEqualTo(counts(List.of(10L, 20L)).values().stream().mapToLong(v -> v).sum());
    }
}
