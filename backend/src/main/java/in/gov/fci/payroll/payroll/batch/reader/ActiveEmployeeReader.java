package in.gov.fci.payroll.payroll.batch.reader;

import in.gov.fci.payroll.platform.persistence.InLists;
import java.util.Collection;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;

/**
 * The reader of the payroll jobs: ACTIVE employees of a set of locations, page by page in
 * employee-id order. Stateful and restartable ({@code saveState=true}): after each committed chunk
 * the last employee id is in the step's ExecutionContext, and a restart continues after it. One
 * reader per partition (step scope), so it never needs to be thread-safe.
 */
public final class ActiveEmployeeReader {

    public static final String SQL_SELECT =
            "SELECT employee_id, location_id, current_basic, stop_payment_flag, pay_pattern";
    public static final String SQL_FROM = "FROM employee";
    public static final String SQL_WHERE =
            "WHERE record_status = 'ACTIVE' AND location_id IN (:locations)";

    private ActiveEmployeeReader() {}

    public static JdbcPagingItemReader<EmployeeRow> create(
            String name, DataSource dataSource, Collection<Long> locations, int pageSize)
            throws Exception {
        // Oracle review F1: more than 1,000 locations are split into IN lists Oracle takes.
        InLists.ExpandedMap where = InLists.expand(SQL_WHERE, Map.of("locations", locations));
        JdbcPagingItemReader<EmployeeRow> reader =
                new JdbcPagingItemReaderBuilder<EmployeeRow>()
                        .name(name)
                        .dataSource(dataSource)
                        .selectClause(SQL_SELECT)
                        .fromClause(SQL_FROM)
                        .whereClause(where.sql())
                        .parameterValues(where.params())
                        .sortKeys(Map.of("employee_id", Order.ASCENDING))
                        .pageSize(pageSize)
                        .fetchSize(pageSize)
                        .saveState(true)
                        .rowMapper(
                                (rs, i) ->
                                        new EmployeeRow(
                                                rs.getLong(1),
                                                rs.getLong(2),
                                                rs.getBigDecimal(3),
                                                rs.getString(4),
                                                rs.getString(5)))
                        .build();
        reader.afterPropertiesSet();
        return reader;
    }

    /** ACTIVE employees per location, for the partitioner. */
    public static String countSql() {
        return "SELECT location_id, COUNT(*) FROM employee WHERE record_status = 'ACTIVE' AND"
                + " location_id IN (:locations) GROUP BY location_id";
    }
}
