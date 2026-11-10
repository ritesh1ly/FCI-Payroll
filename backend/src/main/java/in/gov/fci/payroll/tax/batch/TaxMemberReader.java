package in.gov.fci.payroll.tax.batch;

import in.gov.fci.payroll.jobs.batch.support.ItemKey;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.persistence.InLists;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;

/**
 * The members of a paying unit's statutory output (C-10, C-11), as the screens take them: every
 * employee record at the locations whose paying unit (cycle unit, OD-11) is the unit. Who is
 * certified or reported is decided per member ({@code Form16Service.partBOf}, {@code
 * ReturnService.deducteeOf}), as on the screen. Paged by employee_id, restartable (saveState); one
 * reader per partition.
 */
final class TaxMemberReader {

    static final String COUNT_SQL =
            "SELECT e.location_id, COUNT(*) FROM employee e WHERE e.location_id IN (:locations)"
                    + " GROUP BY e.location_id";

    /** One member as read: its record id (the skip log names it). */
    record Row(Long employeeId) implements ItemKey {

        @Override
        public String itemKey() {
            return "employee:" + employeeId;
        }
    }

    private TaxMemberReader() {}

    static JdbcPagingItemReader<TaxMemberReader.Row> create(
            String name, DataSource dataSource, Collection<Long> locations, int pageSize)
            throws Exception {
        // Oracle review F1: more than 1,000 locations are split into IN lists Oracle takes.
        InLists.ExpandedMap where =
                InLists.expand(
                        "WHERE e.location_id IN (:locations)", Map.of("locations", locations));
        JdbcPagingItemReader<TaxMemberReader.Row> reader =
                new JdbcPagingItemReaderBuilder<Row>()
                        .name(name)
                        .dataSource(dataSource)
                        .selectClause("SELECT e.employee_id")
                        .fromClause("FROM employee e")
                        .whereClause(where.sql())
                        .parameterValues(where.params())
                        .sortKeys(Map.of("employee_id", Order.ASCENDING))
                        .pageSize(pageSize)
                        .fetchSize(pageSize)
                        .saveState(true)
                        .rowMapper((rs, i) -> new Row(rs.getLong(1)))
                        .build();
        reader.afterPropertiesSet();
        return reader;
    }

    /** The paying unit of a requested location (the job runs on the whole unit, as C-10 does). */
    static OrgLocation unitOf(OrgDirectory org, long locationId) {
        return org.cycleUnitOf(locationId)
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    /** The locations of the unit's subtree whose paying unit is the unit itself. */
    static Set<Long> locationsOf(OrgDirectory org, Long unitId) {
        return org.subtree(unitId).stream()
                .filter(l -> org.cycleUnitOf(l).map(u -> u.getId().equals(unitId)).orElse(false))
                .collect(Collectors.toSet());
    }
}
