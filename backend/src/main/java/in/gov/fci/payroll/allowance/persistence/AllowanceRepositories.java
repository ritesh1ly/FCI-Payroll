package in.gov.fci.payroll.allowance.persistence;

import in.gov.fci.payroll.allowance.business.model.CafeteriaElection;
import in.gov.fci.payroll.allowance.business.model.CafeteriaElectionLine;
import in.gov.fci.payroll.allowance.business.model.ReimbursementClaim;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** The allowances module's repositories (R-DEL: no delete). */
public final class AllowanceRepositories {

    private AllowanceRepositories() {}

    /** {@code reimbursement_claim}. */
    public interface Claims extends Repository<ReimbursementClaim, Long> {

        Optional<ReimbursementClaim> findById(Long id);

        Optional<ReimbursementClaim> findByApprovalId(Long approvalId);

        Optional<ReimbursementClaim> findByHrmsChangeTaskId(Long taskId);

        List<ReimbursementClaim> findByEmployeeIdAndClaimType(Long employeeId, String claimType);

        List<ReimbursementClaim> findByEmployeeIdInAndStatusAndMemberType(
                Collection<Long> employeeIds, String status, String memberType);

        List<ReimbursementClaim> findByStatusAndMemberType(String status, String memberType);

        ReimbursementClaim save(ReimbursementClaim claim);

        void flush();
    }

    /** {@code cafeteria_election}. */
    public interface Elections extends Repository<CafeteriaElection, Long> {

        List<CafeteriaElection> findByEmployeeIdAndFinYearOrderByVersionNoDesc(
                Long employeeId, String finYear);

        List<CafeteriaElection> findByStatusAndEffectiveFromLessThanEqual(
                String status, LocalDate day);

        List<CafeteriaElection> findByEmployeeIdInAndEffectiveFromLessThanEqual(
                Collection<Long> employeeIds, LocalDate day);

        CafeteriaElection save(CafeteriaElection election);

        /** Written now: the arrear of a late selection reads the versions by SQL. */
        CafeteriaElection saveAndFlush(CafeteriaElection election);

        Optional<CafeteriaElection> findById(Long id);
    }

    /** {@code cafeteria_election_line}. */
    public interface ElectionLines extends Repository<CafeteriaElectionLine, Long> {

        List<CafeteriaElectionLine> findByElectionIdIn(Collection<Long> electionIds);

        CafeteriaElectionLine save(CafeteriaElectionLine line);
    }
}
