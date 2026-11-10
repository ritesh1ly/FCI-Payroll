package in.gov.fci.payroll.configuration.persistence;

import in.gov.fci.payroll.configuration.business.model.AllowanceCeilingNorm;
import in.gov.fci.payroll.configuration.business.model.CircularOrder;
import in.gov.fci.payroll.configuration.business.model.CircularOrderLink;
import in.gov.fci.payroll.configuration.business.model.CircularOrderScope;
import in.gov.fci.payroll.configuration.business.model.GlAccountMapping;
import in.gov.fci.payroll.configuration.business.model.LabelMaster;
import in.gov.fci.payroll.configuration.business.model.NetPayFloorRule;
import in.gov.fci.payroll.configuration.business.model.OperationMaster;
import in.gov.fci.payroll.configuration.business.model.OtherParameter;
import in.gov.fci.payroll.configuration.business.model.PayComponent;
import in.gov.fci.payroll.configuration.business.model.RateSet;
import in.gov.fci.payroll.configuration.business.model.RateSetLine;
import in.gov.fci.payroll.configuration.business.model.ReferenceValue;
import in.gov.fci.payroll.configuration.business.model.RuleDefinition;
import in.gov.fci.payroll.configuration.business.model.RuleExpressionGrammar;
import in.gov.fci.payroll.configuration.business.model.RuleSettingBound;
import in.gov.fci.payroll.configuration.business.model.RuleSlabBand;
import in.gov.fci.payroll.configuration.business.model.RuleVersion;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Target DDL v4.0: the rule engine (S06; A-07 setting bounds), reference values (S02), rate sets
 * (S06), other parameters (S19), pay components (S07) and their GL mapping (S23).
 */
public final class ConfigurationRepositories {

    private ConfigurationRepositories() {}

    public interface Rules extends Repository<RuleDefinition, Long> {
        List<RuleDefinition> findByTypeCodeOrderByKey(String typeCode);

        Optional<RuleDefinition> findByKey(String key);

        /** The rules of one domain (the engine pins the COMPUTATION rules for an Actual Run). */
        List<RuleDefinition> findByDomain(RuleDefinition.Domain domain);

        /** The rules of the domains M-08 lists (SRS-19-1), in key order. */
        List<RuleDefinition> findByDomainInOrderByKey(Collection<RuleDefinition.Domain> domains);

        /**
         * The rule, locked for the rest of the transaction (KB RE-2: the temporal trigger alone is
         * not safe under concurrency, so a proposal and an approval serialise on the rule row).
         */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT r FROM RuleDefinition r WHERE r.id = :id")
        Optional<RuleDefinition> lockById(@Param("id") Long id);
    }

    public interface SettingBounds extends Repository<RuleSettingBound, Long> {
        List<RuleSettingBound> findByRuleIdIn(Collection<Long> ruleIds);
    }

    public interface RuleVersions extends Repository<RuleVersion, Long> {
        List<RuleVersion> findByRuleIdInAndState(Collection<Long> ruleIds, RuleVersion.State state);

        List<RuleVersion> findByRuleIdOrderByVersionNoDesc(Long ruleId);

        Optional<RuleVersion> findByApprovalId(Long approvalId);

        /** The versions linked to a circular (SRS-19-17: from a circular, every linked version). */
        List<RuleVersion> findByCircularOrderIdOrderByRuleIdAscVersionNoDesc(Long circularId);

        RuleVersion save(RuleVersion version);

        /**
         * Writes pending changes now: the end of a version must reach the database before its
         * successor's approval.
         */
        void flush();
    }

    /** Slab bands of rule versions (Module 06; SRS-19-5). */
    public interface RuleSlabBands extends Repository<RuleSlabBand, Long> {
        List<RuleSlabBand> findByRuleVersionIdIn(Collection<Long> ruleVersionIds);

        RuleSlabBand save(RuleSlabBand band);
    }

    /** The bounded formula grammar (Module 06; SRS-19-15). */
    public interface Grammar extends Repository<RuleExpressionGrammar, Long> {
        List<RuleExpressionGrammar> findByPermitted(Boolean permitted);
    }

    /**
     * M-16 reference values of a list, by list code (the master catalogues of M-04, M-22, M-23).
     */
    public interface ReferenceValues extends Repository<ReferenceValue, Long> {
        @Query(
                "select v from ReferenceValue v, ReferenceList l where v.listId = l.listId"
                        + " and l.listCode in :lists order by l.listCode, v.sortOrder")
        List<ReferenceValue> findByListCodes(@Param("lists") Collection<String> listCodes);

