package in.gov.fci.payroll.organisation.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code pay_scale_band} (target DDL S02): the pay scale code shown on M-01 (E-4, L-9 …). */
@Entity
@Table(name = "pay_scale_band")
public class PayScaleBand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "band_id")
    private Long id;

    @Column(name = "band_code", nullable = false, length = 20)
    private String code;

    @Column(name = "band_name", nullable = false, length = 100)
    private String name;

    @Column(name = "scale_type", length = 10)
    private String scaleType;

    protected PayScaleBand() {}

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getScaleType() {
        return scaleType;
    }
}
