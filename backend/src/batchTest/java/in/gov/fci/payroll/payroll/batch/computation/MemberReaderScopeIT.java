package in.gov.fci.payroll.payroll.batch.computation;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.jobs.batch.BatchTestSupport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * US-GAP-275 (M-01 Casual Labour Indicator, SRS l.3772; SRS-G-55), run against the demo database
 * rather than the SQL text ({@link MemberReaderScopeTest}): the staff engine's reader returns staff
 * records only. Every departmental, DPS and NWNP labour record of the same offices is left out, and
 * so are a staff member separated or dismissed and a record not ACTIVE (Payroll Setup Pending)
 * (changed inside a rolled-back transaction, so no other test sees them).
 */
class MemberReaderScopeIT extends BatchTestSupport {

    private static final LocalDate JULY = LocalDate.of(2026, 7, 1);

    @Autowired private PlatformTransactionManager transactions;

    /** The offices holding both labour and staff records of the demo seed (under 1,000). */
    private List<Long> mixedOffices() {
        return jdbc.queryForList(
                "SELECT location_id FROM employee GROUP BY location_id HAVING SUM(CASE WHEN"
                        + " employee_type = 'STAFF' THEN 1 ELSE 0 END) > 0 AND SUM(CASE WHEN"
                        + " employee_type <> 'STAFF' THEN 1 ELSE 0 END) > 0 ORDER BY location_id"
                        + " FETCH FIRST 900 ROWS ONLY",
                Long.class);
    }

    private Set<Long> read(List<Long> offices) {
        Set<Long> ids = new HashSet<>();
        long after = 0;
        while (true) {
            List<MemberReader.MemberRow> page =
                    MemberReader.page(named, offices, null, JULY, after, 500);
            if (page.isEmpty()) {
                return ids;
            }
            page.forEach(r -> ids.add(r.employeeId()));
            after = page.getLast().employeeId();
        }
    }

    private List<Long> ofType(List<Long> offices, String condition) {
        return named.queryForList(
                "SELECT employee_id FROM employee WHERE location_id IN (:offices) AND " + condition,
                Map.of("offices", offices),
                Long.class);
    }

    @Test
    void SRS_G_55_theStaffReaderReturnsNoLabourRecordAndNoSeparatedOrInactiveMember() {
        List<Long> offices = mixedOffices();
        assertThat(offices).as("the demo seed holds labour beside staff").isNotEmpty();
        List<Long> labour = ofType(offices, "employee_type <> 'STAFF'");
        assertThat(labour).isNotEmpty();

        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            status.setRollbackOnly();
                            List<Long> staff =
                                    ofType(
                                            offices,
                                            "employee_type = 'STAFF' AND record_status = 'ACTIVE'"
                                                    + " AND employment_status = 'IN_SERVICE'");
                            assertThat(staff).hasSizeGreaterThanOrEqualTo(4);
                            List<Long> changed = new ArrayList<>(staff.subList(0, 3));
                            jdbc.update(
                                    "UPDATE employee SET employment_status = 'SEPARATED' WHERE"
                                            + " employee_id = ?",
                                    changed.get(0));
                            jdbc.update(
                                    "UPDATE employee SET employment_status = 'DISMISSED' WHERE"
                                            + " employee_id = ?",
                                    changed.get(1));
                            jdbc.update(
                                    "UPDATE employee SET record_status = 'PAYROLL_SETUP_PENDING'"
                                            + " WHERE employee_id = ?",
                                    changed.get(2));

                            Set<Long> read = read(offices);
                            assertThat(read).as("staff members are read").contains(staff.get(3));
                            assertThat(read).doesNotContainAnyElementsOf(labour);
                            assertThat(read).doesNotContainAnyElementsOf(changed);
                        });
    }
}
