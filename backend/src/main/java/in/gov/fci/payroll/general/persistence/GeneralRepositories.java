package in.gov.fci.payroll.general.persistence;

import in.gov.fci.payroll.general.business.model.ExceptionEntry;
import in.gov.fci.payroll.general.business.model.FallbackEnableScope;
import in.gov.fci.payroll.general.business.model.FallbackEnablement;
import in.gov.fci.payroll.general.business.model.FallbackUpload;
import in.gov.fci.payroll.general.business.model.FallbackUploadLine;
import in.gov.fci.payroll.general.business.model.OffCyclePayment;
import in.gov.fci.payroll.general.business.model.PayrollQuery;
import in.gov.fci.payroll.general.business.model.SupplementaryPayment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Repositories of the Module 01 transactions. Plain {@code Repository} interfaces with no delete
 * method: these records are never deleted (SRS-G-37).
 */
public final class GeneralRepositories {

    private GeneralRepositories() {}

    public interface Queries extends Repository<PayrollQuery, Long> {
        Optional<PayrollQuery> findById(Long id);

        PayrollQuery save(PayrollQuery query);

        /** A member's own queries (eSeva), newest first. */
        List<PayrollQuery> findByEmployeeIdOrderByRaisedAtDesc(Long employeeId);

        /**
         * Queries of members working at {@code locations} (the session's data scope), newest first,
         * optionally of one status. The member's current unit decides the scope, as on M-01.
         */
        @Query(
                "select q from PayrollQuery q where exists (select 1 from Employee e where e.id ="
                        + " q.employeeId and e.locationId in :locations) and (:status is null or"
                        + " q.status = :status) order by q.raisedAt desc, q.id desc")
        Page<PayrollQuery> findInScope(
                @Param("locations") Collection<Long> locations,
                @Param("status") PayrollQuery.Status status,
                Pageable page);

        /** The X-06 tiles: queries of the data scope by status (the whole scope, not one page). */
        @Query(
                "select q.status, count(q) from PayrollQuery q where exists (select 1 from Employee"
                        + " e where e.id = q.employeeId and e.locationId in :locations) group by"
                        + " q.status")
        List<Object[]> countByStatus(@Param("locations") Collection<Long> locations);
    }

    public interface Exceptions extends Repository<ExceptionEntry, Long> {
        Optional<ExceptionEntry> findById(Long id);

        ExceptionEntry save(ExceptionEntry exception);

        /**
         * Unsettled entries of one condition (dedupe on raise, clear). Null-safe on cycle, member
         * and field, which are optional.
         */
        @Query(
                "select x from ExceptionEntry x where x.exceptionCode = :code and ((:cycle is null"
                    + " and x.cycleInstanceId is null) or x.cycleInstanceId = :cycle) and"
                    + " ((:employee is null and x.employeeId is null) or x.employeeId = :employee)"
                    + " and ((:field is null and x.fieldName is null) or x.fieldName = :field) and"
                    + " x.status in :statuses")
        List<ExceptionEntry> findCondition(
                @Param("code") String code,
                @Param("cycle") Long cycle,
                @Param("employee") Long employee,
                @Param("field") String field,
                @Param("statuses") Collection<ExceptionEntry.Status> statuses);

        /**
         * 1 if entry {@code id} lies in the data scope (same rule as {@link #findInScope}), else 0.
         */
        @Query(
                "select count(x) from ExceptionEntry x left join Employee e on e.id = x.employeeId"
                    + " left join PayrollCycleInstance c on c.cycleInstanceId = x.cycleInstanceId"
                    + " where (e.locationId in :locations or (x.employeeId is null and c.orgUnitId"
                    + " in :locations)) and x.id = :id")
        long countVisible(@Param("id") Long id, @Param("locations") Collection<Long> locations);

        /**
         * The filters shared by the worklist, its groups and its tiles (SRS-G-9, SRS l.604:
         * "filters by engine, period, office scope or exception class"): an entry about a member
         * belongs to the member's unit, a cycle-level entry to the cycle's unit; optional module
         * (class), status, severity, cycle (C-08), period (the cycle's payroll_yymm) and condition
         * code.
         */
        String SCOPE =
                " from ExceptionEntry x left join Employee e on e.id = x.employeeId"
                        + " left join PayrollCycleInstance c on c.cycleInstanceId ="
                        + " x.cycleInstanceId where (e.locationId in :locations or"
                        + " (x.employeeId is null and c.orgUnitId in :locations))"
                        + " and (:module is null or x.moduleCode = :module)"
                        + " and (:status is null or x.status = :status)"
                        + " and (:severity is null or x.severity = :severity)"
                        + " and (:cycle is null or x.cycleInstanceId = :cycle)"
                        + " and (:period is null or c.payrollYymm = :period)"
                        + " and (:code is null or x.exceptionCode = :code)";

