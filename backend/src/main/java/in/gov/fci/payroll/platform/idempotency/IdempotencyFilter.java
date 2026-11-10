package in.gov.fci.payroll.platform.idempotency;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import in.gov.fci.payroll.platform.security.CachedBodyHttpServletRequest;
import in.gov.fci.payroll.platform.security.CurrentUser;
import in.gov.fci.payroll.platform.security.InputPatterns;
import in.gov.fci.payroll.platform.security.SecureTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.UrlPathHelper;

/**
 * Enforces {@code Idempotency-Key} on every state-changing POST of a signed-in user (ADR-0011): the
 * first request is processed and its response stored; a retry with the same key and body gets the
 * stored response ({@code Idempotent-Replayed: true}); the same key with a different body is 422; a
 * retry while the first is still running is 409. Server errors are not stored, so the client may
 * retry. Runs after the security filter chain (needs the user id).
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final Pattern KEY = Pattern.compile(InputPatterns.UUID);
    private static final int MAX_STORED_BODY = 256 * 1024;

    /**
     * The largest body read here, as NGINX's largest location allows (client_max_body_size 11m of
     * the upload locations, config/nginx/conf.d/fci-payroll.conf): a non-JSON body the JSON key
     * guard does not bound is never read whole into memory (security review 2026-11-04, W-7).
     */
    static final int MAX_BODY_BYTES = 11 * 1024 * 1024;

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final IdempotencyService idempotency;
    private final CurrentUser currentUser;
    private final ProblemResponder responder;

    public IdempotencyFilter(
            IdempotencyService idempotency, CurrentUser currentUser, ProblemResponder responder) {
        this.idempotency = idempotency;
        this.currentUser = currentUser;
        this.responder = responder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request);
        return !"POST".equals(request.getMethod())
                || !path.startsWith("/v1/")
                || path.startsWith("/v1/auth/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> userId = currentUser.userId();
        if (userId.isEmpty()) {
            // Unauthenticated requests were already rejected by security; nothing to deduplicate.
            chain.doFilter(request, response);
            return;
        }
        String key = request.getHeader(HEADER);
        if (key == null || !KEY.matcher(key).matches()) {
            responder.write(null, CommonErrorCode.IDEMPOTENCY_KEY_REQUIRED, request, response);
            return;
        }
        // A multipart upload is fingerprinted from its parts: reading the raw body here would leave
        // the container nothing to parse, and every form field of the upload would be lost.
        byte[] body;
        String fingerprint;
        if (isMultipart(request)) {
            body = null;
            try {
                fingerprint = multipartFingerprint(request);
            } catch (IllegalStateException tooLarge) {
                // The container refuses a part or request over the spring.servlet.multipart limits.
                responder.write(null, CommonErrorCode.PAYLOAD_TOO_LARGE, request, response);
                return;
            } catch (ServletException malformed) {
                responder.write(null, CommonErrorCode.MALFORMED_REQUEST, request, response);
                return;
            }
        } else {
            body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                responder.write(null, CommonErrorCode.PAYLOAD_TOO_LARGE, request, response);
                return;
            }
            fingerprint = new String(body, StandardCharsets.UTF_8);
        }
        // The query string is part of the request (W-6): the same key and body with another
        // ?finYear= is another request, refused as a mismatch rather than answered by a replay.
        String query = request.getQueryString();
        String requestHash =
                SecureTokens.sha256Hex(
                        request.getMethod()
                                + " "
                                + request.getRequestURI()
                                + (query == null ? "" : "?" + query)
                                + "\n"
                                + fingerprint);
        IdempotencyService.Decision decision = idempotency.begin(userId.get(), key, requestHash);
        if (decision instanceof IdempotencyService.Decision.Replay replay) {
            response.setStatus(replay.status());
            if (replay.contentType() != null) {
                response.setContentType(replay.contentType());
            }
            response.setHeader(REPLAYED_HEADER, "true");
            if (replay.body() != null) {
                response.getOutputStream().write(replay.body().getBytes(StandardCharsets.UTF_8));
            }
        } else if (decision instanceof IdempotencyService.Decision.InProgress) {
            responder.write(null, CommonErrorCode.IDEMPOTENCY_IN_PROGRESS, request, response);
        } else if (decision instanceof IdempotencyService.Decision.Mismatch) {
            responder.write(null, CommonErrorCode.IDEMPOTENCY_KEY_MISMATCH, request, response);
        } else if (decision instanceof IdempotencyService.Decision.Proceed proceed) {
            process(request, response, chain, body, proceed.recordId());
        }
    }

    private void process(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain,
            byte[] body,
            String recordId)
            throws IOException, ServletException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            // A multipart request goes on as it is: the container has already parsed its parts.
            chain.doFilter(
                    body == null ? request : new CachedBodyHttpServletRequest(request, body),
                    wrapper);
            int status = wrapper.getStatus();
            byte[] content = wrapper.getContentAsByteArray();
            if (status < 500 && content.length <= MAX_STORED_BODY) {
                idempotency.complete(
                        recordId,
                        status,
                        wrapper.getContentType(),
                        new String(content, StandardCharsets.UTF_8));
                stored = true;
            }
        } finally {
            if (!stored) {
                idempotency.abandon(recordId);
            }
            wrapper.copyBodyToResponse();
        }
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
    }

    /**
     * What makes two uploads "the same request": the query string and, per part in name order, its
     * name, file name, size and the SHA-256 of its bytes. A retry of the same upload replays; the
     * same key with another file is a mismatch (422), as for a JSON body.
     */
    static String multipartFingerprint(HttpServletRequest request)
            throws IOException, ServletException {
        List<Part> parts = new ArrayList<>(request.getParts());
        parts.sort(
                Comparator.comparing(Part::getName)
                        .thenComparing(p -> Objects.toString(p.getSubmittedFileName(), "")));
        StringBuilder out =
                new StringBuilder("?").append(Objects.toString(request.getQueryString(), ""));
        for (Part part : parts) {
            byte[] content;
            try (InputStream in = part.getInputStream()) {
                content = in.readAllBytes();
            }
            out.append('\n')
                    .append(part.getName())
                    .append('|')
                    .append(Objects.toString(part.getSubmittedFileName(), ""))
                    .append('|')
                    .append(content.length)
                    .append('|')
                    .append(HexFormat.of().formatHex(SecureTokens.sha256(content)));
        }
        return out.toString();
    }
}
