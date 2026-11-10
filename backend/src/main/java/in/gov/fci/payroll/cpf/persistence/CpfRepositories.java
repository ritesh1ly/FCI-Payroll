package in.gov.fci.payroll.cpf.persistence;

import in.gov.fci.payroll.cpf.business.model.BorrowingOrganisation;
import in.gov.fci.payroll.cpf.business.model.CpfClaim;
import in.gov.fci.payroll.cpf.business.model.CpfClaimHistory;
import in.gov.fci.payroll.cpf.business.model.CpfDeputationRecovery;
import in.gov.fci.payroll.cpf.business.model.CpfInterestFreeze;
import in.gov.fci.payroll.cpf.business.model.CpfInterestRun;
import in.gov.fci.payroll.cpf.business.model.CpfMemberIdHistory;
import in.gov.fci.payroll.cpf.business.model.CpfPurposeDocument;
import in.gov.fci.payroll.cpf.business.model.CpfSanctionTier;
import in.gov.fci.payroll.cpf.business.model.CpfSspSyncLog;
import in.gov.fci.payroll.cpf.business.model.CpfWithdrawalPurpose;
import in.gov.fci.payroll.cpf.business.model.EmployeePfDetail;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader;
import in.gov.fci.payroll.cpf.business.model.OpeningBalanceCorrection;
import in.gov.fci.payroll.cpf.business.model.PfInterestRate;
import in.gov.fci.payroll.cpf.business.model.PfSanction;
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
 * Target DDL v4.0 Section 10 (CPF / PF / GPF / VPF). No repository extends {@code CrudRepository}:
 * nothing of the CPF ledger is ever deleted (R-DEL, SRS-6-10).
 */
public final class CpfRepositories {

    private CpfRepositories() {}

    /** M-19 PF interest rates. */
    public interface InterestRates extends Repository<PfInterestRate, Long> {

        /** Every rate, newest year first, then fund, source and date (the M-19 list order). */
        List<PfInterestRate>
                findAllByOrderByFinYearDescFundTypeAscRateSourceAscAppliesFromDescInterestRateIdDesc();

        /** One series (year, fund, source), latest first. */
        List<PfInterestRate> findByFinYearAndFundTypeAndRateSourceOrderByAppliesFromDesc(
                String finYear, String fundType, String rateSource);

        Optional<PfInterestRate> findById(Long id);

        /** The rate, locked for the rest of the transaction (a declaration serialises on it). */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT r FROM PfInterestRate r WHERE r.interestRateId = :id")
        Optional<PfInterestRate> lockById(@Param("id") Long id);

        /** The approved rates of a year and fund (C-12 checks them before a run). */
        List<PfInterestRate> findByFinYearAndFundTypeOrderByAppliesFromAsc(
                String finYear, String fundType);

        PfInterestRate save(PfInterestRate rate);

        void flush();
    }

    /** Member ledgers (one per financial year, fund and CPF Member ID). */
    public interface Headers extends Repository<EmployeePfHeader, Long> {

        /** The member's ledgers of one fund, newest year first. */
        List<EmployeePfHeader> findByEmployeeIdAndFundTypeOrderByFinYearDescPfHeaderIdDesc(
                Long employeeId, String fundType);

        /**
         * The ids of the member's ledgers of one year and fund, newest first: read without loading
         * the ledger, so the lock that follows reads it once, as it is now.
         */
        @Query(
                "SELECT h.pfHeaderId FROM EmployeePfHeader h WHERE h.employeeId = :employeeId AND"
                        + " h.finYear = :finYear AND h.fundType = :fundType ORDER BY h.pfHeaderId"
                        + " DESC")
        List<Long> idsOf(
                @Param("employeeId") Long employeeId,
                @Param("finYear") String finYear,
                @Param("fundType") String fundType);

        Optional<EmployeePfHeader> findById(Long id);

        /** The ledger, locked for the rest of the transaction: every posting serialises on it. */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT h FROM EmployeePfHeader h WHERE h.pfHeaderId = :id")
        Optional<EmployeePfHeader> lockById(@Param("id") Long id);

        /** The ledgers of a zone's year and fund (C-12 runs and the freeze), in member order. */
        List<EmployeePfHeader> findByFinYearAndZoneIdAndFundTypeOrderByEmployeeIdAscPfHeaderIdAsc(
                String finYear, Long zoneId, String fundType);

        EmployeePfHeader save(EmployeePfHeader header);
    }

    /** C-12 interest runs: written once, complete; never changed or deleted. */
    public interface InterestRuns extends Repository<CpfInterestRun, Long> {

        /** The runs of a zone's year, latest first (the Interest Run Audit list). */
        List<CpfInterestRun> findByFinYearAndZoneIdOrderByRunNumberDesc(
                String finYear, Long zoneId);

        CpfInterestRun save(CpfInterestRun run);
    }

