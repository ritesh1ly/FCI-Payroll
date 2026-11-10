package in.gov.fci.payroll.tax.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.type.YesNoConverter;

/**
 * {@code pt_state_master}: one state for Professional Tax (target DDL v4.0 Section 2; SRS-7-4,
 * BR-TAX-006). One row per state ({@code uk_pt_state}): an approved change updates the row and its
 * old and new values are audited field by field. The slabs are the band set of the {@code PT.SLAB}
 * rule qualified by the state code (plan 08 D-7).
 */
@Entity
@Table(name = "pt_state_master")
public class PtState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pt_state_id")
    private Long ptStateId;

    @Column(name = "state_code", nullable = false, length = 10)
    private String stateCode;

    @Column(name = "state_name", nullable = false, length = 100)
    private String stateName;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "pt_applicable_flag", nullable = false, length = 1)
    private Boolean ptApplicable;

    @Column(name = "registration_number", length = 50)
    private String registrationNumber;

    @Column(name = "slab_rule_key", length = 100)
    private String slabRuleKey;

    @Column(name = "return_periodicity", length = 20)
    private String returnPeriodicity;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    protected PtState() {}

    /** A new state as the Rule Approver approved it; its values follow through {@link #apply}. */
    public static PtState of(String code) {
        PtState s = new PtState();
        s.stateCode = code;
        return s;
    }

    /** The approved values (the state code never changes). */
    @SuppressWarnings("java:S107") // the DDL row, one argument per column
    public void apply(
            String name,
            boolean applicable,
            String registration,
            String slabKey,
            String periodicity,
            LocalDate from,
            LocalDate to) {
        stateName = name;
        ptApplicable = applicable;
        registrationNumber = registration;
        slabRuleKey = slabKey;
        returnPeriodicity = periodicity;
        effectiveFrom = from;
        effectiveTo = to;
    }

    /** The audited fields as the M-18 card shows them (SRS-20-2: one audit row per change). */
    public Map<String, String> image() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("state_name", stateName);
        m.put("pt_applicable_flag", isPtApplicable() ? "Y" : "N");
        m.put("registration_number", registrationNumber);
        m.put("slab_rule_key", slabRuleKey);
        m.put("return_periodicity", returnPeriodicity);
        m.put("effective_from", effectiveFrom == null ? null : effectiveFrom.toString());
        m.put("effective_to", effectiveTo == null ? null : effectiveTo.toString());
        return m;
    }

    /** In force on {@code day}. */
    public boolean inForceOn(LocalDate day) {
        return !effectiveFrom.isAfter(day) && (effectiveTo == null || !effectiveTo.isBefore(day));
    }

    public Long getPtStateId() {
        return ptStateId;
    }

    public String getStateCode() {
        return stateCode;
    }

    public String getStateName() {
        return stateName;
    }

    public boolean isPtApplicable() {
        return Boolean.TRUE.equals(ptApplicable);
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public String getSlabRuleKey() {
        return slabRuleKey;
    }

    public String getReturnPeriodicity() {
        return returnPeriodicity;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }
}
