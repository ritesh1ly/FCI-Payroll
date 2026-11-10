package in.gov.fci.payroll.iam.security;

import in.gov.fci.payroll.iam.orchestration.SecurityOffences;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * OD-130: the single point where a refusal has really reached the client. It runs first of the
 * exception resolvers, once the request's own transaction has rolled back, acts on the offence and
 * answers nothing itself (null): the response is exactly the one the normal handlers write.
 *
 * <p>An offence is a {@link FciException#asAccessOffence() marked} refusal or the method-security
 * denial of an {@code @authz} expression, for a browser session. So a refusal a service catches
 * (one item of a bulk decision), a CSRF refusal (the filter chain's own handler), a 401, a
 * validation error, READ_ONLY_CONSOLIDATED, a step-up and the HRMS machine API (no browser session)
 * are never offences. Requests run the resolver once, and an error dispatch is ignored.
 *
 * <p>A 403 tells the client at once ({@value #SESSION_ENDED_HEADER}). A 404 stays byte for byte the
 * answer of a record that does not exist: the next call's 401 says why the session ended.
 */
@Component
@Profile("online")
public class AccessOffenceResolver implements HandlerExceptionResolver, Ordered {

    /** Set on a refused response when the session was ended by it. */
    public static final String SESSION_ENDED_HEADER = "X-FCI-Session-Ended";

    static final String HANDLED = AccessOffenceResolver.class.getName() + ".handled";
    private static final String NO_GATE = "-";

    private final SecurityOffences offences;

    public AccessOffenceResolver(SecurityOffences offences) {
        this.offences = offences;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        if (request.getDispatcherType() != DispatcherType.REQUEST
                || request.getAttribute(HANDLED) != null
                || !isOffence(ex)) {
            return null;
        }
        Optional<SessionUser> user = WebSessions.user(request);
        if (user.isEmpty()) {
            return null;
        }
        request.setAttribute(HANDLED, Boolean.TRUE);
        ErrorCode code =
                ex instanceof FciException refused
                        ? refused.getErrorCode()
                        : CommonErrorCode.ACCESS_DENIED;
        Object[] gate = ex instanceof FciException refused ? refused.getMessageArgs() : null;
        SecurityOffences.Outcome outcome =
                offences.handle(
                        new SecurityOffences.Refusal(
                                user.get().userId(),
                                SecurityOffences.sessionRef(user.get().sessionId()),
                                request.getMethod(),
                                pathPattern(request),
                                gatePart(gate, 0),
                                gatePart(gate, 1),
                                code.code()));
        if (outcome.sessionEnded() && code.status() != HttpStatus.NOT_FOUND) {
            response.setHeader(SESSION_ENDED_HEADER, ApiErrorCode.SESSION_ENDED_OFFENCE.code());
        }
        return null;
    }

    private static boolean isOffence(Exception ex) {
        return ex instanceof AccessDeniedException
                || (ex instanceof FciException refused && refused.isAccessOffence());
    }

    /** The route, never the values in it: {@code /v1/employees/{eis}}. */
    private static String pathPattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? "unmatched" : pattern.toString();
    }

    private static String gatePart(Object[] gate, int index) {
        return gate != null && gate.length > index ? String.valueOf(gate[index]) : NO_GATE;
    }
}