        /**
         * The worklist of a data scope with the {@link #SCOPE} filters. Oldest first: age is the
         * priority.
         */
        @Query(
                value = "select x" + SCOPE + " order by x.raisedAt asc, x.id asc",
                countQuery = "select count(x)" + SCOPE)
        Page<ExceptionEntry> findInScope(
                @Param("locations") Collection<Long> locations,
                @Param("module") String module,
                @Param("status") ExceptionEntry.Status status,
                @Param("severity") ExceptionEntry.Severity severity,
                @Param("cycle") Long cycle,
                @Param("period") String period,
                @Param("code") String code,
                Pageable page);

        /**
         * SRS-G-9, SRS l.634 ("Count - Members affected"): the worklist by condition (module +
         * exception code) over the whole filtered scope, one page of conditions, oldest condition
         * first (module and code break ties: one row per condition). Columns: module, code, items,
         * members, unsettled blocking-G2 items, owner low / high / count, resolvable items,
         * actionable items (unsettled and resolvable), correction screen, detail, first raised.
         */
        @Query(
                value =
                        "select x.moduleCode, x.exceptionCode, count(x), count(distinct"
                                + " x.employeeId), sum(case when x.status in :unsettled and"
                                + " x.blocksGate = 'G2' and x.severity = :blocking then 1 else 0"
                                + " end), min(x.ownerRoleCode), max(x.ownerRoleCode),"
                                + " count(x.ownerRoleCode), sum(case when x.resolvableInPayroll"
                                + " = true then 1 else 0 end), sum(case when x.status in"
                                + " :unsettled and x.resolvableInPayroll = true then 1 else 0"
                                + " end), min(x.correctionScreen), min(x.detail), min(x.raisedAt)"
                                + SCOPE
                                + " group by x.moduleCode, x.exceptionCode"
                                + " order by min(x.raisedAt) asc, x.moduleCode asc,"
                                + " x.exceptionCode asc",
                countQuery =
                        "select count(distinct concat(coalesce(x.moduleCode, '~'), '|',"
                                + " x.exceptionCode))"
                                + SCOPE)
        Page<Object[]> groupsInScope(
                @Param("locations") Collection<Long> locations,
                @Param("module") String module,
                @Param("status") ExceptionEntry.Status status,
                @Param("severity") ExceptionEntry.Severity severity,
                @Param("cycle") Long cycle,
                @Param("period") String period,
                @Param("code") String code,
                @Param("unsettled") Collection<ExceptionEntry.Status> unsettled,
                @Param("blocking") ExceptionEntry.Severity blocking,
                Pageable page);

        /** The module codes (exception classes) present in a data scope, for the filter. */
        @Query(
                "select distinct x.moduleCode from ExceptionEntry x left join Employee e on e.id ="
                        + " x.employeeId left join PayrollCycleInstance c on c.cycleInstanceId ="
                        + " x.cycleInstanceId where (e.locationId in :locations or (x.employeeId"
                        + " is null and c.orgUnitId in :locations)) and x.moduleCode is not null"
                        + " order by x.moduleCode")
        List<String> modulesInScope(@Param("locations") Collection<Long> locations);

        /**
         * The worklist tiles over the same filtered scope as {@link #findInScope} (not over one
         * page): unsettled; unsettled blocking G2; accepted; unsettled and routed upstream.
         */
        @Query(
                "select count(case when x.status in :unsettled then 1 end), count(case when"
                    + " x.status in :unsettled and x.blocksGate = 'G2' then 1 end), count(case when"
                    + " x.status = :accepted then 1 end), count(case when x.status in :unsettled"
                    + " and x.resolvableInPayroll = false then 1 end)"
                        + SCOPE)
        List<Object[]> tileCounts(
                @Param("locations") Collection<Long> locations,
                @Param("module") String module,
                @Param("status") ExceptionEntry.Status status,
                @Param("severity") ExceptionEntry.Severity severity,
                @Param("cycle") Long cycle,
                @Param("period") String period,
                @Param("code") String code,
                @Param("unsettled") Collection<ExceptionEntry.Status> unsettled,
                @Param("accepted") ExceptionEntry.Status accepted);
    }

