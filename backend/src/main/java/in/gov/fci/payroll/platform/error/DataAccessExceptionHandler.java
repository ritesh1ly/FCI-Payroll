package in.gov.fci.payroll.platform.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps persistence failures to problem responses without exposing the database message (which may
 * contain the duplicate value or collection names). A refusal by an immutability trigger of the
 * audit trail or the Legacy Audit table (FCIPAY-AUD-001 / 002) is answered AUD-409-IMMUTABLE and
 * published as a {@link HistoryTamperAttempt}, which the audit module logs as a security event
 * (SRS-20-19, SRS-20-21).
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class DataAccessExceptionHandler {

    private final ProblemResponder responder;
    private final ApplicationEventPublisher events;

    public DataAccessExceptionHandler(
            ProblemResponder responder, ApplicationEventPublisher events) {
        this.responder = responder;
        this.events = events;
    }

    /** The immutability refusal in {@code ex}, answered and published; else null. */
    private ResponseEntity<Object> tamper(Exception ex, HttpServletRequest request) {
        String code = HistoryTamperAttempt.codeIn(ex);
        if (code == null) {
            return null;
        }
        events.publishEvent(
                new HistoryTamperAttempt(code, request.getMethod(), request.getRequestURI()));
        return responder.respond(ex, ApiErrorCode.AUDIT_TAMPER_REFUSED, request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(
            OptimisticLockingFailureException ex, HttpServletRequest request) {
        return responder.respond(ex, CommonErrorCode.PRECONDITION_FAILED, request);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Object> handleDuplicate(
            DuplicateKeyException ex, HttpServletRequest request) {
        return responder.respond(ex, CommonErrorCode.DUPLICATE, request);
    }

    /**
     * Constraint violations not translated to {@link DuplicateKeyException} (for example a JPA
     * unique or foreign-key violation). Reported generically: the message names tables and values.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        ResponseEntity<Object> refused = tamper(ex, request);
        return refused != null ? refused : responder.respond(ex, CommonErrorCode.CONFLICT, request);
    }

    /**
     * Any other persistence failure (a trigger's application error surfaces as an uncategorised SQL
     * error, or at commit as a transaction failure): an immutability refusal, else a server error.
     */
    @ExceptionHandler({DataAccessException.class, TransactionSystemException.class})
    public ResponseEntity<Object> handleOther(Exception ex, HttpServletRequest request) {
        ResponseEntity<Object> refused = tamper(ex, request);
        return refused != null
                ? refused
                : responder.respond(ex, CommonErrorCode.INTERNAL_ERROR, request);
    }

    @ExceptionHandler({
        DataAccessResourceFailureException.class,
        QueryTimeoutException.class,
        TransientDataAccessException.class
    })
    public ResponseEntity<Object> handleUnavailable(Exception ex, HttpServletRequest request) {
        return responder.respond(ex, CommonErrorCode.SERVICE_UNAVAILABLE, request);
    }
}
