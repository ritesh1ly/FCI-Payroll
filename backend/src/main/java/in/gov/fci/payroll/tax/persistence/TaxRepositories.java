package in.gov.fci.payroll.tax.persistence;

import in.gov.fci.payroll.tax.business.model.ExternalIncomeDeclaration;
import in.gov.fci.payroll.tax.business.model.FilingRecord;
import in.gov.fci.payroll.tax.business.model.Form16Certificate;
import in.gov.fci.payroll.tax.business.model.ItDeclaration;
import in.gov.fci.payroll.tax.business.model.ItDeclarationSubmission;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail;
import in.gov.fci.payroll.tax.business.model.ItSection;
import in.gov.fci.payroll.tax.business.model.OutputControlTotal;
import in.gov.fci.payroll.tax.business.model.OutputDefinition;
import in.gov.fci.payroll.tax.business.model.OutputField;
import in.gov.fci.payroll.tax.business.model.OutputVersion;
import in.gov.fci.payroll.tax.business.model.PtState;
import in.gov.fci.payroll.tax.business.model.ReturnDeductee;
import in.gov.fci.payroll.tax.business.model.SavingsDeclaration;
import in.gov.fci.payroll.tax.business.model.StatutoryReturn;
import in.gov.fci.payroll.tax.business.model.TaxChallan;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Repositories of the income-tax module: no delete method anywhere (R-DEL). */
public final class TaxRepositories {

    private TaxRepositories() {}

    /** {@code it_section_master}. */
    public interface Sections extends Repository<ItSection, Long> {

        /** Every version, by code, newest first (the M-18 section card and its history). */
        Page<ItSection> findAllByOrderBySectionCodeAscEffectiveFromDesc(Pageable page);

        /** The versions of one section, newest first. */
        List<ItSection> findBySectionCodeOrderByEffectiveFromDesc(String sectionCode);

        /**
         * The section versions in force on {@code day}, by code (the member's declaration form).
         */
        @Query(
                "SELECT s FROM ItSection s WHERE s.effectiveFrom <= :day AND (s.effectiveTo IS NULL"
                        + " OR s.effectiveTo >= :day) ORDER BY s.sectionCode")
        List<ItSection> inForceOn(@Param("day") LocalDate day);

        ItSection save(ItSection section);
    }

    /** {@code pt_state_master}. */
    public interface PtStates extends Repository<PtState, Long> {

        Page<PtState> findAllByOrderByStateCodeAsc(Pageable page);

        Optional<PtState> findByStateCode(String stateCode);

        /** Every state (the PT computation's cache, {@code PtStates}). */
        List<PtState> findAllByOrderByStateCodeAsc();

        PtState save(PtState state);
    }

    /** {@code it_regime_detail}: one row per member and financial year. */
    public interface Regimes extends Repository<ItRegimeDetail, Long> {

        /** The member's regime row, locked: two submissions of one member never race. */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query(
                "SELECT r FROM ItRegimeDetail r WHERE r.employeeId = :employee AND r.finYear ="
                        + " :year")
        Optional<ItRegimeDetail> lockFor(
                @Param("employee") Long employeeId, @Param("year") String finYear);

        Optional<ItRegimeDetail> findByEmployeeIdAndFinYear(Long employeeId, String finYear);

        /** The regimes of one year of at most 1,000 members (callers chunk by InLists.MAX). */
        List<ItRegimeDetail> findByFinYearAndEmployeeIdIn(String finYear, Collection<Long> ids);

        Optional<ItRegimeDetail> findById(Long id);

        /** The regimes of a page of rounds (at most the page size, below the IN-list limit). */
        List<ItRegimeDetail> findByRegimeDetailIdIn(Collection<Long> ids);

        ItRegimeDetail save(ItRegimeDetail regime);
    }

    /** {@code it_declaration_submission}: the rounds of a member's declarations. */
    public interface Submissions extends Repository<ItDeclarationSubmission, Long> {

        /** The member's rounds of a year, newest first. */
        List<ItDeclarationSubmission> findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(
                Long employeeId, String finYear);

        Optional<ItDeclarationSubmission> findById(Long id);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT s FROM ItDeclarationSubmission s WHERE s.submissionId = :id")
        Optional<ItDeclarationSubmission> lockById(@Param("id") Long id);

        /**
         * I-07: the rounds of a year in the user's offices, newest first, by status (null: all).
         */
        @Query(
                value =
                        "SELECT s FROM ItDeclarationSubmission s WHERE s.finYear = :year AND EXISTS"
                                + " (SELECT 1 FROM Employee e WHERE e.id = s.employeeId AND"
                                + " e.locationId IN :locations) AND (:status IS NULL OR"
                                + " s.submissionStatus = :status) ORDER BY s.submissionId DESC",
                countQuery =
                        "SELECT COUNT(s) FROM ItDeclarationSubmission s WHERE s.finYear = :year AND"
                                + " EXISTS (SELECT 1 FROM Employee e WHERE e.id = s.employeeId AND"
                                + " e.locationId IN :locations) AND (:status IS NULL OR"
                                + " s.submissionStatus = :status)")
        Page<ItDeclarationSubmission> inScope(
                @Param("year") String finYear,
                @Param("locations") Collection<Long> locations,
                @Param("status") String status,
                Pageable pageable);

