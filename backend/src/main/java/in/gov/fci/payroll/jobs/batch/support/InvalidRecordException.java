package in.gov.fci.payroll.jobs.batch.support;

/**
 * A record that cannot be processed because of its own data (target DDL failure class MEMBER_DATA):
 * the record is skipped and logged, and the run continues. Skippable up to the skip limit.
 */
public class InvalidRecordException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidRecordException(String message) {
        super(message);
    }
}
