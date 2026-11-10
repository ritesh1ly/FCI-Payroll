package in.gov.fci.payroll.platform.error;

import java.util.List;
import java.util.Objects;

/**
 * Base exception for expected, catalogued failures. Its message is the error code only, so it can
 * never carry request data into logs; the user-facing text comes from the {@link ErrorCode}.
 */
public class FciException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient Object[] messageArgs;
    private final transient List<Violation> violations;
    private boolean accessOffence;

    public FciException(ErrorCode errorCode) {
        this(errorCode, null, List.of(), null);
    }

    public FciException(ErrorCode errorCode, Throwable cause) {
        this(errorCode, null, List.of(), cause);
    }

    public FciException(ErrorCode errorCode, List<Violation> violations) {
        this(errorCode, null, violations, null);
    }

    /**
     * @param messageArgs arguments for the localised detail message; must be developer-controlled
     *     values (limits, counts), never request input
     */
    public FciException(
            ErrorCode errorCode,
            Object[] messageArgs,
            List<Violation> violations,
            Throwable cause) {
        super(Objects.requireNonNull(errorCode, "errorCode").code(), cause);
        this.errorCode = errorCode;
        this.messageArgs = messageArgs == null ? new Object[0] : messageArgs.clone();
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    /**
     * OD-130: this refusal is an access offence of the signed-in user, whatever it answers (a
     * record outside the scope stays a 404). On the exception, not the request: a refusal a service
     * catches and handles (a bulk decision) is no refused call. Only the access policy marks one.
     */
    public FciException asAccessOffence() {
        this.accessOffence = true;
        return this;
    }

    public boolean isAccessOffence() {
        return accessOffence;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object[] getMessageArgs() {
        return messageArgs.clone();
    }

    public List<Violation> getViolations() {
        return violations;
    }
}
