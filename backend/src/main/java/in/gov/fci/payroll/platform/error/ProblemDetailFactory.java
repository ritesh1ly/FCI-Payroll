package in.gov.fci.payroll.platform.error;

import in.gov.fci.payroll.platform.logging.MdcFilter;
import in.gov.fci.payroll.platform.logging.MdcKeys;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ProblemDetail;

/**
 * Builds RFC 9457 problem details with the FCI extension members {@code errorCode}, {@code
 * messageKey}, {@code severity} and {@code actionKey} (SRS-EH-1, SRS-EH-2), {@code args}, {@code
 * correlationId}, {@code timestamp} and, for validation errors, {@code violations}. The services
 * never translate (FCILekha i18n rule): {@code title} and {@code detail} are always English, for
 * logs and non-UI clients, and the user interface renders {@code messageKey} with {@code args} from
 * its own bundle in the user's language. Stack traces and request input are never included.
 */
public class ProblemDetailFactory {

    private static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9/._~-]{0,255}$");

    private final String typeBaseUri;
    private final MessageSource messages;
    private final Clock clock;

    /**
     * @param typeBaseUri base of the {@code type} URIs, ending with {@code /}
     * @param applicationMessages the application's message source, consulted after the common error
     *     bundle; may be null
     */
    public ProblemDetailFactory(
            String typeBaseUri, MessageSource applicationMessages, Clock clock) {
        this.typeBaseUri = typeBaseUri.endsWith("/") ? typeBaseUri : typeBaseUri + "/";
        ResourceBundleMessageSource common = new ResourceBundleMessageSource();
        common.setBasename("in/gov/fci/payroll/platform/error/messages");
        common.setDefaultEncoding("UTF-8");
        common.setFallbackToSystemLocale(false);
        common.setUseCodeAsDefaultMessage(false);
        if (applicationMessages != null) {
            common.setParentMessageSource(applicationMessages);
        }
        this.messages = common;
        this.clock = clock;
    }

    public ProblemDetail create(
            ErrorCode code, HttpServletRequest request, Object[] args, List<Violation> violations) {
        Locale locale = Locale.ENGLISH;
        ProblemDetail problem = ProblemDetail.forStatus(code.status());
        problem.setType(URI.create(typeBaseUri + code.type()));
        problem.setTitle(messages.getMessage(code.titleKey(), null, code.defaultTitle(), locale));
        problem.setDetail(
                messages.getMessage(code.detailKey(), args, code.defaultDetail(), locale));
        String path = request == null ? null : request.getRequestURI();
        if (path != null && SAFE_PATH.matcher(path).matches()) {
            problem.setInstance(URI.create(path));
        }
        problem.setProperty("errorCode", code.code());
        problem.setProperty("messageKey", messageKey(code));
        // SRS-EH-1, SRS-EH-2: the catalogue's severity and corrective action of the message.
        problem.setProperty("severity", code.severity().name());
        problem.setProperty("actionKey", code.actionKey());
        problem.setProperty(
                "args",
                args == null ? List.of() : Arrays.stream(args).map(String::valueOf).toList());
        problem.setProperty("correlationId", correlationId(request));
        problem.setProperty("timestamp", Instant.now(clock).toString());
        if (violations != null && !violations.isEmpty()) {
            problem.setProperty("violations", List.copyOf(violations));
        }
        return problem;
    }

    /** Resolves a message for a violation, falling back to the supplied default. */
    public String message(String key, Object[] args, String defaultMessage, Locale locale) {
        return messages.getMessage(key, args, defaultMessage, resolveLocale(locale));
    }

    public MessageSource messageSource() {
        return messages;
    }

    /**
     * The UI message key of an error code: {@code error.<code>}, e.g. {@code
     * error.AUTH-423-LOCKED}.
     */
    public static String messageKey(ErrorCode code) {
        return "error." + code.code();
    }

    /** Always English: translation happens in the user interface (see the class comment). */
    static Locale resolveLocale(Locale requested) {
        return Locale.ENGLISH;
    }

    private static String correlationId(HttpServletRequest request) {
        String fromMdc = MdcFilter.currentCorrelationId();
        if (fromMdc != null) {
            return fromMdc;
        }
        Object attribute =
                request == null ? null : request.getAttribute(MdcKeys.CORRELATION_ATTRIBUTE);
        return attribute instanceof String id ? id : null;
    }
}
