package in.gov.fci.payroll.integration.web;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.business.HrmsContracts;
import in.gov.fci.payroll.integration.business.HrmsPayloads;
import in.gov.fci.payroll.integration.business.IntegrationErrorCode;
import in.gov.fci.payroll.integration.orchestration.HrmsAccessService;
import in.gov.fci.payroll.integration.orchestration.HrmsInboundService;
import in.gov.fci.payroll.integration.orchestration.HrmsRequestService;
import in.gov.fci.payroll.platform.error.FciException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The one handler behind every published HRMS API (SRS-INT-1, SRS-INT-4, SRS-INT-5, SRS-INT-23,
 * SRS-INT-31, SRS-INT-35): {@link HrmsApiRegistrar} maps each configured path to {@link
 * #handle(HttpServletRequest)}, which finds the API by method and path and hands the call to its
 * service. Not a {@code @RestController}: a path exists only when its URL setting is set. The
 * caller is already authenticated by {@link HrmsApiAuthFilter}; the answers are built field by
 * field and hold no PAN, bank or Aadhaar value (R-PII).
 */
@Component
@Profile("online")
public class HrmsApiHandler {

    /** A request body larger than this is refused (an HRMS record is a few hundred bytes). */
    public static final int MAX_BODY_BYTES = 262_144;

    private final HrmsApiRoutes routes;
    private final HrmsAccessService access;
    private final HrmsInboundService inbound;
    private final HrmsRequestService requests;

    public HrmsApiHandler(
            HrmsApiRoutes routes,
            HrmsAccessService access,
            HrmsInboundService inbound,
            HrmsRequestService requests) {
        this.routes = routes;
        this.access = access;
        this.inbound = inbound;
        this.requests = requests;
    }

    /** Serves the call. */
    public ResponseEntity<Map<String, Object>> handle(HttpServletRequest request)
            throws IOException {
        HrmsApi api =
                routes.match(request)
                        .orElseThrow(
                                () ->
                                        new FciException(
                                                in.gov.fci.payroll.platform.error.CommonErrorCode
                                                        .NOT_FOUND));
        return switch (HrmsContracts.kind(api)) {
            case TOKEN -> token(request);
            case INTAKE -> intake(api, request);
            case STATUS -> json(200, requests.status(api, query(request)));
            case CANCEL -> json(200, requests.cancel(api, body(request)));
            case REPORT ->
                    throw new FciException(
                            IntegrationErrorCode.HRMS_NOT_ENABLED,
                            new Object[] {api.code()},
                            List.of(),
                            null);
        };
    }

    private ResponseEntity<Map<String, Object>> token(HttpServletRequest request)
            throws IOException {
        JsonNode json;
        try {
            json = HrmsPayloads.parse(body(request));
            for (String name : json.propertyNames()) {
                if (!name.equals("username") && !name.equals("password")) {
                    throw new IllegalArgumentException("unknown field");
                }
            }
        } catch (IllegalArgumentException bad) {
            throw malformed(bad.getMessage());
        }
        HrmsAccessService.Token token =
                access.exchange(
                        json.path("username").isString() ? json.path("username").asString() : null,
                        json.path("password").isString() ? json.path("password").asString() : null,
                        request.getRemoteAddr());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", token.accessToken());
        out.put("token_type", "Bearer");
        out.put("expires_in", token.expiresIn());
        return json(200, out);
    }

    private ResponseEntity<Map<String, Object>> intake(HrmsApi api, HttpServletRequest request)
            throws IOException {
        HrmsInboundService.Result result = inbound.intake(api, query(request), body(request));
        if (result.acks().size() == 1) {
            return json(result.http(), ack(result.acks().getFirst()));
        }
        long accepted = result.acks().stream().filter(a -> a.http() < 400).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", result.acks().stream().map(HrmsApiHandler::ack).toList());
        out.put("received", result.acks().size());
        out.put("accepted", accepted);
        out.put("rejected", result.acks().size() - accepted);
        return json(result.http(), out);
    }

    /** One acknowledgement, field by field (nothing of the request is echoed but its id). */
    private static Map<String, Object> ack(HrmsInboundService.Ack ack) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("request_id", ack.requestId());
        out.put("hrms_request_id", ack.hrmsRequestId());
        out.put("status", ack.status());
        if (ack.reason() != null) {
            out.put("reason", ack.reason());
        }
        out.put("duplicate", ack.duplicate());
        return out;
    }

    private static ResponseEntity<Map<String, Object>> json(int status, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl("no-store");
        return ResponseEntity.status(status).headers(headers).body(body);
    }

    /** The URL parameters; a parameter repeated is refused (two values would be two readings). */
    private static Map<String, String> query(HttpServletRequest request) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : request.getParameterMap().entrySet()) {
            if (e.getValue().length != 1) {
                throw malformed(
                        "parameter "
                                + (e.getKey().length() > 40 ? "?" : e.getKey())
                                + " is repeated");
            }
            out.put(e.getKey(), e.getValue()[0]);
        }
        return out;
    }

    /** The JSON body, bounded; anything but application/json is refused. */
    private static byte[] body(HttpServletRequest request) throws IOException {
        String type = request.getContentType();
        if (type == null
                || !MediaType.parseMediaType(type).isCompatibleWith(MediaType.APPLICATION_JSON)) {
            throw malformed("Content-Type must be application/json");
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new FciException(
                    IntegrationErrorCode.HRMS_TOO_LARGE,
                    new Object[] {MAX_BODY_BYTES},
                    List.of(),
                    null);
        }
        return body;
    }

    private static FciException malformed(String why) {
        return new FciException(
                IntegrationErrorCode.HRMS_MALFORMED, new Object[] {why}, List.of(), null);
    }
}