    /**
     * CPF sanctions (the withdrawal register, SRS-6-55): never deleted, cancelled with a reason.
     */
    public interface Sanctions extends Repository<PfSanction, Long> {

        Optional<PfSanction> findById(Long id);

        /**
         * The sanction, locked for the rest of the transaction: every tier action serialises on it.
         */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT s FROM PfSanction s WHERE s.sanctionId = :id")
        Optional<PfSanction> lockById(@Param("id") Long id);

        /** The member's sanctions, newest first (the register and the eligibility checks). */
        List<PfSanction> findByEmployeeIdOrderBySanctionIdDesc(Long employeeId);

        /** The sanctions an I-09 register names (SRS l.2167; Hibernate splits a long IN list). */
        List<PfSanction> findBySanctionIdIn(java.util.Collection<Long> ids);

        /**
         * The withdrawal register of I-04 (SRS-6-55, SRS-NF-3): the sanctions of members working at
         * {@code locations}, optionally of one stage, newest first, one page.
         */
        @Query(
                value =
                        "SELECT s FROM PfSanction s WHERE EXISTS (SELECT 1 FROM Employee e WHERE"
                            + " e.id = s.employeeId AND e.locationId IN :locations) AND (:stage IS"
                            + " NULL OR s.workflowStage = :stage) ORDER BY s.sanctionId DESC",
                countQuery =
                        "SELECT COUNT(s) FROM PfSanction s WHERE EXISTS (SELECT 1 FROM Employee e"
                                + " WHERE e.id = s.employeeId AND e.locationId IN :locations) AND"
                                + " (:stage IS NULL OR s.workflowStage = :stage)")
        Page<PfSanction> inScope(
                @Param("locations") Collection<Long> locations,
                @Param("stage") String stage,
                Pageable pageable);

        /** SRS-6-4: the requests waiting at a tier, by sanction type, as [type, count] rows. */
        @Query(
                "SELECT s.sanctionType, COUNT(s) FROM PfSanction s WHERE EXISTS (SELECT 1 FROM"
                        + " Employee e WHERE e.id = s.employeeId AND e.locationId IN :locations)"
                        + " AND s.workflowStage IN ('UNIT_VERIFICATION', 'ZONAL_HQ_VALIDATION',"
                        + " 'COMPETENT_AUTHORITY') GROUP BY s.sanctionType")
        List<Object[]> pendingByType(@Param("locations") Collection<Long> locations);

        Optional<PfSanction> findByApprovalId(Long approvalId);

        /** SRS-6-38: the sanction numbers already issued in a zone (its running sequence). */
        @Query(
                "SELECT COUNT(s) FROM PfSanction s WHERE s.sanctionZoneId = :zone AND"
                        + " s.systemSanctionNumber IS NOT NULL")
        long numberedInZone(@Param("zone") Long zone);

        PfSanction save(PfSanction sanction);

        void flush();
    }

    /** Tier actions: appended, never changed. */
    public interface SanctionTiers extends Repository<CpfSanctionTier, Long> {

        List<CpfSanctionTier> findBySanctionIdOrderByTierActionIdAsc(Long sanctionId);

        /** The tier actions of a register page at once. */
        List<CpfSanctionTier> findBySanctionIdInOrderByTierActionIdAsc(
                java.util.Collection<Long> sanctionIds);

        CpfSanctionTier save(CpfSanctionTier tier);
    }

    /** The member's requests (E-01). */
    public interface Claims extends Repository<CpfClaim, Long> {

        Optional<CpfClaim> findBySanctionId(Long sanctionId);

        /** The claims of a register page at once. */
        List<CpfClaim> findBySanctionIdIn(java.util.Collection<Long> sanctionIds);

        List<CpfClaim> findByEmployeeIdOrderByClaimIdDesc(Long employeeId);

        CpfClaim save(CpfClaim claim);
    }

    /** The status changes of the requests: appended, never changed. */
    public interface ClaimHistory extends Repository<CpfClaimHistory, Long> {

        List<CpfClaimHistory> findByClaimIdOrderByClaimHistoryIdAsc(Long claimId);

        CpfClaimHistory save(CpfClaimHistory row);
    }

    /** Withdrawal purposes: effective-dated versions, never deleted. */
    public interface Purposes extends Repository<CpfWithdrawalPurpose, Long> {

        /** Every version, by code, newest first (the M-19 purpose list and its history). */
        List<CpfWithdrawalPurpose> findAllByOrderByPurposeCodeAscEffectiveFromDesc();

        /** The versions of one purpose, newest first. */
        List<CpfWithdrawalPurpose> findByPurposeCodeOrderByEffectiveFromDesc(String purposeCode);

        Optional<CpfWithdrawalPurpose> findById(Long id);

