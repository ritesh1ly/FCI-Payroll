package in.gov.fci.payroll.payroll.business;

/**
 * An employee's own data makes the pay rule inapplicable (target DDL failure class MEMBER_DATA).
 * Whoever runs the rule decides what follows; the batch jobs skip and log the employee.
 */
public class PayDataException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Where the failed value is held and corrected, for the X-05 worklist (SRS-G-9: "the field
     * involved, the source of the value and the screen where it must be corrected").
     */
    public record Where(String entityName, String fieldName, String fieldSource, String screen) {}

    /** A rate of an M-04 rate set not seeded (R-RULE: "not configured", never a default). */
    public static final Where RATE_SET = new Where("rate_set_line", "rate_value", "RULE", "M-04");

    private final transient Where where;

    public PayDataException(String message) {
        this(message, null);
    }

    public PayDataException(String message, Where where) {
        super(message);
        this.where = where;
    }

    /** Where the value is corrected, or null when the rule does not know it. */
    public Where where() {
        return where;
    }
}
