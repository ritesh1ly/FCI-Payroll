package in.gov.fci.payroll.integration.api;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What an HRMS API answered, classified (SRS-INT-6, SRS-INT-31; R-INT, R-PII). The collection FCI
 * supplied has no saved response, so the reading is defensive and uses only what the token script
 * of the collection proves ({@code data.token}) plus the common envelope seen on the test double:
 * {@code status}, {@code status_code}, {@code error_code}, {@code message}, {@code data}, {@code
 * errors[]}. A reply that does not fit is never guessed at: it is UNREADABLE, and the caller
 * decides. Only a short message and the error code are kept for display; the body is not logged.
 *
 * @param outcome how the answer is to be treated
 * @param httpStatus the HTTP status, or 0 when no call was made
 * @param errorCode HRMS's {@code error_code}, or null
 * @param message HRMS's {@code message} (at most 300 characters), or the reason for INVALID_REQUEST
 * @param data the {@code data} node, or null
 * @param problems field-level messages ({@code errors[]}) or, for INVALID_REQUEST, the contract
 *     defects
 * @param duplicate HRMS says the request was already held (an idempotent repeat)
 * @param attempts how many calls it took (1 = no retry)
 */
public record HrmsReply(
        Outcome outcome,
        int httpStatus,
        String errorCode,
        String message,
        JsonNode data,
        List<String> problems,
        boolean duplicate,
        int attempts) {

    /** How an answer is to be treated. */
    public enum Outcome {
        /** 2xx and not marked as an error in the body. */
        ACCEPTED,
        /** 4xx: HRMS understood the call and refused it (see {@code errorCode}). */
        REJECTED,
        /** 2xx but the body says {@code "status":"error"}. */
        FAILED_IN_BODY,
        /** 2xx with an empty, truncated or non-JSON body. */
        UNREADABLE,
        /** The call was not made: it does not match the contract of the collection. */
        INVALID_REQUEST
    }

    private static final int MESSAGE_LIMIT = 300;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Whether the business step succeeded (ACCEPTED). */
    public boolean accepted() {
        return outcome == Outcome.ACCEPTED;
    }

    /** The reply of a call that was never made because it contradicts the contract. */
    public static HrmsReply invalid(List<String> defects) {
        return new HrmsReply(
                Outcome.INVALID_REQUEST,
                0,
                "CONTRACT_MISMATCH",
                "the request does not match the HRMS contract",
                null,
                List.copyOf(defects),
                false,
                0);
    }

    /** Classifies an answer. */
    public static HrmsReply parse(int status, String body, int attempts) {
        boolean ok = status >= 200 && status < 300;
        JsonNode root = null;
        if (body != null && !body.isBlank()) {
            try {
                root = MAPPER.readTree(body);
            } catch (JacksonException notJson) {
                root = null;
            }
        }
        if (root == null || !root.isObject()) {
            String why = body == null || body.isBlank() ? "empty body" : "not a JSON object";
            return ok
                    ? new HrmsReply(
                            Outcome.UNREADABLE,
                            status,
                            null,
                            "HTTP " + status + ": " + why,
                            null,
                            List.of(),
                            false,
                            attempts)
                    : new HrmsReply(
                            Outcome.REJECTED,
                            status,
                            null,
                            "HTTP " + status + ": " + why,
                            null,
                            List.of(),
                            false,
                            attempts);
        }
        String errorCode = text(root, "error_code");
        String message = cut(text(root, "message"));
        JsonNode data = root.has("data") && !root.get("data").isNull() ? root.get("data") : null;
        boolean duplicate = flag(root, "duplicate") || (data != null && flag(data, "duplicate"));
        List<String> problems = new ArrayList<>();
        JsonNode errors = root.get("errors");
        if (errors != null && errors.isArray()) {
            for (JsonNode e : errors) {
                String field = text(e, "field");
                String msg = cut(text(e, "message"));
                problems.add(field == null ? String.valueOf(msg) : field + ": " + msg);
            }
        }
        Outcome outcome;
        if (!ok) {
            outcome = Outcome.REJECTED;
        } else if ("error".equalsIgnoreCase(text(root, "status"))) {
            outcome = Outcome.FAILED_IN_BODY;
        } else {
            outcome = Outcome.ACCEPTED;
        }
        return new HrmsReply(
                outcome,
                status,
                errorCode,
                message,
                data,
                List.copyOf(problems),
                duplicate,
                attempts);
    }

    private static String text(JsonNode node, String name) {
        JsonNode v = node == null ? null : node.get(name);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asString();
    }

    private static boolean flag(JsonNode node, String name) {
        JsonNode v = node.get(name);
        return v != null && v.isBoolean() && v.asBoolean();
    }

    private static String cut(String s) {
        return s == null || s.length() <= MESSAGE_LIMIT ? s : s.substring(0, MESSAGE_LIMIT);
    }
}
