package in.gov.fci.payroll.audit.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.type.YesNoConverter;

/**
 * {@code retention_class} (target DDL v4.0 §2, SRS-G-14, SRS-20-5): how long a class of records is
 * kept. {@code is_confirmed = N} is a placeholder, never a decision: no purge may run against it
 * (and the application has none, R-DEL). The period changes only through an approved A-07 proposal.
 */
@Entity
@Table(name = "retention_class")
public class RetentionClass {

    @Id
    @Column(name = "retention_class_id")
    private Long id;

    @Column(name = "class_code", nullable = false, length = 40)
    private String code;

    @Column(name = "class_name", nullable = false, length = 150)
    private String name;

    @Column(name = "retention_months")
    private Integer months;

    @Column(name = "retention_basis", length = 200)
    private String basis;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_indefinite", nullable = false, length = 1)
    private Boolean indefinite;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_confirmed", nullable = false, length = 1)
    private Boolean confirmed;

    @Column(name = "archival_action", nullable = false, length = 20)
    private String archivalAction;

    protected RetentionClass() {}

    /**
     * SRS-20-5: applies an approved period - a number of months or indefinite - with the instrument
     * that sets it; the period is confirmed from then on.
     */
    public void setPeriod(Integer months, boolean indefinite, String basis) {
        this.months = indefinite ? null : months;
        this.indefinite = indefinite;
        this.basis = basis;
        this.confirmed = Boolean.TRUE;
    }

    /** The fields an audit row compares (SRS-20-2), as the screen shows them. */
    public Map<String, String> image() {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("retention_months", months == null ? null : String.valueOf(months));
        image.put("is_indefinite", Boolean.TRUE.equals(indefinite) ? "Y" : "N");
        image.put("retention_basis", basis);
        image.put("is_confirmed", Boolean.TRUE.equals(confirmed) ? "Y" : "N");
        return image;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Integer getMonths() {
        return months;
    }

    public String getBasis() {
        return basis;
    }

    public boolean isIndefinite() {
        return Boolean.TRUE.equals(indefinite);
    }

    public boolean isConfirmed() {
        return Boolean.TRUE.equals(confirmed);
    }

    public String getArchivalAction() {
        return archivalAction;
    }
}
