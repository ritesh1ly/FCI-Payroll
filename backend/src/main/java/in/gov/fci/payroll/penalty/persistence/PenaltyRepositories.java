package in.gov.fci.payroll.penalty.persistence;

import in.gov.fci.payroll.penalty.business.model.OverAndAbovePayment;
import in.gov.fci.payroll.penalty.business.model.PenaltyOrder;
import in.gov.fci.payroll.penalty.business.model.PenaltyRecoveryLine;
import in.gov.fci.payroll.penalty.business.model.PenaltyRevoke;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The penalty module's repositories (R-DEL: no delete). */
public final class PenaltyRepositories {

    private PenaltyRepositories() {}

    /** {@code penalty_order}. */
    public interface Orders extends Repository<PenaltyOrder, Long> {

        Optional<PenaltyOrder> findById(Long id);

        Optional<PenaltyOrder> findByApprovalId(Long approvalId);

        List<PenaltyOrder> findByEmployeeIdOrderByIdDesc(Long employeeId);

        List<PenaltyOrder> findByEmployeeIdInOrderByIdDesc(Collection<Long> employeeIds);

        List<PenaltyOrder> findByOrderStatusIn(Collection<String> statuses);

        PenaltyOrder save(PenaltyOrder order);

        void flush();
    }

    /** {@code penalty_recovery_schedule}. */
    public interface Lines extends Repository<PenaltyRecoveryLine, Long> {

        List<PenaltyRecoveryLine> findByOrderIdOrderByPayrollYymm(Long orderId);

        List<PenaltyRecoveryLine> findByOrderIdInAndPayrollYymm(
                Collection<Long> orderIds, String yymm);

        PenaltyRecoveryLine save(PenaltyRecoveryLine line);
    }

    /** {@code penalty_revoke}. */
    public interface Revokes extends Repository<PenaltyRevoke, Long> {

        Optional<PenaltyRevoke> findById(Long id);

        List<PenaltyRevoke> findByOrderIdOrderByIdDesc(Long orderId);

        PenaltyRevoke save(PenaltyRevoke revoke);
    }

    /** {@code over_and_above_payment}. */
    public interface Payments extends Repository<OverAndAbovePayment, Long> {

        Optional<OverAndAbovePayment> findById(Long id);

        List<OverAndAbovePayment> findAllByOrderByIdDesc();

        /**
         * The payments of members at {@code locations}, newest first: the scope filtered in the
         * database, not by loading every member id of the scope (deep profile 2026-10-27).
         */
        @Query(
                "SELECT p FROM OverAndAbovePayment p WHERE EXISTS (SELECT 1 FROM Employee e WHERE"
                        + " e.id = p.employeeId AND e.locationId IN :locations) ORDER BY p.id DESC")
        List<OverAndAbovePayment> inScope(@Param("locations") java.util.Collection<Long> locations);

        OverAndAbovePayment save(OverAndAbovePayment payment);
    }
}