    public interface Enablements extends Repository<FallbackEnablement, Long> {
        Optional<FallbackEnablement> findById(Long id);

        FallbackEnablement save(FallbackEnablement enablement);

        FallbackEnablement saveAndFlush(FallbackEnablement enablement);

        /** One page of every enablement, newest first (X-07 list; id breaks ties). */
        Page<FallbackEnablement> findAllByOrderByEnabledAtDescIdDesc(Pageable page);

        /** The enablements still open for an upload (the upload form's choice; a short list). */
        List<FallbackEnablement> findByStatusInOrderByEnabledAtDescIdDesc(
                Collection<FallbackEnablement.Status> statuses);

        /** Enablements of the year, for the next FBE-yyyy-nnn reference. */
        long countByReferenceStartingWith(String prefix);
    }

    public interface EnableScopes extends Repository<FallbackEnableScope, Long> {
        FallbackEnableScope save(FallbackEnableScope scope);

        List<FallbackEnableScope> findByEnablementId(Long enablementId);

        List<FallbackEnableScope> findByEnablementIdIn(Collection<Long> enablementIds);
    }

    public interface Uploads extends Repository<FallbackUpload, Long> {
        Optional<FallbackUpload> findById(Long id);

        Optional<FallbackUpload> findByApprovalId(Long approvalId);

        FallbackUpload save(FallbackUpload upload);

        FallbackUpload saveAndFlush(FallbackUpload upload);

        List<FallbackUpload> findByEnablementIdAndOrgUnitId(Long enablementId, Long orgUnitId);

        List<FallbackUpload> findByEnablementId(Long enablementId);

        /**
         * The X-07 tiles over the data scope: uploads awaiting approval, and approved uploads whose
         * reconciliation is still pending.
         */
        @Query(
                "select count(case when u.status = :pending then 1 end), count(case when u.status"
                        + " = :approved and u.reconStatus = :reconPending then 1 end) from"
                        + " FallbackUpload u where u.orgUnitId in :scope")
        List<Object[]> tileCounts(
                @Param("scope") Collection<Long> scope,
                @Param("pending") FallbackUpload.Status pending,
                @Param("approved") FallbackUpload.Status approved,
                @Param("reconPending") FallbackUpload.ReconStatus reconPending);

        /** Uploads of offices in the data scope, newest first (id breaks ties: a stable order). */
        Page<FallbackUpload> findByOrgUnitIdInOrderByUploadedAtDescIdDesc(
                Collection<Long> orgUnitIds, Pageable pageable);

        /** The uploads of one enablement in the data scope (the row picked on X-07). */
        Page<FallbackUpload> findByEnablementIdAndOrgUnitIdInOrderByUploadedAtDescIdDesc(
                Long enablementId, Collection<Long> orgUnitIds, Pageable pageable);
    }

    public interface UploadLines extends Repository<FallbackUploadLine, Long> {
        FallbackUploadLine save(FallbackUploadLine line);

        List<FallbackUploadLine> findByUploadIdOrderByLineNo(Long uploadId);

        /** One page of an upload's lines, in file order (X-07 validation result). */
        Page<FallbackUploadLine> findByUploadIdOrderByLineNo(Long uploadId, Pageable page);

        /** Rejected lines of an upload by reason, most frequent first (X-07 "Validation"). */
        @Query(
                "select l.rejectionReason, count(l) from FallbackUploadLine l where l.uploadId ="
                        + " :uploadId and l.validationStatus = 'REJECTED' group by"
                        + " l.rejectionReason order by count(l) desc, l.rejectionReason")
        List<Object[]> rejectionCounts(@Param("uploadId") Long uploadId);
    }

    public interface OffCyclePayments extends Repository<OffCyclePayment, Long> {
        Optional<OffCyclePayment> findById(Long id);

        Optional<OffCyclePayment> findByApprovalId(Long approvalId);

        OffCyclePayment save(OffCyclePayment payment);

        OffCyclePayment saveAndFlush(OffCyclePayment payment);

