package in.gov.fci.payroll.integration.api;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The one door of Payroll's modules to the HRMS APIs (SRS-INT-1, SRS-INT-6, SRS-INT-35; owner
 * 2026-11-06). A module names the API ({@link HrmsApi}), gives the query parameters and the JSON
 * body exactly as FCI's Postman collection shows them, and gets a classified {@link HrmsReply}. The
 * URL, the api-key, the bearer token (taken from {@code POST token} and renewed), the pooled
 * WebClient, the bulkhead, the retry and the audit window belong to the gateway, never to the
 * caller. A call that contradicts the collection is not sent (INVALID_REQUEST). An API that is not
 * configured for the OUTBOUND direction, an interface without settings in force, a full bulkhead
 * and a call that still fails after its retries raise an {@code FciException}.
 */
public interface HrmsGateway {

    /**
     * Calls an API.
     *
     * @param query URL parameters, or null
     * @param body the JSON body, or null
     * @param idempotencyKey sent as {@code Idempotency-Key} on every attempt; null makes a new one
     */
    HrmsReply call(HrmsApi api, Map<String, String> query, JsonNode body, String idempotencyKey);

    /** A status read ({@code get-*}). */
    default HrmsReply status(HrmsApi api, Map<String, String> query) {
        return call(api, query, null, null);
    }

    /** A request ({@code request-*}, {@code post-*}, {@code updating-*}). */
    default HrmsReply submit(HrmsApi api, Map<String, String> query, JsonNode body, String key) {
        return call(api, query, body, key);
    }

    /** A cancel ({@code PUT cancel-*}). */
    default HrmsReply cancel(HrmsApi api, JsonNode body, String key) {
        return call(api, null, body, key);
    }

    /** The welcome call on the base address: is HRMS there, and does it accept our api-key. */
    HrmsReply welcome();
}
