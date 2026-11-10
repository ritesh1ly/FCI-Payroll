package in.gov.fci.payroll.tax.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code report_field_binding}: one field of a statutory-output version, bound to the component
 * catalogue, the field-source model, an aggregate, a literal or a derived expression (target DDL
 * v4.0 Section 24; BR-TAX-013, {@code chk_report_binding_resolves}).
 */
@Entity
@Table(name = "report_field_binding")
public class OutputField {

    /** {@code binding_type}. */
    public enum Binding {
        COMPONENT,
        FIELD_SOURCE,
        AGGREGATE,
        LITERAL,
        DERIVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "binding_id")
    private Long bindingId;

    @Column(name = "report_version_id", nullable = false)
    private Long reportVersionId;

    @Column(name = "field_order", nullable = false)
    private Integer fieldOrder;

    @Column(name = "field_label", nullable = false, length = 200)
    private String fieldLabel;

    @Column(name = "binding_type", nullable = false, length = 20)
    private String bindingType;

    @Column(name = "component_id")
    private Long componentId;

    @Column(name = "field_source_id")
    private Long fieldSourceId;

    @Column(name = "aggregate_function", length = 20)
    private String aggregateFunction;

    @Column(name = "derived_expression", length = 1000)
    private String derivedExpression;

    @Column(name = "format_mask", length = 60)
    private String formatMask;

    protected OutputField() {}

    @SuppressWarnings("java:S107") // the DDL row, one argument per column
    public static OutputField of(
            Long versionId,
            int order,
            String label,
            Binding binding,
            Long componentId,
            Long fieldSourceId,
            String aggregate,
            String expression,
            String format) {
        OutputField f = new OutputField();
        f.reportVersionId = versionId;
        f.fieldOrder = order;
        f.fieldLabel = label;
        f.bindingType = binding.name();
        f.componentId = componentId;
        f.fieldSourceId = fieldSourceId;
        f.aggregateFunction = aggregate;
        f.derivedExpression = expression;
        f.formatMask = format;
        return f;
    }

    public Binding binding() {
        return Binding.valueOf(bindingType);
    }

    public int getFieldOrder() {
        return fieldOrder;
    }

    public Long getReportVersionId() {
        return reportVersionId;
    }

    public String getFieldLabel() {
        return fieldLabel;
    }

    public Long getComponentId() {
        return componentId;
    }

    public Long getFieldSourceId() {
        return fieldSourceId;
    }

    public String getAggregateFunction() {
        return aggregateFunction;
    }

    public String getDerivedExpression() {
        return derivedExpression;
    }

    public String getFormatMask() {
        return formatMask;
    }
}