        @Query("select l.listCode from ReferenceList l where l.listId = :id")
        Optional<String> listCodeOf(@Param("id") Long listId);
    }

    /** Rate sets (M-04, M-22) and their lines. */
    public interface RateSets extends Repository<RateSet, Long> {
        List<RateSet> findByRateSetCodeInOrderByRateSetCodeAscVersionNoDesc(
                Collection<String> codes);

        List<RateSet> findByRateSetCodeOrderByVersionNoDesc(String code);

        /** M-25 where used: the rate set versions a circular drives. */
        List<RateSet> findByCircularOrderIdOrderByRateSetCodeAscVersionNoDesc(Long circularId);

        /** M-25 list: the rate set versions driven by any of these circulars (the count). */
        List<RateSet> findByCircularOrderIdIn(Collection<Long> circularIds);

        Optional<RateSet> findByRateSetId(Long id);

        Optional<RateSet> findByApprovalId(Long approvalId);

        RateSet save(RateSet set);

        void flush();
    }

    public interface RateSetLines extends Repository<RateSetLine, Long> {
        List<RateSetLine> findByRateSetIdIn(Collection<Long> rateSetIds);

        RateSetLine save(RateSetLine line);
    }

    /** M-23 other parameters (one row per version). */
    public interface OtherParameters extends Repository<OtherParameter, Long> {
        List<OtherParameter> findByParameterCodeInOrderByParameterCodeAscVersionNoDesc(
                Collection<String> codes);

        List<OtherParameter> findByParameterCodeOrderByVersionNoDesc(String code);

        Optional<OtherParameter> findByApprovalId(Long approvalId);

        OtherParameter save(OtherParameter parameter);

        void flush();
    }

    /** M-07 pay components (read here) and M-24 their GL mapping. */
    public interface PayComponents extends Repository<PayComponent, Long> {
        List<PayComponent> findAllByOrderByLegacyPayCode();

        Optional<PayComponent> findByLegacyPayCode(Integer payCode);

        Optional<PayComponent> findById(Long id);

        List<PayComponent> findByLegacyPayCodeIn(Collection<Integer> payCodes);

        PayComponent save(PayComponent component);
    }

    public interface GlMappings extends Repository<GlAccountMapping, Long> {
        List<GlAccountMapping> findByComponentIdInOrderByComponentIdAscVersionNoDesc(
                Collection<Long> componentIds);

        List<GlAccountMapping> findByComponentIdOrderByVersionNoDesc(Long componentId);

        Optional<GlAccountMapping> findByApprovalId(Long approvalId);

        GlAccountMapping save(GlAccountMapping mapping);

        void flush();
    }

    /** M-14 net-pay floors (one row per version; no version number in the DDL). */
    public interface NetPayFloors extends Repository<NetPayFloorRule, Long> {
        List<NetPayFloorRule> findAllByOrderByPayrollTypeAscEffectiveFromDesc();

        List<NetPayFloorRule> findByPayrollTypeOrderByEffectiveFromDesc(String payrollType);

        Optional<NetPayFloorRule> findByApprovalId(Long approvalId);

        NetPayFloorRule save(NetPayFloorRule rule);

        void flush();
    }

    /** M-15 allowance and reimbursement ceiling norms. */
    public interface CeilingNorms extends Repository<AllowanceCeilingNorm, Long> {
        List<AllowanceCeilingNorm> findAllByOrderByClaimTypeAscVersionNoDesc();

        List<AllowanceCeilingNorm> findByClaimTypeOrderByVersionNoDesc(String claimType);

        Optional<AllowanceCeilingNorm> findByApprovalId(Long approvalId);

        AllowanceCeilingNorm save(AllowanceCeilingNorm norm);

        void flush();
    }

    /** M-27 operations (one row per version). */
    public interface Operations extends Repository<OperationMaster, Long> {
        List<OperationMaster> findAllByOrderByOperationCodeAscVersionNoDesc();

        List<OperationMaster> findByOperationCodeOrderByVersionNoDesc(String code);

        /** M-25 where used: the operation versions a circular governs. */
        List<OperationMaster> findByCircularOrderIdOrderByOperationCodeAscVersionNoDesc(
                Long circularId);

