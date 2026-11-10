package in.gov.fci.payroll.general.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code hrms_fallback_enable_scope} (target DDL v4.0 S08): one office an enablement covers (X-07
 * "Offices"; SRS-INT-22 "a named cycle and set of offices"). An upload from an office not listed is
 * refused (SRS-EH-11).
 */
@Entity
@Table(name = "hrms_fallback_enable_scope")
public class FallbackEnableScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "enable_scope_id")
    private Long id;

    @Column(name = "enablement_id", nullable = false)
    private Long enablementId;

    @Column(name = "org_unit_id", nullable = false)
    private Long orgUnitId;

    /** The office's cycle instance for the month, if it was already initiated when enabled. */
    @Column(name = "cycle_instance_id")
    private Long cycleInstanceId;

    protected FallbackEnableScope() {}

    public static FallbackEnableScope of(Long enablementId, Long orgUnitId, Long cycleInstanceId) {
        FallbackEnableScope s = new FallbackEnableScope();
        s.enablementId = enablementId;
        s.orgUnitId = orgUnitId;
        s.cycleInstanceId = cycleInstanceId;
        return s;
    }

    public Long getId() {
        return id;
    }

    public Long getEnablementId() {
        return enablementId;
    }

    public Long getOrgUnitId() {
        return orgUnitId;
    }

    public Long getCycleInstanceId() {
        return cycleInstanceId;
    }
}