        /** The approved rounds of a year (the proof deadline looks at these). */
        List<ItDeclarationSubmission> findByFinYearAndSubmissionStatus(
                String finYear, String status);

        ItDeclarationSubmission save(ItDeclarationSubmission submission);
    }

    /** {@code it_declaration}: the lines of the rounds. */
    public interface Declarations extends Repository<ItDeclaration, Long> {

        List<ItDeclaration> findBySubmissionIdOrderByDeclarationId(Long submissionId);

        List<ItDeclaration> findBySubmissionIdInOrderByDeclarationId(
                Collection<Long> submissionIds);

        ItDeclaration save(ItDeclaration declaration);
    }

    /** {@code external_income_declaration}: the I-18 entries. */
    public interface Entries extends Repository<ExternalIncomeDeclaration, Long> {

        Optional<ExternalIncomeDeclaration> findById(Long id);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT e FROM ExternalIncomeDeclaration e WHERE e.extIncomeId = :id")
        Optional<ExternalIncomeDeclaration> lockById(@Param("id") Long id);

        /** The entries of a member's year, newest first. */
        List<ExternalIncomeDeclaration> findByEmployeeIdAndFinYearOrderByExtIncomeIdDesc(
                Long employeeId, String finYear);

        /**
         * I-18: the entries of a year in the user's offices, newest first, by status (null: all).
         */
        @Query(
                value =
                        "SELECT x FROM ExternalIncomeDeclaration x WHERE x.finYear = :year AND"
                            + " EXISTS (SELECT 1 FROM Employee e WHERE e.id = x.employeeId AND"
                            + " e.locationId IN :locations) AND (:status IS NULL OR x.entryStatus ="
                            + " :status) ORDER BY x.extIncomeId DESC",
                countQuery =
                        "SELECT COUNT(x) FROM ExternalIncomeDeclaration x WHERE x.finYear = :year"
                            + " AND EXISTS (SELECT 1 FROM Employee e WHERE e.id = x.employeeId AND"
                            + " e.locationId IN :locations) AND (:status IS NULL OR x.entryStatus ="
                            + " :status)")
        Page<ExternalIncomeDeclaration> inScope(
                @Param("year") String finYear,
                @Param("locations") Collection<Long> locations,
                @Param("status") String status,
                Pageable pageable);

        ExternalIncomeDeclaration save(ExternalIncomeDeclaration entry);
    }

    /** {@code savings_declaration}: the savings and rebate rows of a member's year. */
    public interface Savings extends Repository<SavingsDeclaration, Long> {

        List<SavingsDeclaration> findByEmployeeIdAndFinYearOrderBySavingsId(
                Long employeeId, String finYear);

        SavingsDeclaration save(SavingsDeclaration row);
    }

    /** {@code report_definition}: the statutory outputs of M-17 (read-only catalogue). */
    public interface OutputDefinitions extends Repository<OutputDefinition, Long> {

        List<OutputDefinition> findByOutputDomainOrderByReportKey(String outputDomain);

        Optional<OutputDefinition> findByReportKey(String reportKey);
    }

    /** {@code report_definition_version}. */
    public interface OutputVersions extends Repository<OutputVersion, Long> {

        /** The versions of a definition, newest first. */
        List<OutputVersion> findByReportDefIdOrderByEffectiveFromDesc(Long reportDefId);

        List<OutputVersion> findByReportDefIdIn(Collection<Long> reportDefIds);

        OutputVersion save(OutputVersion version);
    }

    /** {@code report_field_binding}. */
    public interface OutputFields extends Repository<OutputField, Long> {

        List<OutputField> findByReportVersionIdOrderByFieldOrder(Long reportVersionId);

        /**
         * The fields of several versions in one read (the M-17 catalogue, no query per version).
         */
        List<OutputField> findByReportVersionIdInOrderByFieldOrder(
                Collection<Long> reportVersionIds);

        OutputField save(OutputField field);
    }

    /** {@code report_control_total}. */
    public interface OutputControls extends Repository<OutputControlTotal, Long> {

        List<OutputControlTotal> findByReportVersionId(Long reportVersionId);

        /** The control totals of several versions in one read (the M-17 catalogue). */
        List<OutputControlTotal> findByReportVersionIdIn(Collection<Long> reportVersionIds);

        OutputControlTotal save(OutputControlTotal control);
    }

    /** {@code form16}: the annual certificates. */
    public interface Form16s extends Repository<Form16Certificate, Long> {

