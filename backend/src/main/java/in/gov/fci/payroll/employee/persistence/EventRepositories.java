package in.gov.fci.payroll.employee.persistence;

import in.gov.fci.payroll.employee.business.model.EmployeeNumberHistory;
import in.gov.fci.payroll.employee.business.model.EmployeeTransfer;
import in.gov.fci.payroll.employee.business.model.LifecycleEvent;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Target DDL v4.0 S03: life-cycle events (I-17), transfers (M-11) and the staff-number history. */
public final class EventRepositories {

    private EventRepositories() {}

    public interface LifecycleEvents extends Repository<LifecycleEvent, Long> {
        List<LifecycleEvent> findByEmployeeIdOrderByEventDateDescEventIdDesc(Long employeeId);

        Optional<LifecycleEvent> findByApprovalId(Long approvalId);

        /** The latest events of the employees at {@code locations} (data scope), newest first. */
        @Query(
                value =
                        "select e from LifecycleEvent e, Employee m where m.id = e.employeeId and"
                                + " m.locationId in :locations order by e.createdAt desc,"
                                + " e.eventId desc",
                countQuery =
                        "select count(e) from LifecycleEvent e, Employee m where m.id ="
                                + " e.employeeId and m.locationId in :locations")
        Page<LifecycleEvent> findRecentInScope(
                @Param("locations") Collection<Long> locations, Pageable page);

        LifecycleEvent save(LifecycleEvent event);
    }

    public interface Transfers extends Repository<EmployeeTransfer, Long> {
        List<EmployeeTransfer> findByEmployeeIdOrderByTransferDateDesc(Long employeeId);

        /** One transfer (its X-04 / M-11 History record, SRS-20-29). */
        Optional<EmployeeTransfer> findById(Long transferId);

        Optional<EmployeeTransfer> findByApprovalId(Long approvalId);

        /** Transfers out of or into {@code locations} (data scope), newest first. */
        @Query(
                value =
                        "select t from EmployeeTransfer t where t.fromLocationId in :locations or"
                                + " t.toLocationId in :locations order by t.transferDate desc,"
                                + " t.transferId desc",
                countQuery =
                        "select count(t) from EmployeeTransfer t where t.fromLocationId in"
                                + " :locations or t.toLocationId in :locations")
        Page<EmployeeTransfer> findRecentInScope(
                @Param("locations") Collection<Long> locations, Pageable page);

        EmployeeTransfer save(EmployeeTransfer transfer);
    }

    public interface NumberHistory extends Repository<EmployeeNumberHistory, Long> {
        List<EmployeeNumberHistory> findByEmployeeIdOrderByChangeDateDesc(Long employeeId);

        EmployeeNumberHistory save(EmployeeNumberHistory row);
    }
}