        /** M-25 list: the operation versions governed by any of these circulars (the count). */
        List<OperationMaster> findByCircularOrderIdIn(Collection<Long> circularIds);

        Optional<OperationMaster> findByApprovalId(Long approvalId);

        OperationMaster save(OperationMaster operation);

        void flush();
    }

    /** M-25 circulars, their scope rows and their links (SRS-G-38). */
    public interface Circulars extends Repository<CircularOrder, Long> {
        Optional<CircularOrder> findById(Long id);

        Optional<CircularOrder> findByCircularNumber(String number);

        List<CircularOrder> findByCircularOrderIdIn(Collection<Long> ids);

        Optional<CircularOrder> findByApprovalId(Long approvalId);

        List<CircularOrder> findBySupersedesCircularId(Long id);

        @Query(
                "select c from CircularOrder c where (:text is null"
                        + " or lower(c.circularNumber) like :text escape '\\'"
                        + " or lower(c.subject) like :text escape '\\')"
                        + " and (:category is null or c.categoryCode = :category)"
                        + " and (:status is null or c.circularStatus = :status)"
                        + " and (:fromDate is null or c.circularDate >= :fromDate)"
                        + " and (:toDate is null or c.circularDate <= :toDate)"
                        + " order by c.circularDate desc, c.circularOrderId desc")
        List<CircularOrder> search(
                @Param("text") String likeText,
                @Param("category") String category,
                @Param("status") String status,
                @Param("fromDate") LocalDate from,
                @Param("toDate") LocalDate to);

        CircularOrder save(CircularOrder circular);

        void flush();
    }

    public interface CircularScopes extends Repository<CircularOrderScope, Long> {
        List<CircularOrderScope> findByCircularOrderIdIn(Collection<Long> circularIds);

        CircularOrderScope save(CircularOrderScope scope);
    }

    public interface CircularLinks extends Repository<CircularOrderLink, Long> {
        List<CircularOrderLink> findByCircularOrderIdOrderByLinkedAtDesc(Long circularId);

        /** M-25 list: the links of any of these circulars (the count). */
        List<CircularOrderLink> findByCircularOrderIdIn(Collection<Long> circularIds);

        /** The links made to rows of {@code entity} (e.g. every M-15 norm version, US-GAP-211). */
        List<CircularOrderLink> findByLinkedEntity(String entity);

        /** The links made to one row, oldest first (Wave D review 6: cancelled when it fails). */
        List<CircularOrderLink> findByLinkedEntityAndLinkedKeyOrderByCircularLinkId(
                String entity, String key);

        CircularOrderLink save(CircularOrderLink link);
    }

    /** M-26 labels (one row per version of a key, language and scope). */
    public interface Labels extends Repository<LabelMaster, Long> {
        Optional<LabelMaster> findById(Long id);

        Optional<LabelMaster> findByApprovalId(Long approvalId);

        List<LabelMaster> findByLabelKeyOrderByVersionNoDesc(String key);

        List<LabelMaster> findByLabelKeyIn(Collection<String> keys);

        /** SRS-G-39: every label version of the values of one master (local names included). */
        List<LabelMaster> findByMasterEntity(String masterEntity);

        @Query(
                "select l from LabelMaster l where (:text is null"
                        + " or lower(l.labelKey) like :text escape '\\'"
                        + " or lower(l.labelText) like :text escape '\\')"
                        + " and (:language is null or l.languageCode = :language)"
                        + " and (:scope is null or l.scopeLevel = :scope)"
                        + " and (:status is null or l.labelStatus = :status)"
                        + " order by l.labelKey, l.languageCode, l.scopeLevel, l.versionNo desc")
        List<LabelMaster> search(
                @Param("text") String likeText,
                @Param("language") String language,
                @Param("scope") String scope,
                @Param("status") String status,
                Pageable page);

        /** Keys with an Active English Global label and no Active or pending Hindi Global one. */
        @Query(
                "select e from LabelMaster e where e.languageCode = 'EN' and e.scopeLevel ="
                    + " 'GLOBAL' and e.labelStatus = 'ACTIVE' and not exists (select h from"
                    + " LabelMaster h where h.labelKey = e.labelKey and h.languageCode = 'HI' and"
                    + " h.scopeLevel = 'GLOBAL' and h.labelStatus = 'ACTIVE') order by e.labelKey")
        List<LabelMaster> missingHindi(Pageable page);

        LabelMaster save(LabelMaster label);

        void flush();
    }
}