        Optional<Form16Certificate> findById(Long id);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT f FROM Form16Certificate f WHERE f.form16Id = :id")
        Optional<Form16Certificate> lockById(@Param("id") Long id);

        Optional<Form16Certificate> findByEmployeeIdAndFinYear(Long employeeId, String finYear);

        List<Form16Certificate> findByEmployeeIdAndReleaseStatusOrderByFinYearDesc(
                Long employeeId, String status);

        List<Form16Certificate> findByFinYearAndEmployeeIdIn(
                String finYear, Collection<Long> employeeIds);

        /** C-10: the certificates of a year in the user's offices, by status (null: all). */
        @Query(
                value =
                        "SELECT f FROM Form16Certificate f WHERE f.finYear = :year AND exists"
                            + " (select 1 from Employee e where e.id = f.employeeId and"
                            + " e.locationId in :locations) AND (:status IS NULL OR f.releaseStatus"
                            + " = :status) ORDER BY f.form16Id",
                countQuery =
                        "SELECT COUNT(f) FROM Form16Certificate f WHERE f.finYear = :year AND"
                            + " EXISTS (SELECT 1 FROM Employee e WHERE e.id = f.employeeId AND"
                            + " e.locationId IN :locations) AND (:status IS NULL OR f.releaseStatus"
                            + " = :status)")
        Page<Form16Certificate> inScope(
                @Param("year") String finYear,
                @Param("locations") Collection<Long> locations,
                @Param("status") String status,
                Pageable pageable);

        Form16Certificate save(Form16Certificate certificate);
    }

    /** {@code statutory_return}. */
    public interface Returns extends Repository<StatutoryReturn, Long> {

        Optional<StatutoryReturn> findById(Long id);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT r FROM StatutoryReturn r WHERE r.returnId = :id")
        Optional<StatutoryReturn> lockById(@Param("id") Long id);

        /** The returns of a unit and year, newest first. */
        List<StatutoryReturn> findByOrgUnitIdAndFinYearOrderByReturnIdDesc(
                Long orgUnitId, String finYear);

        /** C-11: the returns of the user's units, newest first. */
        Page<StatutoryReturn> findByOrgUnitIdInAndFinYearOrderByReturnIdDesc(
                Collection<Long> orgUnitIds, String finYear, Pageable pageable);

        StatutoryReturn save(StatutoryReturn statutoryReturn);
    }

    /** {@code statutory_return_deductee}. */
    public interface Deductees extends Repository<ReturnDeductee, Long> {

        List<ReturnDeductee> findByReturnIdOrderByDeducteeId(Long returnId);

        /** The deductees in the return (a regeneration keeps an emptied row for a former one). */
        Page<ReturnDeductee> findByReturnIdAndTaxDeductedGreaterThanOrderByDeducteeId(
                Long returnId, java.math.BigDecimal minimum, Pageable pageable);

        Optional<ReturnDeductee> findByReturnIdAndEmployeeId(Long returnId, Long employeeId);

        /** A regeneration empties the rows of the earlier one (never deletes them, R-DEL). */
        @Modifying(flushAutomatically = true)
        @Query(
                "UPDATE ReturnDeductee d SET d.amountPaid = 0, d.taxDeducted = 0, d.challanId ="
                        + " NULL, d.panNumber = NULL, d.panValid = 'N', d.higherRateApplied = 'N'"
                        + " WHERE d.returnId = :id")
        int emptyAll(@Param("id") Long returnId);

        /** The deductees in the return (TDS deducted in the quarter). */
        @Query("SELECT COUNT(d) FROM ReturnDeductee d WHERE d.returnId = :id AND d.taxDeducted > 0")
        long countInReturn(@Param("id") Long returnId);

        @Query("SELECT SUM(d.taxDeducted) FROM ReturnDeductee d WHERE d.returnId = :id")
        java.math.BigDecimal totalInReturn(@Param("id") Long returnId);

        ReturnDeductee save(ReturnDeductee deductee);
    }

    /** {@code tax_challan}. */
    public interface Challans extends Repository<TaxChallan, Long> {

        List<TaxChallan> findByOrgUnitIdAndFinYearOrderByChallanDate(
                Long orgUnitId, String finYear);

        Optional<TaxChallan> findByCinNumberAndBsrCodeAndChallanDate(
                String cin, String bsr, java.time.LocalDate date);

        TaxChallan save(TaxChallan challan);

        /** The challans a deductee page names (SRS l.2625: shown by CIN, never the id). */
        List<TaxChallan> findByChallanIdIn(java.util.Collection<Long> ids);
    }

    /** {@code statutory_filing_record}. */
    public interface Filings extends Repository<FilingRecord, Long> {

        List<FilingRecord> findByReturnIdOrderByFilingId(Long returnId);

        /** The filings of a page of returns in one read (the C-11 register, no query per row). */
        List<FilingRecord> findByReturnIdInOrderByFilingId(Collection<Long> returnIds);

        FilingRecord save(FilingRecord filing);
    }
}