        /** Other ACTIVE cases of the member for the salary month (the duplicate-payment check). */
        @Query(
                "select count(o) from OffCyclePayment o where o.employeeId = :employee and"
                        + " o.salaryYymm = :yymm and o.recordStatus = 'ACTIVE' and o.id <> :self")
        long countOtherActive(
                @Param("employee") Long employee,
                @Param("yymm") String yymm,
                @Param("self") Long self);

        /** Cases of members working at {@code locations} (data scope), newest first. */
        @Query(
                "select o from OffCyclePayment o where exists (select 1 from Employee e where e.id"
                        + " = o.employeeId and e.locationId in :locations) order by o.id desc")
        Page<OffCyclePayment> findInScope(
                @Param("locations") Collection<Long> locations, Pageable page);

        /**
         * As {@link #findInScope}, of one case type (the drawn Filter of I-20; null = every type).
         */
        @Query(
                "select o from OffCyclePayment o where exists (select 1 from Employee e where e.id"
                    + " = o.employeeId and e.locationId in :locations) and (:caseType is null or"
                    + " o.caseType = :caseType) order by o.id desc")
        Page<OffCyclePayment> findInScopeOfType(
                @Param("locations") Collection<Long> locations,
                @Param("caseType") String caseType,
                Pageable page);
    }

    /** {@code supplementary_payment} (I-23). No delete: a request is cancelled (SRS-G-37). */
    public interface SupplementaryPayments extends Repository<SupplementaryPayment, Long> {
        Optional<SupplementaryPayment> findById(Long id);

        Optional<SupplementaryPayment> findByApprovalId(Long approvalId);

        SupplementaryPayment save(SupplementaryPayment payment);

        SupplementaryPayment saveAndFlush(SupplementaryPayment payment);

        /**
         * Other live I-23 payments of the member for the period that match by component, or by
         * payment type when neither names a component (BR-CYC-010 duplicate check). Rejected and
         * cancelled ones never paid anything.
         */
        @Query(
                "select s from SupplementaryPayment s where s.employeeId = :employee and"
                    + " s.forPeriodYymm = :yymm and s.id <> :self and s.status not in"
                    + " (in.gov.fci.payroll.general.business.model.SupplementaryPayment.Status.REJECTED,"
                    + " in.gov.fci.payroll.general.business.model.SupplementaryPayment.Status.CANCELLED)"
                    + " and ((:component is not null and s.componentId = :component) or (:component"
                    + " is null and s.componentId is null and s.paymentType = :type)) order by"
                    + " s.id")
        List<SupplementaryPayment> findMatching(
                @Param("employee") Long employee,
                @Param("yymm") String yymm,
                @Param("component") Long component,
                @Param("type") String type,
                @Param("self") Long self);

        /**
         * The I-23 tiles over the whole data scope: recorded; submitted to the Checker; recorded
         * and blocked by the duplicate check; rejected.
         */
        @Query(
                "select count(case when s.status = :recorded then 1 end), count(case when s.status"
                    + " = :submitted then 1 end), count(case when s.status = :recorded and"
                    + " s.duplicateCheck = :blocked then 1 end), count(case when s.status ="
                    + " :rejected then 1 end) from SupplementaryPayment s where exists (select 1"
                    + " from Employee e where e.id = s.employeeId and e.locationId in :locations)")
        List<Object[]> tileCounts(
                @Param("locations") Collection<Long> locations,
                @Param("recorded") SupplementaryPayment.Status recorded,
                @Param("submitted") SupplementaryPayment.Status submitted,
                @Param("blocked") SupplementaryPayment.DuplicateCheck blocked,
                @Param("rejected") SupplementaryPayment.Status rejected);

        /** Requests of members working at {@code locations} (data scope), newest first. */
        @Query(
                "select s from SupplementaryPayment s where exists (select 1 from Employee e where"
                        + " e.id = s.employeeId and e.locationId in :locations) order by s.id desc")
        Page<SupplementaryPayment> findInScope(
                @Param("locations") Collection<Long> locations, Pageable page);

        /**
         * As {@link #findInScope}, of one payment type (the drawn Filter of I-23; null = every
         * type).
         */
        @Query(
                "select s from SupplementaryPayment s where exists (select 1 from Employee e where"
                    + " e.id = s.employeeId and e.locationId in :locations) and (:paymentType is"
                    + " null or s.paymentType = :paymentType) order by s.id desc")
        Page<SupplementaryPayment> findInScopeOfType(
                @Param("locations") Collection<Long> locations,
                @Param("paymentType") String paymentType,
                Pageable page);
    }
}
