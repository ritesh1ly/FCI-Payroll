package in.gov.fci.payroll.iam.security;

import in.gov.fci.payroll.iam.orchestration.AccessPolicy;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authorisation before input processing (OWASP ASVS V4.1.1 / V4.1.5, deny by default; security
 * review 2026-11-04): the screen-and-action gate of an endpoint's {@code @PreAuthorize} is checked
 * before Spring MVC reads, converts and validates the request.
 *
 * <p>{@code @PreAuthorize} itself runs only when the controller method is called, i.e. after the
 * arguments are resolved. A caller without the permission therefore used to receive 400 with the
 * names and rules of the body's fields (bean validation), a type-conversion error, or a 404 an
 * argument resolver raised, instead of the 403: the request shape of every endpoint could be
 * learned without holding it, and unauthorised input was parsed. This interceptor runs first and
 * answers exactly what the gate answers (AUTHZ-403-PERMISSION with screen and action,
 * AUTH-403-STEP-UP; {@code @authz.can} false is the generic 403). The method-security gate stays in
 * place behind it (defence in depth), so an endpoint is never less protected than before.
 *
 * <p>Only the static gate forms are evaluated here: {@code @authz.gate('S','A')}, {@code
 * @authz.gateEither('S','A','T','B')} and {@code @authz.can('S','A')}. An expression that reads
 * the request ({@code #request.screen()}) or any other form is left to method security alone. The
 * parsed gate of each handler method is kept, so the annotation is read once.
 */
public class GateFirstInterceptor implements HandlerInterceptor {

    private static final String Q = "\\s*'([A-Z]-\\d{2})'\\s*";
    private static final String A = "\\s*'([A-Z_]+)'\\s*";
    private static final Pattern GATE = Pattern.compile("^@authz\\.gate\\(" + Q + "," + A + "\\)$");
    private static final Pattern EITHER =
            Pattern.compile("^@authz\\.gateEither\\(" + Q + "," + A + "," + Q + "," + A + "\\)$");
    private static final Pattern CAN = Pattern.compile("^@authz\\.can\\(" + Q + "," + A + "\\)$");

    /** A static gate: the permission(s) an endpoint needs. */
    sealed interface Gate permits Single, Either, Can, None {}

    record Single(String screen, String action) implements Gate {}

    record Either(String screen, String action, String otherScreen, String otherAction)
            implements Gate {}

    record Can(String screen, String action) implements Gate {}

    /** No static gate: method security alone decides. */
    record None() implements Gate {}

    private static final Gate NONE = new None();

    private final AccessPolicy policy;
    private final Map<Method, Gate> gates = new ConcurrentHashMap<>();

    public GateFirstInterceptor(AccessPolicy policy) {
        this.policy = policy;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        // The original request was gated; an async or error dispatch of it is not asked again.
        if (request.getDispatcherType() != DispatcherType.REQUEST
                || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        switch (gates.computeIfAbsent(method.getMethod(), m -> gateOf(method))) {
            case Single g -> policy.gate(g.screen(), g.action());
            case Either g ->
                    policy.gateEither(g.screen(), g.action(), g.otherScreen(), g.otherAction());
            case Can g -> {
                if (!policy.can(g.screen(), g.action())) {
                    throw new AccessDeniedException("Access Denied");
                }
            }
            case None n -> {
                // method security alone
            }
        }
        return true;
    }

    /** The static gate of a handler method (its own annotation, else its class's). */
    static Gate gateOf(HandlerMethod method) {
        PreAuthorize annotation =
                Optional.ofNullable(
                                AnnotatedElementUtils.findMergedAnnotation(
                                        method.getMethod(), PreAuthorize.class))
                        .orElseGet(
                                () ->
                                        AnnotatedElementUtils.findMergedAnnotation(
                                                method.getBeanType(), PreAuthorize.class));
        return annotation == null ? NONE : parse(annotation.value().trim());
    }

    static Gate parse(String expression) {
        Matcher m = GATE.matcher(expression);
        if (m.matches()) {
            return new Single(m.group(1), m.group(2));
        }
        m = EITHER.matcher(expression);
        if (m.matches()) {
            return new Either(m.group(1), m.group(2), m.group(3), m.group(4));
        }
        m = CAN.matcher(expression);
        if (m.matches()) {
            return new Can(m.group(1), m.group(2));
        }
        return NONE;
    }
}
