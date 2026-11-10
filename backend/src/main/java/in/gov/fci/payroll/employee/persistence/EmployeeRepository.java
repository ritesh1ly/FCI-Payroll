package in.gov.fci.payroll.employee.persistence;

import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.platform.persistence.Likes;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Employee master. Every list query takes the caller's data scope as a set of location ids: no
 * query exists that could return rows outside it.
 */
public interface EmployeeRepository extends Repository<Employee, Long> {

    Optional<Employee> findById(Long id);

    /**
     * The record, its row locked for the rest of the transaction (serialises a member's requests).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Employee e WHERE e.id = :id")
    Optional<Employee> lockById(@Param("id") Long id);

    Optional<Employee> findByEmpNum(String empNum);

    /** SRS I-22 Member (CPF Code / UAN) lookup: the record holding the CPF code (unique). */
    List<Employee> findByCpfCode(String cpfCode);

    /** SRS I-22 Member (CPF Code / UAN) lookup: the records holding the UAN. */
    List<Employee> findByUanNumber(String uanNumber);

    /** Many records at once (at most 1,000 ids: Oracle's IN-list limit; callers chunk). */
    List<Employee> findByIdIn(Collection<Long> ids);

    Employee save(Employee employee);

    /**
     * Writes the saved record now (Oracle review F5): the JDBC writers of employee bump
     * row_version, so a JPA change left for the commit would fail its version check after a JDBC
     * write of the same member later in the transaction.
     */
    void flush();

    /*
     * The M-01 status line as predicates, the same rule as Employee.displayStatus(): set-up and
     * change pending first, then PAN / CPF code missing (a labour member has no CPF code), then no
     * bank branch, else Active. Literals only (no bind parameters): Hibernate converts the enum and
     * the Y / N flag, and Oracle accepts them in any clause.
     */
    String SETUP =
            "e.recordStatus ="
                + " in.gov.fci.payroll.employee.business.model.RecordStatus.PAYROLL_SETUP_PENDING";
    String CHANGE =
            "e.recordStatus ="
                    + " in.gov.fci.payroll.employee.business.model.RecordStatus.CHANGE_PENDING";
    String NOT_PENDING = "not (" + SETUP + ") and not (" + CHANGE + ")";
    String PAN_CPF_MISSING =
            NOT_PENDING + " and (e.panNumber is null or (e.labour = false and e.cpfCode is null))";
    String IDENTIFIED =
            NOT_PENDING
                    + " and e.panNumber is not null and (e.labour = true or e.cpfCode is not null)";
    String BANK_INVALID = IDENTIFIED + " and e.bankBranchId is null";
    String ACTIVE = IDENTIFIED + " and e.bankBranchId is not null";

    /**
     * The M-01 filters: scope, free text (EIS number, name or PAN, escaped by {@link Likes}),
     * pattern. SRS-16-7: the text also finds a member by a staff number held before a transfer or
     * reissue ({@code employee_number_history.old_emp_num}), the same PAN-anchored person.
     */
    String FILTERS =
            " e.locationId in :locations"
                    + " and (:text is null or lower(e.empNum) like :text escape '\\'"
                    + " or exists (select 1 from EmployeeNumberHistory h where h.employeeId = e.id"
                    + " and lower(h.oldEmpNum) like :text escape '\\')"
                    + " or lower(e.firstName) like :text escape '\\'"
                    + " or lower(e.lastName) like :text escape '\\'"
                    + " or lower(e.panNumber) like :text escape '\\')"
                    + " and (:pattern is null or e.payPattern = :pattern)";

    /**
     * SRS-G-53 (NEW-G1-07): "the previous unit keeps read-only access to the period served there".
     * A lookup by text also finds a member transferred out of an office in scope (an M-11 transfer
     * row names the posting the member left); the list without text and the tiles stay the present
     * posting's.
     */
    String LOOKUP_FILTERS =
            " (e.locationId in :locations or (:text is not null and exists (select 1 from"
                    + " EmployeeTransfer t where t.employeeId = e.id and t.fromLocationId in"
                    + " :locations)))"
                    + " and (:text is null or lower(e.empNum) like :text escape '\\'"
                    + " or exists (select 1 from EmployeeNumberHistory h where h.employeeId = e.id"
                    + " and lower(h.oldEmpNum) like :text escape '\\')"
                    + " or lower(e.firstName) like :text escape '\\'"
                    + " or lower(e.lastName) like :text escape '\\'"
                    + " or lower(e.panNumber) like :text escape '\\')"
                    + " and (:pattern is null or e.payPattern = :pattern)";

    /**
     * M-01 list within {@code locations}: optional free text, pay pattern and status line ({@code
     * status} null for every status), one page.
     */
    @Query(
            "select e from Employee e where"
                    + LOOKUP_FILTERS
                    + " and (:status is null"
                    + " or (:status = 'PAYROLL_SETUP_PENDING' and "
                    + SETUP
                    + ") or (:status = 'CHANGE_PENDING' and "
                    + CHANGE
                    + ")"
                    + " or (:status = 'PAN_CPF_MISSING' and "
                    + PAN_CPF_MISSING
                    + ") or (:status = 'BANK_INVALID' and "
                    + BANK_INVALID
                    + ") or (:status = 'ACTIVE' and "
                    + ACTIVE
                    + "))")
    Page<Employee> search(
            @Param("locations") Collection<Long> locations,
            @Param("text") String text,
            @Param("pattern") String pattern,
            @Param("status") String status,
            Pageable pageable);

    /**
     * The M-01 tiles over the whole filtered scope (not one page): total, set-up pending, change
     * pending, PAN / CPF missing, bank invalid, active; one row.
     */
    @Query(
            "select count(e),"
                    + " sum(case when "
                    + SETUP
                    + " then 1 else 0 end), sum(case when "
                    + CHANGE
                    + " then 1 else 0 end),"
                    + " sum(case when "
                    + PAN_CPF_MISSING
                    + " then 1 else 0 end), sum(case when "
                    + BANK_INVALID
                    + " then 1 else 0 end), sum(case when "
                    + ACTIVE
                    + " then 1 else 0 end) from Employee e where"
                    + FILTERS)
    List<Object[]> statusCounts(
            @Param("locations") Collection<Long> locations,
            @Param("text") String text,
            @Param("pattern") String pattern);

    /** Record ids of the employees working at {@code locations} (an administrator's scope). */
    @Query("select e.id from Employee e where e.locationId in :locations")
    java.util.Set<Long> idsAt(@Param("locations") java.util.Collection<Long> locations);

    @Query("select e from Employee e where e.locationId in :locations")
    List<Employee> findAllInScope(@Param("locations") Collection<Long> locations);

    /** Head count per location and record status, for the consolidated MIS. */
    @Query(
            "select e.locationId, e.recordStatus, count(e) from Employee e"
                    + " where e.locationId in :locations group by e.locationId, e.recordStatus")
    List<Object[]> countByLocationAndStatus(@Param("locations") Collection<Long> locations);
}
