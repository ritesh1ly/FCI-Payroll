package in.gov.fci.payroll.configuration.business.model;

import in.gov.fci.payroll.configuration.business.SlabBands;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * {@code rule_slab_band}: one band of a Slab rule version (target DDL v4.0 S06; SRS-19-5,
 * BR-RULE-003). A band set must be contiguous and non-overlapping, checked as a set by {@link
 * SlabBands#validate} on submit.
 */
@Entity
@Table(name = "rule_slab_band")
public class RuleSlabBand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "slab_band_id")
    private Long id;

    @Column(name = "rule_version_id", nullable = false)
    private Long ruleVersionId;

    @Column(name = "band_order", nullable = false)
    private Integer bandOrder;

    @Column(name = "range_from", nullable = false, precision = 18, scale = 4)
    private BigDecimal rangeFrom;

    @Column(name = "range_to", precision = 18, scale = 4)
    private BigDecimal rangeTo;

    @Column(name = "rate_or_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal rateOrAmount;

    @Column(name = "step_size", precision = 18, scale = 4)
    private BigDecimal stepSize;

    @Column(name = "band_qualifier", length = 40)
    private String bandQualifier;

    protected RuleSlabBand() {}

    public RuleSlabBand(Long ruleVersionId, SlabBands.Band band) {
        this.ruleVersionId = ruleVersionId;
        this.bandOrder = band.order();
        this.rangeFrom = band.from();
        this.rangeTo = band.to();
        this.rateOrAmount = band.rateOrAmount();
        this.stepSize = band.step();
        this.bandQualifier = band.qualifier();
    }

    /** The band as the pure slab rules read it. */
    public SlabBands.Band band() {
        return new SlabBands.Band(
                bandOrder, rangeFrom, rangeTo, rateOrAmount, stepSize, bandQualifier);
    }

    public Long getId() {
        return id;
    }

    public Long getRuleVersionId() {
        return ruleVersionId;
    }
}