        /** BR-CPF-029: the name of a limit rule in the catalogue (null when it has none). */
        @Query(
                value = "SELECT rule_name FROM rule_definition WHERE rule_key = :key",
                nativeQuery = true)
        String ruleName(@Param("key") String key);

        CpfWithdrawalPurpose save(CpfWithdrawalPurpose purpose);

        void flush();
    }

    /** The checklist rows of purpose versions. */
    public interface PurposeDocuments extends Repository<CpfPurposeDocument, Long> {

        List<CpfPurposeDocument> findByPurposeIdOrderByPurposeDocId(Long purposeId);

        CpfPurposeDocument save(CpfPurposeDocument document);
    }

    /** C-12 freezes: one per zone and year, never removed (trg_cpf_freeze_irreversible). */
    public interface InterestFreezes extends Repository<CpfInterestFreeze, Long> {

        Optional<CpfInterestFreeze> findByFinYearAndZoneId(String finYear, Long zoneId);

        CpfInterestFreeze save(CpfInterestFreeze freeze);
    }

    /** Monthly ledger rows: written once, never changed or deleted. */
    public interface Details extends Repository<EmployeePfDetail, Long> {

        List<EmployeePfDetail> findByPfHeaderIdOrderByPayrollYymmAscPfDetailIdAsc(Long headerId);

        EmployeePfDetail save(EmployeePfDetail detail);
    }

    /**
     * The CPF SSP sync log: appended, never deleted; a change queued for after its commit has its
     * outcome recorded on its row once sent (Wave C review).
     */
    public interface SyncLog extends Repository<CpfSspSyncLog, Long> {

        List<CpfSspSyncLog> findByEmployeeIdOrderBySyncIdDesc(Long employeeId);

        Optional<CpfSspSyncLog> findById(Long id);

        CpfSspSyncLog save(CpfSspSyncLog row);
    }

    /** I-22 correction rows (one per changed part, sharing the request's reference). */
    public interface Corrections extends Repository<OpeningBalanceCorrection, Long> {

        List<OpeningBalanceCorrection> findByCorrectionRefOrderByCorrectionId(String ref);

        List<OpeningBalanceCorrection> findByApprovalIdOrderByCorrectionId(Long approvalId);

        List<OpeningBalanceCorrection> findByEmployeeIdAndFinYearOrderByCorrectionId(
                Long employeeId, String finYear);

        List<OpeningBalanceCorrection> findAllByOrderByRaisedAtDescCorrectionIdDesc();

        /**
         * The corrections of members at {@code locations}, newest first: the scope filtered in the
         * database, not by loading every member id of the scope (deep profile 2026-10-27).
         */
        @Query(
                "SELECT c FROM OpeningBalanceCorrection c WHERE EXISTS (SELECT 1 FROM Employee e"
                        + " WHERE e.id = c.employeeId AND e.locationId IN :locations) ORDER BY"
                        + " c.raisedAt DESC, c.correctionId DESC")
        List<OpeningBalanceCorrection> inScope(@Param("locations") Collection<Long> locations);

        OpeningBalanceCorrection save(OpeningBalanceCorrection row);

        void flush();
    }

    /** The CPF / EPS / UAN / EPFO identifiers a member has held (received from HRMS). */
    public interface MemberIds extends Repository<CpfMemberIdHistory, Long> {

        List<CpfMemberIdHistory> findByEmployeeIdAndIdTypeOrderByEffectiveFromDesc(
                Long employeeId, String idType);
    }

    /** I-09: the borrowing organisations (SRS-6-27). */
    public interface BorrowingOrgs extends Repository<BorrowingOrganisation, Long> {

        Optional<BorrowingOrganisation> findById(Long id);

        Optional<BorrowingOrganisation> findByOrgCode(String code);

        List<BorrowingOrganisation> findAllByOrderByOrgName();

        BorrowingOrganisation save(BorrowingOrganisation org);
    }

    /** I-09: the deputation entries (SRS-6-27 … 6-30). */
    public interface DeputationEntries extends Repository<CpfDeputationRecovery, Long> {

        Optional<CpfDeputationRecovery> findById(Long id);

        List<CpfDeputationRecovery> findByEmployeeIdOrderByPayrollYymmDescIdDesc(Long employeeId);

        List<CpfDeputationRecovery> findByApprovalId(Long approvalId);

        List<CpfDeputationRecovery> findAllByOrderByIdDesc();

        /**
         * The entries of members at {@code locations}, newest first (scope filtered in the
         * database).
         */
        @Query(
                "SELECT r FROM CpfDeputationRecovery r WHERE EXISTS (SELECT 1 FROM Employee e WHERE"
                        + " e.id = r.employeeId AND e.locationId IN :locations) ORDER BY r.id DESC")
        List<CpfDeputationRecovery> inScope(@Param("locations") Collection<Long> locations);

        CpfDeputationRecovery save(CpfDeputationRecovery entry);
    }
}
