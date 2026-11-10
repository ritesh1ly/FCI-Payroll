package in.gov.fci.payroll.platform.security;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

/**
 * Rejects JSON request bodies that could abuse the parser or smuggle unexpected property paths,
 * before they reach a controller (defence in depth for JSON binding; first introduced with
 * ADR-0005):
 *
 * <ul>
 *   <li>property names starting with {@code $}, or containing {@code .} or NUL;
 *   <li>property names longer than 128 characters;
 *   <li>nesting deeper than the configured depth, or bodies larger than the configured size;
 *   <li>a property named twice in one object ({@code {"reason":"a","reason":"b"}}): parsers
 *       disagree on which one wins (first, last, both), so a value checked by one component could
 *       differ from the value another uses (JSON parser differential, OWASP ASVS V1.5 / V5.1;
 *       security review 2026-11-04). Refused as malformed rather than resolved.
 * </ul>
 *
 * The body is buffered (bounded) and replayed to the rest of the chain.
 */
public class JsonKeyGuardFilter extends OncePerRequestFilter {

    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final int MAX_NAME_LENGTH = 128;
    private static final char NUL = 0;

    private final ProblemResponder responder;
    private final int maxBodyBytes;
    private final JsonFactory jsonFactory;

    public JsonKeyGuardFilter(ProblemResponder responder, int maxBodyBytes, int maxNestingDepth) {
        this.responder = responder;
        this.maxBodyBytes = maxBodyBytes;
        this.jsonFactory =
                JsonFactory.builder()
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(
                                StreamReadConstraints.builder()
                                        .maxNestingDepth(maxNestingDepth)
                                        .maxNameLength(MAX_NAME_LENGTH)
                                        .maxStringLength(maxBodyBytes)
                                        .maxDocumentLength(maxBodyBytes)
                                        .build())
                        .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!BODY_METHODS.contains(request.getMethod())) {
            return true;
        }
        String contentType = request.getContentType();
        if (contentType == null) {
            return true;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return !(MediaType.APPLICATION_JSON.includes(mediaType)
                    || mediaType.getSubtype().endsWith("+json"));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxBodyBytes) {
            responder.write(null, CommonErrorCode.PAYLOAD_TOO_LARGE, request, response);
            return;
        }
        byte[] body = readBounded(request.getInputStream());
        if (body == null) {
            responder.write(null, CommonErrorCode.PAYLOAD_TOO_LARGE, request, response);
            return;
        }
        if (body.length > 0) {
            CommonErrorCode problem = inspect(body);
            if (problem != null) {
                responder.write(null, problem, request, response);
                return;
            }
        }
        chain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    /** Returns the error to report, or {@code null} if the document is acceptable. */
    CommonErrorCode inspect(byte[] body) {
        try (JsonParser parser = jsonFactory.createParser(ObjectReadContext.empty(), body)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.PROPERTY_NAME && !isAllowedName(parser.currentName())) {
                    return CommonErrorCode.FORBIDDEN_JSON_KEY;
                }
            }
            return null;
        } catch (JacksonException ex) {
            // Malformed JSON and every constraint breach (nesting, name, string or document size).
            return CommonErrorCode.MALFORMED_REQUEST;
        }
    }

    static boolean isAllowedName(String name) {
        return name != null
                && !name.isEmpty()
                && name.length() <= MAX_NAME_LENGTH
                && name.charAt(0) != '$'
                && name.indexOf('.') < 0
                && name.indexOf(NUL) < 0;
    }

    private byte[] readBounded(InputStream in) throws IOException {
        byte[] data = in.readNBytes(maxBodyBytes + 1);
        return data.length > maxBodyBytes ? null : data;
    }
}
