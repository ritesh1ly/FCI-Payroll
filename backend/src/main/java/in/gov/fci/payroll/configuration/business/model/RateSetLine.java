package in.gov.fci.payroll.configuration.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code rate_set_line}: One rate of a rate set, keyed by designation, category, pay pattern, Basic
 * slab and city class.
 */
@Entity
@Table(name = "rate_set_line")
public class RateSetLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rate_line_id")
    private Long rateLineId;

    @Column(name = "rate_set_id", nullable = false)
    private Long rateSetId;

    @Column(name = "designation_id")
    private Long designationId;

    @Column(name = "category_code", length = 10)
    private String categoryCode;

    @Column(name = "pay_pattern", length = 10)
    private String payPattern;

    @Column(name = "basic_slab_from", precision = 12, scale = 2)
    private BigDecimal basicSlabFrom;

    @Column(name = "basic_slab_to", precision = 12, scale = 2)
    private BigDecimal basicSlabTo;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "city_class", length = 1)
    private String cityClass;

    @Column(name = "rate_code", nullable = false, length = 40)
    private String rateCode;

    @Column(name = "rate_value", nullable = false, precision = 14, scale = 4)
    private BigDecimal rateValue;

    @Column(name = "unit_of_measure", length = 20)
    private String unitOfMeasure;

    protected RateSetLine() {}

    public static RateSetLine of(
            Long rateSetId,
            Long designationId,
            String categoryCode,
            String payPattern,
            BigDecimal basicSlabFrom,
            BigDecimal basicSlabTo,
            String cityClass,
            String rateCode,
            BigDecimal rateValue,
            String unitOfMeasure) {
        RateSetLine line = new RateSetLine();
        line.rateSetId = rateSetId;
        line.designationId = designationId;
        line.categoryCode = categoryCode;
        line.payPattern = payPattern;
        line.basicSlabFrom = basicSlabFrom;
        line.basicSlabTo = basicSlabTo;
        line.cityClass = cityClass;
        line.rateCode = rateCode;
        line.rateValue = rateValue;
        line.unitOfMeasure = unitOfMeasure;
        return line;
    }

    public Long getRateLineId() {
        return rateLineId;
    }

    public Long getRateSetId() {
        return rateSetId;
    }

    public Long getDesignationId() {
        return designationId;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public String getPayPattern() {
        return payPattern;
    }

    public BigDecimal getBasicSlabFrom() {
        return basicSlabFrom;
    }

    public BigDecimal getBasicSlabTo() {
        return basicSlabTo;
    }

    public String getCityClass() {
        return cityClass;
    }

    public String getRateCode() {
        return rateCode;
    }

    public BigDecimal getRateValue() {
        return rateValue;
    }

    public String getUnitOfMeasure() {
        return unitOfMeasure;
    }
}
